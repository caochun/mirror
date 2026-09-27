package gov.objectlibrary.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;

/** Persistent batches apply each person/rule result atomically with contributions, issues, history, and progress. */
@Service
class RuleBatchService {
    private final StorageProvider storage;
    private final TagActivity activity;
    private final DirectoryService directory;
    private final DomainContracts contracts;
    private final BusinessCommands commands;
    private final PersonTagCommands manualTags;
    private final RuleFacts facts;
    private final RuleInputStamp stamps;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    RuleBatchService(StorageProvider storage, TagActivity activity, DirectoryService directory, DomainContracts contracts, BusinessCommands commands,
                     PersonTagCommands manualTags, RuleFacts facts, RuleInputStamp stamps, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.storage = storage;
        this.activity = activity;
        this.directory = directory;
        this.contracts = contracts;
        this.commands = commands;
        this.manualTags = manualTags;
        this.facts = facts;
        this.stamps = stamps;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    ObjectRecord enqueue(Transaction tx, Accounts.Actor actor, List<ObjectRecord> versions, List<String> people, String trigger) {
        Map<String, String> tagVersions = new HashMap<>();
        for (var version : versions) {
            var tag = required(actor, "TagDefinition", text(version, "tagDefinitionId"));
            tagVersions.put(version.id(), text(tag, "currentVersionId"));
        }
        String id = "tag-batch-" + UUID.randomUUID();
        var batch = tx.createObject("TagBatch", id, values("kind", "RULE", "state", "QUEUED", "startedAt", clock.instant().toString(),
                "successCount", 0, "failureCount", 0, "unknownCount", 0, "skippedCount", 0, "cursor", 0,
                "totalCount", people.size() * versions.size(), "personIdsJson", encode(people), "ruleVersionIdsJson", encode(versions.stream().map(ObjectRecord::id).toList()),
                "tagVersionsJson", encode(tagVersions), "scopeSnapshotJson", encode(Map.of("personIds", people)),
                "configurationDigest", BusinessCommands.hash(encode(tagVersions) + versions.stream().map(v -> text(v, "configurationDigest")).toList()),
                "createdBy", actor.username(), "organizationId", actor.organizationId(), "triggerReference", trigger));
        for (var version : versions) tx.createLink("BatchUsesRuleVersion", id + "-" + version.id(), batch.key(), version.key(), Map.of());
        return batch;
    }

    Map<String, Object> start(Accounts.Actor actor, List<String> ruleIds, List<String> personIds, String key) {
        if (ruleIds == null || ruleIds.isEmpty() || ruleIds.size() > 100 || personIds == null || personIds.size() > 50000) throw new IllegalArgumentException("Invalid rule batch");
        return commands.executeDefined(actor, "StartTagBatch", key, List.of(ruleIds, personIds), () -> contracts.authorize(actor, "StartTagBatch"), tx -> {
            var versions = ruleIds.stream().distinct().map(id -> required(actor, "TagRule", id)).map(rule -> {
                if (!text(rule, "status").equals("ACTIVE")) throw new BusinessConflict("规则尚未发布");
                return required(actor, "RuleVersion", text(rule, "currentVersionId"));
            }).toList();
            var selected = personIds.isEmpty() ? directory.all(actor, "Person").stream().map(ObjectRecord::id).sorted().toList() : personIds.stream().distinct().sorted().toList();
            for (String id : selected) directory.person(actor, id);
            contracts.validateInputs(actor, "StartTagBatch", Map.of("kind", "RULE", "scope", Map.of("personIds", selected),
                    "ruleVersionIds", versions.stream().map(ObjectRecord::id).toList(), "aiPolicyIds", List.of(), "triggerReference", key));
            var batch = enqueue(tx, actor, versions, selected, "MANUAL:" + key);
            return Map.of("id", batch.id(), "state", "QUEUED", "total", selected.size() * versions.size());
        }, contracts.eventType("StartTagBatch"));
    }

    int processPending() {
        int total = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var actor = worker(tenant);
            for (var batch : directory.all(actor, "TagBatch")) {
                if (!text(batch, "kind").equals("RULE") || !Set.of("QUEUED", "RUNNING").contains(text(batch, "state"))) continue;
                if (!text(batch, "leaseUntil").isEmpty() && Instant.parse(text(batch, "leaseUntil")).isAfter(clock.instant())) continue;
                try { total += run(actor, batch); }
                catch (BusinessConflict competingWorker) { /* Resume the persisted cursor after another writer completes. */ }
            }
        }
        return total;
    }

