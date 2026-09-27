package gov.objectlibrary.server;

import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads Pack contracts; only reviewed Java handlers can activate a command. */
@Component
public class DomainContracts {
    private static final Set<String> CONNECTED_ACTIONS = Set.of(
            "AddPersonTag", "RemovePersonTag", "RestorePersonTag", "SaveReminderDraft",
            "UpdateRecipientSelection", "ConfirmRecipientSelection", "ConfirmReminderContent", "SubmitReminderReview",
            "DecideReminderReview", "WithdrawReminderReview", "CancelScheduledReminder", "ExpireReminderReview",
            "RegisterMediaAsset", "SaveContentExample", "SetContentAvailability",
            "DispatchReminder", "RecordDeliveryReceipt", "RetryFailedRecipients", "ReadOwnReminder", "RecordFirstRead",
            "EvaluateOverdue", "RecordIntegrationIssue", "ReadOverdueList",
            "SaveReminderRevision", "SubmitReminderRevision", "PublishReminderRevision",
            "RequestReminderWithdrawal", "DispatchReminderWithdrawal", "RecordWithdrawalResult", "RetryWithdrawal", "QueryBusinessMetrics", "CreateTagRule", "PreviewRuleChange", "PublishRuleVersion",
            "StartTagBatch", "ApplyRuleEvaluation", "CompleteTagBatch");

    private static final Set<String> RECIPIENT_ACTIONS = Set.of("ReadOwnReminder", "RecordFirstRead");

    private static final Map<String, String> SYSTEM_ACTIONS = Map.ofEntries(
            Map.entry("ExpireReminderReview", "scheduler"),
            Map.entry("DispatchReminder", "delivery-worker"),
            Map.entry("RecordDeliveryReceipt", "channel-adapter"),
            Map.entry("EvaluateOverdue", "scheduler"),
            Map.entry("PublishReminderRevision", "publication-worker"),
            Map.entry("DispatchReminderWithdrawal", "withdrawal-worker"),
            Map.entry("RecordWithdrawalResult", "channel-adapter"),
            Map.entry("RecordIntegrationIssue", "channel-or-receiver-adapter"),
            Map.entry("StartTagBatch", "system-or-configuration-admin"),
            Map.entry("PreviewRuleChange", "system-or-configuration-admin"),
            Map.entry("ApplyRuleEvaluation", "rule-worker"),
            Map.entry("CompleteTagBatch", "batch-worker"));

    private final Map<String, Map<String, Object>> actions = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> states = new LinkedHashMap<>();
    private final Set<String> objectTypes;
    private final StorageProvider storage;
    private final Accounts accounts;
    private final String digest;

    public DomainContracts(@Value("${mirror.pack}") String packDirectory,
                           StorageProvider storage, Accounts accounts) throws IOException {
        this.storage = storage;
        this.accounts = accounts;
        Path root = Path.of(packDirectory).toAbsolutePath().normalize();
        var pack = new DomainPackLoader().load(root);
        objectTypes = pack.ontology().objectTypes().keySet();
        String actionSource = Files.readString(root.resolve("contracts/actions.yaml"));
        String stateSource = Files.readString(root.resolve("contracts/states.yaml"));
        String ruleSource = Files.readString(root.resolve("contracts/rules.yaml"));
        digest = BusinessCommands.hash(pack.ontology().schemaDigest() + actionSource + stateSource + ruleSource);
        for (var definition : rows(parse(actionSource).get("actions"))) {
            String name = text(definition.get("name"));
            if (actions.put(name, definition) != null) {
                throw new IllegalArgumentException("Duplicate business contract: " + name);
            }
        }
        for (var definition : rows(parse(stateSource).get("models"))) {
            states.put(text(definition.get("object")) + "." + text(definition.get("field")), definition);
        }
        if (!actions.keySet().containsAll(CONNECTED_ACTIONS)) {
            throw new IllegalArgumentException("Connected action is absent from Domain Pack");
        }
    }

    void authorizeSystem(String action) {
        var definition = requireConnected(action);
        if (!text(definition.get("actor")).equals(SYSTEM_ACTIONS.get(action))) {
            throw new org.springframework.security.access.AccessDeniedException("Not an internal service action");
        }
    }

