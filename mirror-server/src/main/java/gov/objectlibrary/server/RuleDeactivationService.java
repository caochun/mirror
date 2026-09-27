package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;
import static gov.objectlibrary.server.RuleBatchService.number;

/** Revokes a rule immediately, then records the ending of its frozen sources in durable batches. */
@Service
class RuleDeactivationService {
    private static final String ACTION = "DeactivateTagRule";
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final DomainContracts contracts;
    private final BusinessCommands commands;
    private final RuleBatchService batches;
    private final TagActivity activity;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RuleDeactivationService(StorageProvider storage, DirectoryService directory, DomainContracts contracts,
                            BusinessCommands commands, RuleBatchService batches, TagActivity activity,
                            JdbcTemplate jdbc, Clock clock) {
        this.storage = storage;
        this.directory = directory;
        this.contracts = contracts;
        this.commands = commands;
        this.batches = batches;
        this.activity = activity;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Map<String, Object> deactivate(Accounts.Actor actor, String ruleId, Deactivate input, String key) {
        return commands.executeDefined(actor, ACTION, key, List.of(ruleId, input), () -> contracts.authorize(actor, ACTION), tx -> {
            if (!input.confirmation()) throw new IllegalArgumentException("请确认停用规则");
            var rule = batches.required(actor, "TagRule", ruleId);
            if (rule.version() != input.expectedVersion() || !text(rule, "status").equals("ACTIVE")) {
                throw new BusinessConflict("规则已变化或尚未启用，请刷新");
            }
            contracts.validateInputs(actor, ACTION, Map.of("ruleId", ruleId, "expectedVersion", input.expectedVersion(), "confirmation", true));
            return enqueue(tx, actor, rule);
        }, contracts.eventType(ACTION));
    }

    Map<String, Object> enqueue(Transaction tx, Accounts.Actor actor, ObjectRecord rule) {
        contracts.requireTransition("TagRule", "status", "ACTIVE", "INACTIVE", ACTION);
        var sources = directory.all(actor, "TagContribution").stream()
                .filter(c -> "RULE".equals(text(c, "source")) && rule.id().equals(text(c, "ruleId")) && "ACTIVE".equals(text(c, "state")))
                .map(ObjectRecord::id).sorted().toList();
        var issues = directory.all(actor, "TagProcessingIssue").stream()
                .filter(issue -> rule.id().equals(text(issue, "ruleId")) && "OPEN".equals(text(issue, "state")))
                .map(ObjectRecord::id).sorted().toList();
        String batchId = "rule-stop-" + UUID.randomUUID();
        String now = clock.instant().toString();
        var changed = tx.updateObject(rule.type(), rule.id(), values("status", "INACTIVE", "deactivatedAt", now,
                "deactivationBatchId", batchId), rule.version());
        var batch = tx.createObject("TagBatch", batchId, values("kind", "RULE_DEACTIVATE", "state", "QUEUED",
                "ruleId", rule.id(), "contributionIdsJson", batches.encode(sources), "issueIdsJson", batches.encode(issues),
                "cursor", 0, "totalCount", sources.size() + issues.size(),
                "successCount", 0, "failureCount", 0, "unknownCount", 0, "skippedCount", 0,
                "createdBy", actor.username(), "organizationId", actor.organizationId(), "startedAt", now,
                "configurationDigest", BusinessCommands.hash(rule.id() + "/" + rule.version() + "/" + sources + "/" + issues),
                "scopeSnapshotJson", batches.encode(Map.of("contributionIds", sources, "issueIds", issues)), "triggerReference", "DEACTIVATE:" + rule.id()));
        if (!text(rule, "currentVersionId").isEmpty()) {
            tx.createLink("BatchUsesRuleVersion", batchId + "-version", batch.key(),
                    new EntityKey("RuleVersion", text(rule, "currentVersionId")), Map.of());
        }
        return Map.of("id", rule.id(), "version", changed.version(), "state", "INACTIVE", "batchId", batchId, "total", sources.size() + issues.size());
    }

    int processPending() {
        int processed = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var actor = RuleBatchService.worker(tenant);
            for (var batch : directory.all(actor, "TagBatch")) {
                if (!"RULE_DEACTIVATE".equals(text(batch, "kind")) || !Set.of("QUEUED", "RUNNING").contains(text(batch, "state"))) continue;
                if (!text(batch, "leaseUntil").isEmpty() && Instant.parse(text(batch, "leaseUntil")).isAfter(clock.instant())) continue;
                try {
                    processed += run(actor, batch);
                } catch (BusinessConflict concurrentChange) {
                    // The cursor and lease remain durable; the next pass resumes after the competing writer.
                }
            }
        }
        return processed;
    }