    private int run(Accounts.Actor actor, ObjectRecord observed) {
        String lease = UUID.randomUUID().toString();
        commands.executeDefined(actor, "StartTagBatch", "claim-rule-" + lease, List.of(observed.id(), observed.version()),
                () -> contracts.authorizeSystem("StartTagBatch"), tx -> {
            var batch = required(actor, "TagBatch", observed.id());
            if (batch.version() != observed.version()) throw new BusinessConflict("赋标批次已被领取");
            contracts.requireTransition(batch.type(), "state", text(batch, "state"), "RUNNING", "StartTagBatch");
            tx.updateObject(batch.type(), batch.id(), values("state", "RUNNING", "leaseOwner", lease, "leaseUntil", clock.instant().plusSeconds(60).toString()), batch.version());
            return Map.of("id", batch.id());
        }, contracts.eventType("StartTagBatch"));
        var batch = required(actor, "TagBatch", observed.id());
        var people = strings(text(batch, "personIdsJson"));
        var versions = strings(text(batch, "ruleVersionIdsJson"));
        var targets = stringMap(text(batch, "tagVersionsJson"));
        int cursor = number(batch, "cursor");
        int end = Math.min(number(batch, "totalCount"), cursor + 100);
        for (int index = cursor; index < end; index++) {
            String person = people.get(index / versions.size());
            String version = versions.get(index % versions.size());
            apply(actor, batch.id(), lease, index, person, version, targets.get(version));
        }
        var latest = required(actor, "TagBatch", batch.id());
        if (number(latest, "cursor") == number(latest, "totalCount")) complete(actor, latest, lease);
        else commands.executeDefined(actor, "StartTagBatch", "yield-rule-" + lease, List.of(batch.id(), lease),
                () -> contracts.authorizeSystem("StartTagBatch"), tx -> {
            var current = required(actor, "TagBatch", batch.id());
            if (!lease.equals(text(current, "leaseOwner"))) throw new BusinessConflict("赋标批次租约已变化");
            tx.updateObject(current.type(), current.id(), values("leaseOwner", null, "leaseUntil", null), current.version());
            return Map.of("id", current.id());
        }, contracts.eventType("StartTagBatch"));
        return end - cursor;
    }