    void authorizeRecipient(String action) {
        var definition = requireConnected(action);
        if (!RECIPIENT_ACTIONS.contains(action) || !text(definition.get("actor")).equals("recipient")) {
            throw new org.springframework.security.access.AccessDeniedException("Not a recipient action");
        }
        // ReceiverService must validate its bound session and current version before invoking this path.
    }

    public String eventType(String action) {
        return text(requireConnected(action).get("event"));
    }

    public String digest() {
        return digest;
    }

    public void authorize(Accounts.Actor actor, String action) {
        var definition = requireConnected(action);
        if ((SYSTEM_ACTIONS.containsKey(action) && !Set.of("StartTagBatch", "PreviewRuleChange").contains(action)) || RECIPIENT_ACTIONS.contains(action)) {
            throw new org.springframework.security.access.AccessDeniedException("Internal actions cannot be invoked by an account");
        }
        // Reload the principal so a retained Java Actor cannot bypass a disabled account or changed role.
        var current = accounts.actor(actor.username());
        if (!current.equals(actor)) {
            throw new org.springframework.security.access.AccessDeniedException("Account context changed");
        }
        accounts.requirePermission(current, text(definition.get("permission")));
    }

    public void validateInputs(Accounts.Actor actor, String action, Map<String, Object> input) {
        Map<String, Object> declared = mapping(requireConnected(action).get("inputs"));
        if (!declared.keySet().containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unexpected business command input");
        }
        for (var parameter : declared.entrySet()) {
            String type = text(parameter.getValue());
            Object value = input.get(parameter.getKey());
            if (value == null) {
                if (!type.endsWith("?")) throw new IllegalArgumentException("Missing " + parameter.getKey());
                continue;
            }
            validateValue(actor, type.replace("?", ""), value);
        }
    }

    public void requireTransition(String object, String field, String from, String to, String action) {
        requireConnected(action);
        var model = states.get(object + "." + field);
        if (model == null || !((List<?>) model.get("values")).contains(to)) {
            throw new IllegalArgumentException("State is absent from Domain Pack");
        }
        if (from == null || from.equals(to)) return;
        boolean permitted = rows(model.get("transitions")).stream().anyMatch(t ->
                from.equals(t.get("from")) && to.equals(t.get("to")) && action.equals(t.get("action")));
        if (!permitted) throw new BusinessConflict("Domain Pack不允许本次状态变化，请刷新并核对操作");
    }

    private Map<String, Object> requireConnected(String action) {
        if (!actions.containsKey(action)) throw new IllegalArgumentException("Undefined business action: " + action);
        if (!CONNECTED_ACTIONS.contains(action)) throw new BusinessConflict("该业务动作尚未接通处理器");
        return actions.get(action);
    }

    private void validateValue(Accounts.Actor actor, String type, Object value) {
        if (type.endsWith("[]")) {
            if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Expected list");
            values.forEach(v -> validateValue(actor, type.substring(0, type.length() - 2), v));
        } else if (objectTypes.contains(type)) {
            if (!(value instanceof String id) || id.isBlank()) throw new IllegalArgumentException("Expected object ID");
            var object = storage.getObject(actor.context(), type, id);
            if (object == null || object.isDeleted()) throw new BusinessConflict("关联对象不存在或已删除");
        } else {
            switch (type) {
                case "Int" -> {
                    if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Expected integer");
                }
                case "Boolean" -> {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean");
                }
                case "String", "ID" -> {
                    if (!(value instanceof String)) throw new IllegalArgumentException("Expected text");
                }
                case "DateTime" -> Instant.parse(text(value));
                case "Date" -> LocalDate.parse(text(value));
                case "JSON" -> {
                    if (!(value instanceof Map<?, ?> || value instanceof List<?>)) throw new IllegalArgumentException("Expected structured JSON");
                }
                default -> throw new IllegalArgumentException("Unknown contract type: " + type);
            }
        }
    }

    private static Map<String, Object> parse(String source) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return mapping(new Yaml(new SafeConstructor(options)).load(source));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected contract mapping");
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Expected contract list");
        return values.stream().map(DomainContracts::mapping).toList();
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string");
        return text;
    }
}