    private int run(Accounts.Actor actor, ObjectRecord observed) {
        String lease = UUID.randomUUID().toString();
        commands.executeDefined(actor, "StartTagBatch", "claim-stop-" + lease, List.of(observed.id(), observed.version()),
                () -> contracts.authorizeSystem("StartTagBatch"), tx -> {
            var batch = batches.required(actor, "TagBatch", observed.id());
            if (batch.version() != observed.version()) throw new BusinessConflict("清理批次已被领取");
            contracts.requireTransition("TagBatch", "state", text(batch, "state"), "RUNNING", "StartTagBatch");
            tx.updateObject(batch.type(), batch.id(), values("state", "RUNNING", "leaseOwner", lease,
                    "leaseUntil", clock.instant().plusSeconds(60).toString()), batch.version());
            return Map.of("id", batch.id());
        }, contracts.eventType("StartTagBatch"));
        var current = batches.required(actor, "TagBatch", observed.id());
        var sources = batches.strings(text(current, "contributionIdsJson"));
        var issues = batches.strings(text(current, "issueIdsJson"));
        int cursor = number(current, "cursor");
        int end = Math.min(cursor + 100, sources.size() + issues.size());
        for (int index = cursor; index < end; index++) {
            if (index < sources.size()) clean(actor, current.id(), lease, index, sources.get(index));
            else cleanIssue(actor, current.id(), lease, index, issues.get(index - sources.size()));
        }
        finishPass(actor, current.id(), lease, end == sources.size() + issues.size());
        return end - cursor;
    }

    private void clean(Accounts.Actor actor, String batchId, String lease, int index, String contributionId) {
        commands.executeDefinedWithRetry(actor, ACTION, "stop-cell-" + BusinessCommands.hash(batchId + "/" + index),
                List.of(batchId, index, contributionId), () -> contracts.authorizeSystem(ACTION), tx -> {
            var batch = batches.required(actor, "TagBatch", batchId);
            if (!"RULE_DEACTIVATE".equals(text(batch, "kind")) || !lease.equals(text(batch, "leaseOwner")) || number(batch, "cursor") != index) {
                throw new BusinessConflict("清理批次游标或租约已变化");
            }
            var contribution = storage.getObject(actor.context(), "TagContribution", contributionId);
            String ruleId = text(batch, "ruleId");
            var links = contribution == null ? List.<org.openfoundry.foundation.spi.LinkRecord>of()
                    : directory.links(actor, contribution.key(), "AssignmentHasContribution", StorageProvider.Direction.INBOUND);
            var assignment = links.size() == 1 ? storage.getObject(actor.context(), "PersonTagAssignment", links.getFirst().from().id()) : null;
            boolean valid = contribution != null && !contribution.isDeleted() && ruleId.equals(text(contribution, "ruleId"))
                    && "RULE".equals(text(contribution, "source")) && assignment != null && !assignment.isDeleted();
            String outcome = valid ? "SKIPPED" : "FAILED";
            boolean ended = valid && "ACTIVE".equals(text(contribution, "state"));
            String reason = valid ? "规则已停用，该次停用前的来源贡献结束" : "贡献与逻辑标签关联异常，需核实历史依据";
            String personId = assignment == null ? "" : text(assignment, "personId");
            String now = clock.instant().toString();
            if (valid) {
                if ("ACTIVE".equals(text(contribution, "state"))) {
                    contracts.requireTransition("TagContribution", "state", "ACTIVE", "EXPIRED", ACTION);
                    tx.updateObject(contribution.type(), contribution.id(), values("state", "EXPIRED",
                            "effectiveTo", text(batch, "startedAt"), "reason", reason), contribution.version());
                } else {
                    outcome = "SKIPPED";
                    reason = "该来源已由其他处理结束，保留其原失效依据";
                }
                boolean other = batches.activeContributions(actor, assignment).stream()
                        .anyMatch(c -> !c.id().equals(contributionId) && activity.contributionActive(actor, c));
                boolean suppressed = Boolean.TRUE.equals(assignment.properties().get("manualSuppressed"))
                        || Set.of("SUPPRESSED", "REMOVED").contains(text(assignment, "state"));
                if (!suppressed && !other && "ACTIVE".equals(text(assignment, "state"))) {
                    contracts.requireTransition("PersonTagAssignment", "state", "ACTIVE", "EXPIRED", ACTION);
                    tx.updateObject(assignment.type(), assignment.id(), values("state", "EXPIRED", "effectiveTo", text(batch, "startedAt"),
                            "operatorId", text(batch, "createdBy"), "operatorOrganizationId", text(batch, "organizationId"), "note", reason), assignment.version());
                }
            }
            String evaluationId = "stop-evaluation-" + BusinessCommands.hash(batchId + "/" + index);
            var evaluation = tx.createObject("TagEvaluation", evaluationId, values("personId", personId, "ruleId", ruleId,
                    "batchId", batchId, "state", outcome, "inputDigest", text(batch, "configurationDigest"), "reason", reason,
                    "evaluatedAt", now, "change", "RULE_DEACTIVATE", "evidenceJson", batches.encode(Map.of("contributionId", contributionId))));
            tx.createLink("EvaluationInBatch", evaluationId + "-batch", evaluation.key(), batch.key(), Map.of());
            if (!personId.isEmpty() && storage.getObject(actor.context(), "Person", personId) != null) {
                tx.createLink("EvaluationForPerson", evaluationId + "-person", evaluation.key(), new EntityKey("Person", personId), Map.of());
            }
            String counter = !valid ? "failureCount" : ended ? "successCount" : "skippedCount";
            tx.updateObject(batch.type(), batch.id(), values("cursor", index + 1, counter, number(batch, counter) + 1,
                    "leaseUntil", clock.instant().plusSeconds(60).toString()), batch.version());
            return Map.of("batchId", batchId, "contributionId", contributionId, "outcome", outcome, "evaluationId", evaluationId);
        }, contracts.eventType(ACTION));
    }