    private void apply(Accounts.Actor actor, String batchId, String lease, int index, String personId, String versionId, String tagVersionId) {
        String cellKey = "rule-cell-" + BusinessCommands.hash(batchId + "/" + index);
        commands.executeDefinedWithRetry(actor, "ApplyRuleEvaluation", cellKey, List.of(batchId, index, personId, versionId, tagVersionId),
                () -> contracts.authorizeSystem("ApplyRuleEvaluation"), tx -> {
            var batch = required(actor, "TagBatch", batchId);
            if (!lease.equals(text(batch, "leaseOwner")) || number(batch, "cursor") != index) throw new BusinessConflict("赋标游标已变化");
            var version = required(actor, "RuleVersion", versionId);
            var rule = required(actor, "TagRule", text(version, "ruleId"));
            var tag = required(actor, "TagDefinition", text(version, "tagDefinitionId"));
            var person = required(actor, "Person", personId);
            var condition = condition(text(version, "conditionJson"));
            var input = facts.load(actor, person, RuleExpression.validate(condition));
            boolean stale = !text(rule, "status").equals("ACTIVE") || !text(rule, "currentVersionId").equals(versionId) || !text(tag, "currentVersionId").equals(tagVersionId);
            var result = stale ? new RuleExpression.Result("SKIPPED", "规则已停用或配置已有新版本，旧批次不再应用", List.of()) : RuleExpression.evaluate(condition, input);
            if (!text(tag, "status").equals("ACTIVE")) result = new RuleExpression.Result("SKIPPED", "标签目录已停用", List.of());
            contracts.validateInputs(actor, "ApplyRuleEvaluation", Map.of("personId", personId, "ruleVersionId", versionId, "batchId", batchId,
                    "outcome", result.outcome(), "inputDigest", input.digest(), "evidence", Map.of("references", input.references(), "reason", result.reason()),
                    "effectiveAt", clock.instant().toString()));
            String evaluationId = "evaluation-" + BusinessCommands.hash(batchId + "/" + index);
            var evaluation = tx.createObject("TagEvaluation", evaluationId, values("personId", personId, "ruleId", rule.id(), "ruleVersionId", versionId,
                    "tagVersionId", tagVersionId, "batchId", batchId, "state", result.outcome(), "inputDigest", input.digest(),
                    "reason", result.reason(), "evidenceJson", encode(Map.of("references", input.references(), "fields", input.fields(), "mappings", input.mappings())), "evaluatedAt", clock.instant().toString()));
            tx.createLink("EvaluationForPerson", "person-" + evaluationId, evaluation.key(), person.key(), Map.of());
            tx.createLink("EvaluationForTagVersion", "tag-" + evaluationId, evaluation.key(), new EntityKey("TagVersion", tagVersionId), Map.of());
            tx.createLink("EvaluationUsesRuleVersion", "rule-" + evaluationId, evaluation.key(), version.key(), Map.of());
            tx.createLink("EvaluationInBatch", "batch-" + evaluationId, evaluation.key(), batch.key(), Map.of());
            for (var mapping : input.mappings()) {
                tx.createLink("EvaluationUsesMapping", evaluationId + "-mapping-" + mapping.id(), evaluation.key(),
                        new EntityKey("ClassificationMapping", mapping.id()), Map.of("mappingVersion", Long.toString(mapping.version()),
                                "mappingSnapshotJson", encode(mapping)));
            }
            String change = "SKIPPED";
            if (!stale) {
                change = reconcile(actor, tx, person, rule, version, tag, tagVersionId, evaluation, input, result);
                updateIssue(actor, tx, rule, person, tagVersionId, batch, evaluation, result);
                String cursorId = cursorId(rule.id(), personId);
                var cursor = storage.getObject(actor.context(), "RuleProcessingCursor", cursorId);
                var values = values("personId", personId, "ruleId", rule.id(), "ruleVersionId", versionId, "tagVersionId", tagVersionId,
                        "inputDigest", input.digest(), "evaluationId", evaluationId, "evaluatedAt", clock.instant().toString());
                if (cursor == null) tx.createObject("RuleProcessingCursor", cursorId, values);
                else tx.updateObject(cursor.type(), cursorId, values, cursor.version());
            }
            String count = switch (result.outcome()) { case "UNKNOWN" -> "unknownCount"; case "SKIPPED" -> "skippedCount"; case "FAILED" -> "failureCount"; default -> "successCount"; };
            tx.updateObject(batch.type(), batch.id(), values("cursor", index + 1, count, number(batch, count) + 1,
                    "leaseUntil", clock.instant().plusSeconds(60).toString()), batch.version());
            return Map.of("batchId", batchId, "personId", personId, "outcome", result.outcome(), "change", change, "evaluationId", evaluationId);
        }, contracts.eventType("ApplyRuleEvaluation"));
    }

