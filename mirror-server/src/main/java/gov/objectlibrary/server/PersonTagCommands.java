package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manual tag handlers implementing the Pack's logical-tag and independent-contribution model. */
@Service
public class PersonTagCommands {
    private final StorageProvider storage;
    private final TagActivity activity;
    private final DirectoryService directory;
    private final BusinessCommands commands;
    private final DomainContracts contracts;

    public PersonTagCommands(StorageProvider storage, TagActivity activity, DirectoryService directory,
                             BusinessCommands commands, DomainContracts contracts) {
        this.storage = storage;
        this.activity = activity;
        this.directory = directory;
        this.commands = commands;
        this.contracts = contracts;
    }

    public Map<String, Object> execute(Accounts.Actor actor, String tagId,
                                       TagService.AssignmentCommand input, String key) {
        String action = switch (input.operation()) {
            case "ADD" -> "AddPersonTag";
            case "REMOVE" -> "RemovePersonTag";
            case "RESTORE" -> "RestorePersonTag";
            default -> throw new IllegalArgumentException("Unknown tag operation");
        };
        validateBatch(input);
        Runnable authorize = () -> {
            contracts.authorize(actor, action);
            for (String personId : input.personIds()) directory.person(actor, personId);
        };
        return commands.executeDefined(actor, action, key, List.of(tagId, input), authorize, tx -> {
            ObjectRecord definition = required(actor, "TagDefinition", tagId);
            if (definition.version() != input.tagVersion()) throw new BusinessConflict("标签定义已变化，请刷新");
            if (!"ACTIVE".equals(text(definition, "status"))) throw new BusinessConflict("标签已停用");
            if (directory.all(actor, "TagDefinition").stream().anyMatch(t -> tagId.equals(text(t, "parentId")))) {
                throw new BusinessConflict("只有末级标签可以赋给人员");
            }
            String tagVersionId = text(definition, "currentVersionId");
            var tagVersion = required(actor, "TagVersion", tagVersionId);
            if (!tagId.equals(text(tagVersion, "tagDefinitionId"))) throw new BusinessConflict("标签版本归属不一致");
            if (!"PUBLISHED".equals(text(tagVersion, "status"))) throw new BusinessConflict("标签版本尚未发布");
            List<Map<String, Object>> results = new ArrayList<>();
            for (String personId : input.personIds()) {
                ObjectRecord person = required(actor, "Person", personId);
                checkEligibility(actor, person);
                String id = TagService.assignmentId(personId, tagId);
                ObjectRecord previous = storage.getObject(actor.context(), "PersonTagAssignment", id);
                long expected = input.expectedVersions().get(personId);
                if ((previous == null ? 0 : previous.version()) != expected) {
                    throw new BusinessConflict("人员标签已变化，请刷新后重试");
                }
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("expectedVersion", expected);
                parameters.put("note", input.note());
                if (action.equals("AddPersonTag")) parameters.put("personId", personId);
                else parameters.put("assignmentId", id);
                if (!action.equals("RemovePersonTag")) parameters.put("tagVersionId", tagVersionId);
                contracts.validateInputs(actor, action, parameters);

                String before = previous == null ? null : activity.state(actor, previous);
                boolean removing = action.equals("RemovePersonTag");
                String after = removing ? "SUPPRESSED" : "ACTIVE";
                if (previous != null) {
                    if (!personId.equals(text(previous, "personId")) || !tagId.equals(text(previous, "tagDefinitionId"))) {
                        throw new BusinessConflict("人员标签关联不一致，需先核实数据");
                    }
                    preserveLegacyContribution(actor, tx, previous);
                }
                contracts.requireTransition("PersonTagAssignment", "state", before, after, action);
                if (previous != null && before.equals(after) && text(previous, "state").equals(after)) {
                    results.add(Map.of("personId", personId, "assignmentId", id, "version", expected,
                            "state", after, "unchanged", true));
                    continue;
                }
                String now = Instant.now().toString();
                Map<String, Object> update = new HashMap<>();
                update.put("state", after);
                update.put("manualSuppressed", removing);
                update.put("operatorId", actor.username());
                update.put("operatorOrganizationId", actor.organizationId());
                update.put("note", input.note());
                update.put("effectiveTo", removing ? now : null);
                update.put("suppressedAt", removing ? now : null);
                update.put("suppressedBy", removing ? actor.username() : null);
                update.put("suppressionReason", removing ? input.note() : null);
                if (!removing) {
                    update.put("effectiveFrom", now);
                    update.put("tagVersion", tagVersionId);
                    update.put("tagNameSnapshot", text(definition, "name"));
                }
                ObjectRecord saved;
                if (previous == null) {
                    update.put("personId", personId);
                    update.put("tagDefinitionId", tagId);
                    update.put("source", "MANUAL");
                    update.put("sourceOrganizationId", actor.organizationId());
                    saved = tx.createObject("PersonTagAssignment", id, update);
                    tx.createLink("PersonHasTag", "person-tag-" + id, person.key(), saved.key(), Map.of("linkedAt", now));
                    tx.createLink("TagAssignmentUsesDefinition", "definition-" + id, saved.key(), definition.key(), Map.of());
                } else {
                    saved = tx.updateObject(previous.type(), id, update, expected);
                }
                if (!removing) {
                    String contributionId = "manual-" + BusinessCommands.hash(actor.username() + "/" + key + "/" + personId);
                    createContribution(tx, saved, contributionId, "MANUAL", key, tagVersionId, now,
                            actor.username(), actor.organizationId(), input.note(), "ACTIVE");
                }
                results.add(Map.of("personId", personId, "assignmentId", id, "version", saved.version(),
                        "beforeState", before == null ? "ABSENT" : before, "state", after));
            }
            return Map.of("tagId", tagId, "operation", input.operation(), "results", results,
                    "contract", action, "contractDigest", contracts.digest());
        }, contracts.eventType(action));
    }