    private void cleanIssue(Accounts.Actor actor, String batchId, String lease, int index, String issueId) {
        commands.executeDefinedWithRetry(actor, ACTION, "stop-cell-" + BusinessCommands.hash(batchId + "/" + index),
                List.of(batchId, index, issueId), () -> contracts.authorizeSystem(ACTION), tx -> {
            var batch = batches.required(actor, "TagBatch", batchId);
            if (!"RULE_DEACTIVATE".equals(text(batch, "kind")) || !lease.equals(text(batch, "leaseOwner")) || number(batch, "cursor") != index) {
                throw new BusinessConflict("清理批次游标或租约已变化");
            }
            var issue = storage.getObject(actor.context(), "TagProcessingIssue", issueId);
            boolean valid = issue != null && !issue.isDeleted() && text(batch, "ruleId").equals(text(issue, "ruleId"));
            boolean ended = valid && "OPEN".equals(text(issue, "state"));
            if (ended) {
                contracts.requireTransition("TagProcessingIssue", "state", "OPEN", "RESOLVED", ACTION);
                tx.updateObject(issue.type(), issue.id(), values("state", "RESOLVED", "resolvedAt", text(batch, "startedAt"),
                        "resolution", "对应规则已停用；关闭待处理项，不表示原始资料已补齐"), issue.version());
            }
            String id = "stop-evaluation-" + BusinessCommands.hash(batchId + "/" + index);
            var evaluation = tx.createObject("TagEvaluation", id, values("personId", valid ? text(issue, "personId") : "",
                    "ruleId", text(batch, "ruleId"), "batchId", batchId, "state", valid ? "SKIPPED" : "FAILED",
                    "inputDigest", text(batch, "configurationDigest"), "evaluatedAt", clock.instant().toString(), "change", "RULE_DEACTIVATE",
                    "reason", valid ? "规则停用，原待处理项关闭或已结束，原问题历史保留" : "待处理项关联异常，需核实历史依据",
                    "evidenceJson", batches.encode(Map.of("issueId", issueId))));
            tx.createLink("EvaluationInBatch", id + "-batch", evaluation.key(), batch.key(), Map.of());
            String counter = !valid ? "failureCount" : ended ? "successCount" : "skippedCount";
            tx.updateObject(batch.type(), batch.id(), values("cursor", index + 1, counter, number(batch, counter) + 1,
                    "leaseUntil", clock.instant().plusSeconds(60).toString()), batch.version());
            return Map.of("batchId", batchId, "issueId", issueId, "ended", ended);
        }, contracts.eventType(ACTION));
    }

    private void finishPass(Accounts.Actor actor, String batchId, String lease, boolean complete) {
        String action = complete ? "CompleteTagBatch" : "StartTagBatch";
        commands.executeDefined(actor, action, "finish-stop-" + lease, List.of(batchId, lease, complete),
                () -> contracts.authorizeSystem(action), tx -> {
            var batch = batches.required(actor, "TagBatch", batchId);
            if (!lease.equals(text(batch, "leaseOwner"))) throw new BusinessConflict("清理批次租约已变化");
            var updates = values("leaseOwner", null, "leaseUntil", null);
            if (complete) {
                if (number(batch, "cursor") != number(batch, "totalCount")) throw new BusinessConflict("清理尚未完成");
                String state = number(batch, "failureCount") == 0 ? "SUCCEEDED" : number(batch, "successCount") > 0 ? "PARTIAL_FAILED" : "FAILED";
                contracts.requireTransition("TagBatch", "state", "RUNNING", state, action);
                updates.put("state", state);
                updates.put("finishedAt", clock.instant().toString());
            }
            tx.updateObject(batch.type(), batch.id(), updates, batch.version());
            return Map.of("id", batch.id(), "complete", complete);
        }, contracts.eventType(action));
    }

    record Deactivate(long expectedVersion, boolean confirmation) {}
}