    private String reconcile(Accounts.Actor actor, Transaction tx, ObjectRecord person, ObjectRecord rule, ObjectRecord version, ObjectRecord tag,
                             String tagVersionId, ObjectRecord evaluation, RuleFacts.Input input, RuleExpression.Result result) {
        String id = TagService.assignmentId(person.id(), tag.id());
        var assignment = storage.getObject(actor.context(), "PersonTagAssignment", id);
        List<ObjectRecord> contributions = assignment == null ? List.of() : activeContributions(actor, assignment);
        boolean legacy = assignment != null && directory.links(actor, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND).isEmpty() && !text(assignment, "source").isEmpty();
        if (legacy) manualTags.preserveLegacyContribution(actor, tx, assignment);
        boolean matched = result.outcome().equals("MATCH") && input.eligible() && text(tag, "status").equals("ACTIVE");
        boolean keep = false;
        boolean other = legacy && !text(assignment, "state").equals("EXPIRED");
        for (var contribution : contributions) {
            if (!rule.id().equals(text(contribution, "ruleId"))) {
                other |= activity.contributionActive(actor, contribution);
                continue;
            }
            if (matched && text(contribution, "ruleVersionId").equals(version.id()) && text(contribution, "sourceReference").equals(tagVersionId) && text(contribution, "inputDigest").equals(input.digest())) keep = true;
            else tx.updateObject(contribution.type(), contribution.id(), values("state", "EXPIRED", "effectiveTo", clock.instant().toString(),
                    "reason", result.outcome().equals("UNKNOWN") ? "本规则缺少可靠输入，暂停本来源" : "规则不再命中或版本已替换"), contribution.version());
        }
        if (assignment == null && !matched) return "NO_ASSIGNMENT";
        boolean suppressed = assignment != null && (Boolean.TRUE.equals(assignment.properties().get("manualSuppressed")) || Set.of("SUPPRESSED", "REMOVED").contains(text(assignment, "state")));
        String state = suppressed ? "SUPPRESSED" : input.eligible() && text(tag, "status").equals("ACTIVE") && (matched || other) ? "ACTIVE" : "EXPIRED";
        String before = assignment == null ? "ABSENT" : text(assignment, "state");
        if (assignment == null) {
            assignment = tx.createObject("PersonTagAssignment", id, values("personId", person.id(), "tagDefinitionId", tag.id(), "state", state,
                    "source", "RULE", "sourceReference", rule.id(), "sourceOrganizationId", input.organizationId(), "manualSuppressed", false,
                    "tagVersion", tagVersionId, "tagNameSnapshot", text(tag, "name"), "effectiveFrom", clock.instant().toString(),
                    "operatorId", actor.username(), "operatorOrganizationId", ""));
            tx.createLink("PersonHasTag", "person-" + id, person.key(), assignment.key(), Map.of());
            tx.createLink("TagAssignmentUsesDefinition", "tag-" + id, assignment.key(), tag.key(), Map.of());
        } else if (!before.equals(state) || matched && !text(assignment, "tagVersion").equals(tagVersionId)) {
            contracts.requireTransition(assignment.type(), "state", before.equals("REMOVED") ? "SUPPRESSED" : before, state, "ApplyRuleEvaluation");
            var updates = values("state", state, "operatorId", actor.username(), "operatorOrganizationId", "");
            if (!state.equals("SUPPRESSED")) updates.put("effectiveTo", state.equals("EXPIRED") ? clock.instant().toString() : null);
            if (matched) { updates.put("tagVersion", tagVersionId); updates.put("tagNameSnapshot", text(tag, "name")); }
            assignment = tx.updateObject(assignment.type(), assignment.id(), updates, assignment.version());
        }
        if (matched && !keep) {
            String contributionId = "rule-source-" + evaluation.id();
            var contribution = tx.createObject("TagContribution", contributionId, values("ruleId", rule.id(), "ruleVersionId", version.id(),
                    "source", "RULE", "sourceReference", tagVersionId, "inputDigest", input.digest(), "state", "ACTIVE", "effectiveFrom", clock.instant().toString(),
                    "actorId", actor.username(), "organizationId", input.organizationId(), "reason", result.reason()));
            tx.createLink("AssignmentHasContribution", "assignment-" + contributionId, assignment.key(), contribution.key(), Map.of());
            tx.createLink("ContributionUsesVersion", "tag-" + contributionId, contribution.key(), new EntityKey("TagVersion", tagVersionId), Map.of());
            tx.createLink("ContributionFromEvaluation", "evaluation-" + contributionId, contribution.key(), evaluation.key(), Map.of());
            tx.createLink("ContributionInBatch", "batch-" + contributionId, contribution.key(), new EntityKey("TagBatch", text(evaluation, "batchId")), Map.of());
            if (!input.organizationId().isEmpty()) tx.createLink("ContributionFromOrganization", "org-" + contributionId, contribution.key(), new EntityKey("Organization", input.organizationId()), Map.of());
        }
        return before.equals(state) ? "UNCHANGED" : before + "→" + state;
    }

    private void updateIssue(Accounts.Actor actor, Transaction tx, ObjectRecord rule, ObjectRecord person, String tagVersionId,
                             ObjectRecord batch, ObjectRecord evaluation, RuleExpression.Result result) {
        for (var link : directory.links(actor, person.key(), "TagIssueForPerson", StorageProvider.Direction.INBOUND)) {
            var issue = required(actor, "TagProcessingIssue", link.from().id());
            if (text(issue, "ruleId").equals(rule.id()) && text(issue, "state").equals("OPEN")) {
                tx.updateObject(issue.type(), issue.id(), values("state", "RESOLVED", "resolvedAt", clock.instant().toString(),
                        "resolution", result.outcome().equals("UNKNOWN") ? "已由新评估替代" : "规则已重新评估"), issue.version());
            }
        }
        if (!result.outcome().equals("UNKNOWN")) return;
        String id = "issue-" + evaluation.id();
        var issue = tx.createObject("TagProcessingIssue", id, values("ruleId", rule.id(), "personId", person.id(), "tagVersionId", tagVersionId,
                "batchId", batch.id(), "ruleVersionId", text(evaluation, "ruleVersionId"), "category", "RULE_UNCOMPUTABLE", "state", "OPEN", "field", String.join(",", result.missingFields()),
                "reason", result.reason(), "detectedAt", clock.instant().toString(), "suggestedResolution", "在权威来源补齐字段或由配置管理员修正映射后局部重算"));
        tx.createLink("TagIssueForPerson", "person-" + id, issue.key(), person.key(), Map.of());
        tx.createLink("TagIssueForTagVersion", "tag-" + id, issue.key(), new EntityKey("TagVersion", tagVersionId), Map.of());
        tx.createLink("TagIssueForEvaluation", "evaluation-" + id, issue.key(), evaluation.key(), Map.of());
        tx.createLink("TagIssueInBatch", "batch-" + id, issue.key(), batch.key(), Map.of());
    }