    public List<ContributionView> contributions(Accounts.Actor actor, String personId, String tagId) {
        directory.person(actor, personId);
        var assignment = storage.getObject(actor.context(), "PersonTagAssignment", TagService.assignmentId(personId, tagId));
        if (assignment == null) return List.of();
        return directory.links(actor, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND)
                .stream().map(l -> required(actor, "TagContribution", l.to().id()))
                .map(c -> new ContributionView(c.id(), text(c, "source"), activity.contributionActive(actor, c) ? "ACTIVE" : "EXPIRED",
                        text(c, "sourceReference"), text(c, "effectiveFrom"), text(c, "effectiveTo"),
                        text(c, "actorId"), text(c, "organizationId"), text(c, "reason"))).toList();
    }

    void preserveLegacyContribution(Accounts.Actor actor, Transaction tx, ObjectRecord assignment) {
        if (!directory.links(actor, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND).isEmpty()) return;
        String versionId = text(assignment, "tagVersion");
        var version = required(actor, "TagVersion", versionId);
        if (!text(assignment, "tagDefinitionId").equals(text(version, "tagDefinitionId"))) {
            throw new BusinessConflict("旧标签版本缺乏可靠引用，需先迁移核实");
        }
        createContribution(tx, assignment, "legacy-" + assignment.id(), text(assignment, "source"),
                "legacy:" + assignment.id(), versionId, text(assignment, "effectiveFrom"),
                text(assignment, "operatorId"), text(assignment, "sourceOrganizationId"),
                "由旧人员标签记录迁入；原始评估依据未补造", "EXPIRED".equals(text(assignment, "state")) ? "EXPIRED" : "ACTIVE");
    }

    private void createContribution(Transaction tx, ObjectRecord assignment, String id, String source,
                                    String reference, String versionId, String startedAt, String actorId,
                                    String organizationId, String reason, String state) {
        var values = new HashMap<String, Object>();
        values.put("source", source);
        values.put("sourceReference", reference);
        values.put("state", state);
        values.put("effectiveFrom", startedAt);
        values.put("actorId", actorId);
        values.put("organizationId", organizationId);
        values.put("reason", reason);
        if (state.equals("EXPIRED")) values.put("effectiveTo", assignment.properties().get("effectiveTo"));
        var contribution = tx.createObject("TagContribution", id, values);
        tx.createLink("AssignmentHasContribution", "assignment-" + id, assignment.key(), contribution.key(), Map.of());
        tx.createLink("ContributionUsesVersion", "version-" + id, contribution.key(), new EntityKey("TagVersion", versionId), Map.of());
        if (!organizationId.isBlank()) {
            tx.createLink("ContributionFromOrganization", "organization-" + id, contribution.key(),
                    new EntityKey("Organization", organizationId), Map.of());
        }
    }

    private void checkEligibility(Accounts.Actor actor, ObjectRecord person) {
        if (!"ACTIVE".equals(text(person, "status"))) throw new BusinessConflict("人员已停用");
        var current = directory.links(actor, person.key(), "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND);
        if (current.size() != 1 || !"ACTIVE".equals(text(required(actor, "Organization", current.getFirst().to().id()), "status"))) {
            throw new BusinessConflict("人员当前单位未唯一确定或已停用");
        }
        for (var link : directory.links(actor, person.key(), "EligibilityForPerson", StorageProvider.Direction.INBOUND)) {
            String state = text(required(actor, "ObjectEligibility", link.from().id()), "state");
            if (!state.equals("ELIGIBLE")) throw new BusinessConflict("人员尚不具备对象资格");
        }
    }

    private static void validateBatch(TagService.AssignmentCommand input) {
        if (input.personIds() == null || input.personIds().isEmpty() || input.personIds().size() > 100
                || input.note() == null || input.note().length() > 1000 || input.tagVersion() < 1
                || input.expectedVersions() == null) throw new IllegalArgumentException("Invalid tag batch");
        Set<String> people = new HashSet<>(input.personIds());
        if (people.size() != input.personIds().size() || people.stream().anyMatch(p -> p == null || p.isBlank())
                || !people.equals(input.expectedVersions().keySet())
                || input.expectedVersions().values().stream().anyMatch(v -> v == null || v < 0)) {
            throw new IllegalArgumentException("Invalid tag batch versions");
        }
    }

    private ObjectRecord required(Accounts.Actor actor, String type, String id) {
        var object = storage.getObject(actor.context(), type, id);
        if (object == null || object.isDeleted()) throw new BusinessConflict("关联对象不存在或已删除");
        return object;
    }

    private static String text(ObjectRecord object, String field) {
        Object value = object.properties().get(field);
        return value == null ? "" : value.toString();
    }

    public record ContributionView(String id, String source, String state, String sourceReference,
                                   String effectiveFrom, String effectiveTo, String actorId,
                                   String organizationId, String reason) {}
}
