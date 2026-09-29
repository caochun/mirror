package gov.mirror.app;

import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.spi.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public final class PersonnelService {
    private static final QueryOptions ALL = new QueryOptions(Integer.MAX_VALUE, 0, null, null, false);
    private static final Set<String> COMMANDS = Set.of("RegisterManualPerson", "DecideObjectMembership",
            "AssignManualTag", "ApplyManualTagContribution", "SuppressPersonTag");
    private final FoundryRuntime runtime;
    private final MirrorAccounts accounts;
    private final PersonnelPolicy policy;
    private final ActionExecutor executor;

    public PersonnelService(FoundryRuntime runtime, MirrorAccounts accounts) {
        this.runtime = runtime;
        this.accounts = accounts;
        policy = new PersonnelPolicy(accounts, runtime.storage());
        executor = new ActionExecutor().withParameterSchema(runtime.pack().ontology().schema()).withAuthorization(policy);
    }

    public Map<String, Object> list(String search, String status, String organization, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        var rows = objects("Person").stream().filter(person -> visible(person.key()))
                .filter(person -> String.valueOf(person.properties().get("name")).contains(search))
                .map(this::summary)
                .filter(row -> status.isBlank() || status.equals(row.get("status")))
                .filter(row -> organization.isBlank() || organization.equals(row.get("organizationId")))
                .filter(row -> visible(new EntityKey("Person", row.get("id").toString()))).toList();
        return Map.of("items", rows.stream().skip(offset).limit(limit).toList(), "total", rows.size(), "offset", offset);
    }

    public Map<String, Object> catalog() {
        return Map.of("organizations", objects("Organization").stream().filter(o -> visible(o.key()))
                        .filter(o -> "ACTIVE".equals(o.properties().get("sourceStatus"))).map(this::safe).toList(),
                "tags", objects("Tag").stream().filter(this::assignable).map(tag -> {
                    var current = endpoint(tag.key(), "TagCurrentVersion", false);
                    return Map.of("id", tag.id(), "version", tag.version(), "tagVersion", current.id(),
                            "name", current.properties().get("name"));
                }).toList());
    }

    public Map<String, Object> detail(String id) {
        var person = require("Person", id);
        var result = new LinkedHashMap<>(summary(person));
        result.put("person", safe(person));
        var membership = endpoint(person.key(), "MembershipPerson", true);
        result.put("membership", membership == null ? null : safe(membership));
        var personTags = targets(person.key(), "PersonTagPerson", true);
        result.put("tags", personTags.stream().map(pt -> {
            var tag = endpoint(pt.key(), "PersonTagTag", false);
            var version = tag == null ? null : endpoint(tag.key(), "TagCurrentVersion", false);
            return Map.of("id", pt.id(), "version", pt.version(), "tag", tag == null ? "" : tag.id(),
                    "tagVersion", version == null ? "" : version.id(),
                    "name", version == null ? "Unavailable tag" : version.properties().get("name"),
                    "suppression", pt.properties().get("suppression"), "effective", effective(person, pt),
                    "contributions", targets(pt.key(), "ContributionForPersonTag", true).stream().map(this::safe).toList());
        }).toList());
        var keys = new LinkedHashSet<EntityKey>();
        keys.add(person.key());
        if (membership != null) keys.add(membership.key());
        for (var pt : personTags) {
            keys.add(pt.key());
            targets(pt.key(), "ContributionForPersonTag", true).forEach(c -> keys.add(c.key()));
        }
        var histories = new ArrayList<Map<String, Object>>();
        for (var key : keys) {
            addHistory(histories, key);
            for (var definition : runtime.pack().ontology().schema().linkTypes()) {
                if (!definition.fromType().equals(key.type())) continue;
                var links = runtime.storage().getLinks(accounts.context(), key, definition.name(), StorageProvider.Direction.OUTBOUND,
                        new QueryOptions(Integer.MAX_VALUE, 0, null, null, true));
                links.stream().filter(link -> visible(link.from()) && visible(link.to()))
                        .forEach(link -> addHistory(histories, new EntityKey(link.type(), link.id())));
            }
        }
        result.put("history", histories);
        require("Person", id);
        return result;
    }

    private void addHistory(List<Map<String, Object>> output, EntityKey key) {
        for (var snapshot : runtime.storage().getEntityHistory(accounts.context(), key)) {
            output.add(Map.of("type", key.type(), "id", key.id(), "version", snapshot.version(),
                    "operation", snapshot.operation(), "actor", Objects.toString(snapshot.actorId(), ""),
                    "time", snapshot.recordedAt(), "state", redact(snapshot.state()),
                    "actionId", Objects.toString(snapshot.actionId(), ""), "transactionId", snapshot.transactionId()));
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sensitive = runtime.pack().ontology().schema().objectTypes().stream()
                    .flatMap(t -> t.properties().stream()).filter(p -> p.sensitive()).map(p -> p.name()).collect(Collectors.toSet());
            var clean = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> { if (!sensitive.contains(key.toString())) clean.put(key.toString(), redact(item)); });
            return clean;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    private Map<String, Object> safe(ObjectRecord record) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", record.id());
        result.put("version", record.version());
        result.put("properties", redact(record.properties()));
        return result;
    }

    private Map<String, Object> summary(ObjectRecord person) {
        var organization = endpoint(person.key(), "PersonCurrentOrganization", false);
        var membership = endpoint(person.key(), "MembershipPerson", true);
        return Map.of("id", person.id(), "version", person.version(), "name", person.properties().get("name"),
                "organizationId", organization == null ? "" : organization.id(),
                "organization", organization == null ? "" : organization.properties().get("name"),
                "status", membership == null ? "PENDING" : membership.properties().get("status"),
                "effectiveTags", targets(person.key(), "PersonTagPerson", true).stream().filter(pt -> effective(person, pt)).count());
    }

    private boolean assignable(ObjectRecord tag) {
        var version = endpoint(tag.key(), "TagCurrentVersion", false);
        return "ENABLED".equals(tag.properties().get("status")) && version != null
                && targets(tag.key(), "TagParent", true).isEmpty();
    }

    boolean effective(ObjectRecord person, ObjectRecord pt) {
        var membership = endpoint(person.key(), "MembershipPerson", true);
        var organization = endpoint(person.key(), "PersonCurrentOrganization", false);
        var tag = endpoint(pt.key(), "PersonTagTag", false);
        if (membership == null || !"IN_SCOPE".equals(membership.properties().get("status"))
                || organization == null || !"ACTIVE".equals(organization.properties().get("sourceStatus"))
                || tag == null || !assignable(tag) || !"NONE".equals(pt.properties().get("suppression"))) return false;
        var now = Instant.now();
        return targets(pt.key(), "ContributionForPersonTag", true).stream().anyMatch(c ->
                "ACTIVE".equals(c.properties().get("status"))
                        && !Instant.parse(c.properties().get("effectiveFrom").toString()).isAfter(now)
                        && (c.properties().get("effectiveTo") == null || Instant.parse(c.properties().get("effectiveTo").toString()).isAfter(now)));
    }

    public ActionResult command(String action, Map<String, Object> input, String key) {
        if (!COMMANDS.contains(action)) throw new SecurityException("Unsupported business command");
        if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Stable Idempotency-Key required");
        var definition = runtime.pack().ontology().schema().actionTypes().stream().filter(a -> a.name().equals(action)).findFirst().orElseThrow();
        var generated = Set.of("sourceOrganization", "decisionOrganization", "contributionId", "contributionKey", "pairKey", "personTagId");
        var declared = definition.parameters().stream().map(p -> p.name()).collect(Collectors.toSet());
        if (input.keySet().stream().anyMatch(field -> generated.contains(field) || !declared.contains(field))) {
            throw new IllegalArgumentException("Unknown or server-owned command field");
        }
        for (var parameter : definition.parameters()) {
            if (parameter.required() && !generated.contains(parameter.name()) && input.get(parameter.name()) == null) {
                throw new IllegalArgumentException("Missing required field: " + parameter.name());
            }
        }
        var values = new LinkedHashMap<>(input);
        for (String field : List.of("name", "note", "decisionCode")) {
            if (values.get(field) instanceof String text) {
                if (text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("Invalid " + field);
                values.put(field, text.trim());
            }
        }
        if (!action.equals("RegisterManualPerson") && (!(values.get("note") instanceof String note) || note.isBlank())) {
            throw new IllegalArgumentException("Operation reason is required");
        }
        var account = accounts.current();
        for (String field : List.of("sourceOrganization", "decisionOrganization")) {
            if (declared.contains(field)) values.put(field, account.organization());
        }
        String identity = LineageValues.hash(true, List.of(MirrorAccounts.TENANT, account.username(), action, key));
        for (String field : List.of("contributionId", "contributionKey")) {
            if (declared.contains(field)) values.put(field, identity);
        }
        if (action.equals("AssignManualTag")) {
            String pair = LineageValues.hash(true, List.of(values.get("person"), values.get("tag")));
            values.put("pairKey", pair);
            values.put("personTagId", "person_tag_" + pair);
        }
        Set<String> objectTypes = runtime.pack().ontology().schema().objectTypes().stream().map(t -> t.name()).collect(Collectors.toSet());
        var resolved = new LinkedHashMap<String, Object>();
        for (var parameter : definition.parameters()) {
            Object value = values.get(parameter.name());
            if (value != null && objectTypes.contains(parameter.type())) value = require(parameter.type(), value.toString());
            resolved.put(parameter.name(), value);
        }
        var result = executor.execute(runtime.pack().actions().get(action), definition, accounts.context(),
                new ActionActor(account.username(), account.roles()), Collections.unmodifiableMap(resolved), key, runtime.storage());
        if (!result.success()) {
            throw new IllegalStateException(result.errors().stream().map(ActionResult.Failure::message).collect(Collectors.joining("; ")));
        }
        return result;
    }

    private ObjectRecord require(String type, String id) {
        var object = runtime.storage().getObject(accounts.context(), type, id);
        if (object == null || object.isDeleted() || !visible(object.key())) throw new SecurityException("Object not available in your scope");
        return object;
    }

    private boolean visible(EntityKey key) { return policy.visible(accounts.current(), key, null, 0); }
    private List<ObjectRecord> objects(String type) { return runtime.storage().queryObjects(accounts.context(), type, ALL); }
    private ObjectRecord endpoint(EntityKey key, String type, boolean inbound) {
        var objects = targets(key, type, inbound);
        return objects.isEmpty() ? null : objects.getFirst();
    }
    private List<ObjectRecord> targets(EntityKey key, String type, boolean inbound) {
        return policy.links(accounts.context(), key, type, inbound, null).stream().map(link -> inbound ? link.from() : link.to())
                .map(target -> runtime.storage().getObject(accounts.context(), target.type(), target.id()))
                .filter(Objects::nonNull).filter(o -> !o.isDeleted()).toList();
    }
}