    private void complete(Accounts.Actor actor, ObjectRecord observed, String lease) {
        commands.executeDefined(actor, "CompleteTagBatch", "complete-" + BusinessCommands.hash(observed.id()), List.of(observed.id()),
                () -> contracts.authorizeSystem("CompleteTagBatch"), tx -> {
            var batch = required(actor, "TagBatch", observed.id());
            if (!lease.equals(text(batch, "leaseOwner")) || number(batch, "cursor") != number(batch, "totalCount")) throw new BusinessConflict("批次尚未处理完成");
            contracts.validateInputs(actor, "CompleteTagBatch", Map.of("batchId", batch.id(), "expectedVersion", batch.version()));
            String state = number(batch, "failureCount") > 0 || number(batch, "unknownCount") > 0 ? number(batch, "successCount") == 0 ? "FAILED" : "PARTIAL_FAILED" : "SUCCEEDED";
            contracts.requireTransition(batch.type(), "state", "RUNNING", state, "CompleteTagBatch");
            tx.updateObject(batch.type(), batch.id(), values("state", state, "finishedAt", clock.instant().toString(), "leaseOwner", null, "leaseUntil", null), batch.version());
            return Map.of("id", batch.id(), "state", state, "success", number(batch, "successCount"), "unknown", number(batch, "unknownCount"), "failed", number(batch, "failureCount"), "skipped", number(batch, "skippedCount"));
        }, contracts.eventType("CompleteTagBatch"));
    }

    int scheduleChangedInputs() {
        int created = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var actor = worker(tenant);
            String stamp = stamps.read(tenant, false);
            var activeBatches = directory.all(actor, "TagBatch").stream().filter(batch -> text(batch, "kind").equals("RULE")
                    && Set.of("QUEUED", "RUNNING").contains(text(batch, "state"))).toList();
            var cursors = directory.all(actor, "RuleProcessingCursor").stream().collect(java.util.stream.Collectors.toMap(ObjectRecord::id, cursor -> cursor));
            for (var rule : directory.all(actor, "TagRule")) {
                if (!text(rule, "status").equals("ACTIVE")) continue;
                String versionId = text(rule, "currentVersionId");
                if (activeBatches.stream().anyMatch(batch -> strings(text(batch, "ruleVersionIdsJson")).contains(versionId))) continue;
                var version = required(actor, "RuleVersion", versionId);
                var tag = required(actor, "TagDefinition", text(rule, "tagDefinitionId"));
                var fields = RuleExpression.validate(condition(text(version, "conditionJson")));
                String date = fields.contains("ageYears") || fields.contains("serviceMonths")
                        ? java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneId.of("Asia/Shanghai")).toString() : "";
                if (fields.stream().anyMatch(Set.of("positionCode", "roleLevel", "positionDomain")::contains)) {
                    date += "/" + clock.instant().getEpochSecond() / 60;
                }
                String signature = BusinessCommands.hash(stamp + versionId + text(tag, "currentVersionId") + date);
                if (signature.equals(text(rule, "lastScanStamp"))) continue;
                List<String> people = new ArrayList<>();
                for (var person : directory.all(actor, "Person")) {
                    var input = facts.load(actor, person, fields);
                    var cursor = cursors.get(cursorId(rule.id(), person.id()));
                    if (cursor == null || !input.digest().equals(text(cursor, "inputDigest")) || !versionId.equals(text(cursor, "ruleVersionId"))
                            || !text(tag, "currentVersionId").equals(text(cursor, "tagVersionId"))) people.add(person.id());
                }
                try {
                    commands.executeDefined(actor, "StartTagBatch", "scan-rule-" + java.util.UUID.randomUUID(), List.of(rule.id(), signature),
                            () -> contracts.authorizeSystem("StartTagBatch"), tx -> {
                        var current = required(actor, "TagRule", rule.id());
                        if (!text(current, "status").equals("ACTIVE") || !versionId.equals(text(current, "currentVersionId"))) throw new BusinessConflict("规则已更新");
                        tx.updateObject(current.type(), current.id(), Map.of("lastScanStamp", signature), current.version());
                        if (!people.isEmpty()) {
                            var batch = enqueue(tx, actor, List.of(version), people.stream().sorted().toList(), "INPUT_CHANGE:" + signature);
                            return Map.of("id", batch.id(), "total", people.size());
                        }
                        return Map.of("unchanged", true);
                    }, contracts.eventType("StartTagBatch"));
                    if (!people.isEmpty()) created++;
                } catch (BusinessConflict concurrentChange) {
                    // A configuration publish changed the rule while the scan was running.
                }
            }
        }
        return created;
    }

    List<Map<String, Object>> list(Accounts.Actor actor) {
        contracts.authorize(actor, "StartTagBatch");
        return directory.all(actor, "TagBatch").stream().filter(batch -> Set.of("RULE", "RULE_DEACTIVATE").contains(text(batch, "kind")))
                .sorted(java.util.Comparator.comparing(ObjectRecord::createdAt).reversed()).map(batch -> values("id", batch.id(), "state", text(batch, "state"),
                        "total", number(batch, "totalCount"), "processed", number(batch, "cursor"), "success", number(batch, "successCount"),
                        "unknown", number(batch, "unknownCount"), "failed", number(batch, "failureCount"), "skipped", number(batch, "skippedCount"), "startedAt", text(batch, "startedAt"),
                        "finishedAt", text(batch, "finishedAt"), "trigger", text(batch, "triggerReference"), "kind", text(batch, "kind"))).toList();
    }

    DirectoryService.Page<Map<String, Object>> results(Accounts.Actor actor, String batchId, int page, int size) {
        contracts.authorize(actor, "StartTagBatch");
        required(actor, "TagBatch", batchId);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        var evaluations = directory.all(actor, "TagEvaluation").stream().filter(evaluation -> batchId.equals(text(evaluation, "batchId")))
                .sorted(java.util.Comparator.comparing(ObjectRecord::id)).toList();
        int start = (int) Math.min((long) page * size, evaluations.size());
        var rows = evaluations.subList(start, Math.min(start + size, evaluations.size())).stream().map(evaluation -> {
            String personId = text(evaluation, "personId");
            var sourcePerson = storage.getObject(actor.context(), "Person", personId);
            var person = sourcePerson == null || sourcePerson.isDeleted() ? null : directory.person(actor, personId).person();
            var rule = required(actor, "TagRule", text(evaluation, "ruleId"));
            return values("id", evaluation.id(), "personId", personId, "name", person == null ? "关联待核实" : person.name(), "organization", person == null ? "" : person.organizationName(),
                    "rule", text(rule, "name"), "outcome", text(evaluation, "state"), "reason", text(evaluation, "reason"), "evaluatedAt", text(evaluation, "evaluatedAt"));
        }).toList();
        return new DirectoryService.Page<>(rows, evaluations.size(), page, size);
    }

    List<ObjectRecord> activeContributions(Accounts.Actor actor, ObjectRecord assignment) {
        return directory.links(actor, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND).stream()
                .map(link -> required(actor, "TagContribution", link.to().id())).filter(record -> text(record, "state").equals("ACTIVE")).toList();
    }
    ObjectRecord required(Accounts.Actor actor, String type, String id) {
        var record = storage.getObject(actor.context(), type, id);
        if (record == null || record.isDeleted()) throw new BusinessConflict("规则相关记录不存在");
        return record;
    }
    static Accounts.Actor worker(String tenant) { return new Accounts.Actor("__rule_worker__", "规则重算", tenant, "", "SYSTEM"); }
    static String cursorId(String rule, String person) { return "rule-cursor-" + BusinessCommands.hash(rule + "/" + person); }
    static int number(ObjectRecord record, String name) { return ((Number) record.properties().getOrDefault(name, 0)).intValue(); }
    String encode(Object value) {
        try { return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException(failure); }
    }
    List<String> strings(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception invalid) { throw new BusinessConflict("批次范围数据不完整"); }
    }
    Map<String, String> stringMap(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, String>>() {}); }
        catch (Exception invalid) { throw new BusinessConflict("批次版本数据不完整"); }
    }
    Map<String, Object> condition(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception invalid) { throw new IllegalArgumentException("规则条件不完整"); }
    }
}
