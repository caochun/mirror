package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;
import static gov.objectlibrary.server.RuleBatchService.number;

@Service
class RuleConfigurationService {
    private final StorageProvider storage;
    private final TagActivity activity;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final DomainContracts contracts;
    private final BusinessCommands commands;
    private final RuleFacts facts;
    private final RuleInputStamp stamps;
    private final RuleBatchService batches;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RuleConfigurationService(StorageProvider storage, TagActivity activity, DirectoryService directory, Accounts accounts, DomainContracts contracts,
                             BusinessCommands commands, RuleFacts facts, RuleInputStamp stamps, RuleBatchService batches, JdbcTemplate jdbc, Clock clock) {
        this.storage = storage;
        this.activity = activity;
        this.directory = directory;
        this.accounts = accounts;
        this.contracts = contracts;
        this.commands = commands;
        this.facts = facts;
        this.stamps = stamps;
        this.batches = batches;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Map<String, Object> create(Accounts.Actor actor, String tagId, String name, String key) {
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Invalid rule name");
        return commands.executeDefined(actor, "CreateTagRule", key, List.of(tagId, name), () -> contracts.authorize(actor, "CreateTagRule"), tx -> {
            var tag = target(actor, tagId);
            String id = "rule-" + UUID.randomUUID();
            contracts.validateInputs(actor, "CreateTagRule", Map.of("ruleId", id, "tagDefinitionId", tagId, "name", name));
            var rule = tx.createObject("TagRule", id, values("name", name.strip(), "tagDefinitionId", tagId, "ruleType", "DETERMINISTIC",
                    "expression", "{}", "status", "DRAFT", "version", "0"));
            tx.createLink("RuleForTag", "tag-" + id, rule.key(), tag.key(), Map.of());
            return Map.of("id", id, "version", rule.version(), "state", "DRAFT");
        }, contracts.eventType("CreateTagRule"));
    }

    Map<String, Object> preview(Accounts.Actor actor, String ruleId, Map<String, Object> condition, String key) {
        RuleExpression.validate(condition);
        return commands.executeDefined(actor, "PreviewRuleChange", key, List.of(ruleId, condition), () -> contracts.authorize(actor, "PreviewRuleChange"), tx -> {
            var rule = batches.required(actor, "TagRule", ruleId);
            var tag = target(actor, text(rule, "tagDefinitionId"));
            enforceSpecialRules(tag, condition);
            var people = directory.all(actor, "Person").stream().map(ObjectRecord::id).sorted().toList();
            // Publishing changes a global rule; preview the complete impact, never a misleading partial sample.
            if (!actor.role().equals("SUPER_ADMIN")) throw new org.springframework.security.access.AccessDeniedException("全局规则发布需要全市配置权限");
            String versionId = "rule-version-" + UUID.randomUUID();
            String encoded = batches.encode(condition);
            var version = tx.createObject("RuleVersion", versionId, values("ruleId", ruleId, "tagDefinitionId", tag.id(), "version", versionId,
                    "state", "DRAFT", "conditionJson", encoded, "fieldDependenciesJson", batches.encode(RuleExpression.validate(condition).stream().sorted().toList()),
                    "configurationDigest", BusinessCommands.hash(encoded), "createdAt", clock.instant().toString()));
            tx.createLink("RuleHasVersion", "rule-" + versionId, rule.key(), version.key(), Map.of());
            tx.createLink("RuleVersionUsesTagVersion", "tag-" + versionId, version.key(), new EntityKey("TagVersion", text(tag, "currentVersionId")), Map.of());
            String id = "rule-preview-" + UUID.randomUUID();
            var preview = tx.createObject("RuleImpactPreview", id, values("state", "QUEUED", "ruleId", ruleId, "ruleVersionId", versionId,
                    "tagVersionId", text(tag, "currentVersionId"), "createdBy", actor.username(), "inputDigest", "PENDING", "scopeJson", batches.encode(people),
                    "addedCount", 0, "expiredCount", 0, "unchangedCount", 0, "unknownCount", 0, "skippedCount", 0, "suppressedCount", 0,
                    "createdAt", clock.instant().toString(), "evaluatedDate", date(), "resultJson", "[]", "totalCount", people.size(), "processedCount", 0));
            tx.createLink("PreviewForRuleVersion", "version-" + id, preview.key(), version.key(), Map.of());
            contracts.validateInputs(actor, "PreviewRuleChange", Map.of("ruleId", ruleId, "condition", condition, "scope", Map.of("all", true)));
            return Map.of("id", id, "state", "QUEUED");
        }, contracts.eventType("PreviewRuleChange"));
    }

    int processPreviews() {
        int count = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var worker = RuleBatchService.worker(tenant);
            for (var preview : directory.all(worker, "RuleImpactPreview")) {
                if (!Set.of("QUEUED", "RUNNING").contains(text(preview, "state"))) continue;
                if (!text(preview, "leaseUntil").isEmpty() && Instant.parse(text(preview, "leaseUntil")).isAfter(clock.instant())) continue;
                try { computePreview(worker, preview); count++; }
                catch (BusinessConflict competingWorker) { /* Another writer changed the preview or its inputs. */ }
            }
        }
        return count;
    }

    private void computePreview(Accounts.Actor worker, ObjectRecord observed) {
        String lease = UUID.randomUUID().toString();
        commands.executeDefined(worker, "PreviewRuleChange", "claim-preview-" + lease, List.of(observed.id(), observed.version()),
                () -> contracts.authorizeSystem("PreviewRuleChange"), tx -> {
            var preview = batches.required(worker, "RuleImpactPreview", observed.id());
            if (preview.version() != observed.version()) throw new BusinessConflict("预览已被领取");
            tx.updateObject(preview.type(), preview.id(), values("state", "RUNNING", "leaseOwner", lease, "leaseUntil", clock.instant().plusSeconds(120).toString()), preview.version());
            return Map.of("id", preview.id());
        }, contracts.eventType("PreviewRuleChange"));
        var version = batches.required(worker, "RuleVersion", text(observed, "ruleVersionId"));
        var rule = batches.required(worker, "TagRule", text(observed, "ruleId"));
        var condition = batches.condition(text(version, "conditionJson"));
        var dependencies = RuleExpression.validate(condition);
        String before = stamps.read(worker.tenantId(), true);
        String evaluatedDate = date();
        List<PreviewRow> rows = new ArrayList<>();
        int added = 0, expired = 0, unchanged = 0, unknown = 0, skipped = 0, suppressed = 0;
        for (String personId : batches.strings(text(observed, "scopeJson"))) {
            var person = batches.required(worker, "Person", personId);
            var input = facts.load(worker, person, dependencies);
            var result = RuleExpression.evaluate(condition, input);
            var assignment = storage.getObject(worker.context(), "PersonTagAssignment", TagService.assignmentId(personId, text(version, "tagDefinitionId")));
            boolean masked = assignment != null && (Boolean.TRUE.equals(assignment.properties().get("manualSuppressed")) || Set.of("SUPPRESSED", "REMOVED").contains(text(assignment, "state")));
            boolean active = assignment != null && activity.state(worker, assignment).equals("ACTIVE");
            boolean other = assignment != null && batches.activeContributions(worker, assignment).stream().anyMatch(c -> !rule.id().equals(text(c, "ruleId")) && activity.contributionActive(worker, c));
            if (assignment != null && directory.links(worker, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND).isEmpty()
                    && !text(assignment, "state").equals("EXPIRED")) other = true;
            boolean after = !masked && input.eligible() && (result.outcome().equals("MATCH") || other);
            String change = after && !active ? "ADD" : !after && active ? "EXPIRE" : "UNCHANGED";
            if (change.equals("ADD")) added++;
            else if (change.equals("EXPIRE")) expired++;
            else unchanged++;
            if (result.outcome().equals("UNKNOWN")) unknown++;
            if (result.outcome().equals("SKIPPED")) skipped++;
            if (masked) suppressed++;
            rows.add(new PreviewRow(personId, input.name(), input.organizationName(), result.outcome(), change, masked, result.reason(), result.missingFields()));
            if (rows.size() % 100 == 0) renewPreview(worker, observed.id(), lease, rows.size());
        }
        String after = stamps.read(worker.tenantId(), true);
        String digest = BusinessCommands.hash(after + text(version, "configurationDigest") + text(observed, "tagVersionId") + date());
        String state = evaluatedDate.equals(date()) && before.equals(after) && text(batches.required(worker, "TagDefinition", text(version, "tagDefinitionId")), "currentVersionId").equals(text(observed, "tagVersionId")) ? "READY" : "STALE";
        var resultValues = values("state", state, "sourceStamp", after, "inputDigest", digest, "confirmedDigest", digest,
                "addedCount", added, "expiredCount", expired, "unchangedCount", unchanged, "unknownCount", unknown, "skippedCount", skipped,
                "suppressedCount", suppressed, "processedCount", rows.size(), "evaluatedDate", date(), "resultJson", batches.encode(rows), "leaseOwner", null, "leaseUntil", null);
        commands.executeDefined(worker, "PreviewRuleChange", "finish-preview-" + lease, List.of(observed.id(), lease), () -> contracts.authorizeSystem("PreviewRuleChange"), tx -> {
            var current = batches.required(worker, "RuleImpactPreview", observed.id());
            if (!lease.equals(text(current, "leaseOwner"))) throw new BusinessConflict("预览租约已变化");
            tx.updateObject(current.type(), current.id(), resultValues, current.version());
            return Map.of("id", current.id(), "state", state);
        }, contracts.eventType("PreviewRuleChange"));
    }

    private void renewPreview(Accounts.Actor worker, String id, String lease, int processed) {
        commands.executeDefined(worker, "PreviewRuleChange", "renew-preview-" + UUID.randomUUID(), List.of(id, lease), () -> contracts.authorizeSystem("PreviewRuleChange"), tx -> {
            var preview = batches.required(worker, "RuleImpactPreview", id);
            if (!lease.equals(text(preview, "leaseOwner"))) throw new BusinessConflict("预览租约已变化");
            tx.updateObject(preview.type(), id, Map.of("leaseUntil", clock.instant().plusSeconds(120).toString(), "processedCount", processed), preview.version());
            return Map.of("id", id);
        }, contracts.eventType("PreviewRuleChange"));
    }

    Map<String, Object> publish(Accounts.Actor actor, String ruleId, Publish input, String key) {
        return commands.executeDefined(actor, "PublishRuleVersion", key, List.of(ruleId, input), () -> contracts.authorize(actor, "PublishRuleVersion"), tx -> {
            var rule = batches.required(actor, "TagRule", ruleId);
            var preview = batches.required(actor, "RuleImpactPreview", input.previewId());
            var tag = target(actor, text(rule, "tagDefinitionId"));
            if (rule.version() != input.expectedVersion() || !text(preview, "ruleId").equals(ruleId)
                    || !text(preview, "createdBy").equals(actor.username()) || !text(preview, "state").equals("READY")) throw new BusinessConflict("预览或规则已变化，请刷新");
            if (!text(preview, "confirmedDigest").equals(input.confirmedDigest()) || !text(preview, "sourceStamp").equals(stamps.read(actor.tenantId(), true))
                    || !text(preview, "tagVersionId").equals(text(tag, "currentVersionId")) || !text(preview, "evaluatedDate").equals(date())) {
                throw new BusinessConflict("配置、人员资料或标签状态已变化，请重新预览");
            }
            var version = batches.required(actor, "RuleVersion", text(preview, "ruleVersionId"));
            if (!text(version, "state").equals("DRAFT")) throw new BusinessConflict("规则版本已发布");
            contracts.validateInputs(actor, "PublishRuleVersion", Map.of("ruleId", ruleId, "previewId", preview.id(), "expectedVersion", rule.version(), "confirmedDigest", input.confirmedDigest()));
            tx.updateObject(version.type(), version.id(), values("state", "PUBLISHED", "publishedAt", clock.instant().toString()), version.version());
            var changed = tx.updateObject(rule.type(), rule.id(), values("status", "ACTIVE", "currentVersionId", version.id(),
                    "conditionJson", text(version, "conditionJson"), "expression", text(version, "conditionJson"), "fieldDependenciesJson", text(version, "fieldDependenciesJson"),
                    "version", version.id(), "lastScanStamp", ""), rule.version());
            var batch = batches.enqueue(tx, actor, List.of(version), batches.strings(text(preview, "scopeJson")), "PUBLISH:" + version.id());
            return Map.of("id", ruleId, "version", changed.version(), "ruleVersionId", version.id(), "batchId", batch.id());
        }, contracts.eventType("PublishRuleVersion"));
    }

    List<RuleView> list(Accounts.Actor actor) {
        accounts.requirePermission(actor, "PERSON_READ");
        return directory.all(actor, "TagRule").stream().map(rule -> {
            var tag = batches.required(actor, "TagDefinition", text(rule, "tagDefinitionId"));
            var version = storage.getObject(actor.context(), "RuleVersion", text(rule, "currentVersionId"));
            return new RuleView(rule.id(), rule.version(), text(rule, "name"), tag.id(), text(tag, "name"), text(rule, "status"),
                    version == null ? Map.of() : batches.condition(text(version, "conditionJson")), version == null ? "" : version.id());
        }).toList();
    }

    Map<String, Object> previewDetail(Accounts.Actor actor, String id, int page, int size) {
        contracts.authorize(actor, "PreviewRuleChange");
        if (!actor.role().equals("SUPER_ADMIN")) throw new org.springframework.security.access.AccessDeniedException("全库预览只对全市配置管理员开放");
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        var preview = batches.required(actor, "RuleImpactPreview", id);
        if (!text(preview, "createdBy").equals(actor.username())) throw new org.springframework.security.access.AccessDeniedException("预览属于其他操作者");
        List<?> rows;
        try { rows = new com.fasterxml.jackson.databind.ObjectMapper().readValue(text(preview, "resultJson"), List.class); }
        catch (Exception invalid) { throw new BusinessConflict("预览明细不可用"); }
        int start = (int) Math.min((long) page * size, rows.size());
        var rule = batches.required(actor, "TagRule", text(preview, "ruleId"));
        return values("id", id, "state", text(preview, "state"), "ruleId", rule.id(), "ruleVersion", rule.version(), "digest", text(preview, "confirmedDigest"),
                "added", number(preview, "addedCount"), "expired", number(preview, "expiredCount"), "unchanged", number(preview, "unchangedCount"),
                "unknown", number(preview, "unknownCount"), "skipped", number(preview, "skippedCount"), "suppressed", number(preview, "suppressedCount"),
                "processed", number(preview, "processedCount"), "items", rows.subList(start, Math.min(start + size, rows.size())), "total", number(preview, "totalCount"), "page", page, "size", size);
    }

    ObjectRecord target(Accounts.Actor actor, String id) {
        var tag = batches.required(actor, "TagDefinition", id);
        if (!text(tag, "status").equals("ACTIVE") || directory.all(actor, "TagDefinition").stream().anyMatch(child -> text(child, "parentId").equals(id))) {
            throw new BusinessConflict("只能为启用的末级标签配置规则");
        }
        if (text(tag, "assignmentMode").equals("MANUAL_ONLY") || Set.of("NEW_PROMOTION", "RETIREMENT").contains(text(tag, "code"))) {
            throw new BusinessConflict("新提拔和退休过渡期等人工维护标签不支持自动规则");
        }
        return tag;
    }
    private void enforceSpecialRules(ObjectRecord tag, Map<String, Object> condition) {
        if (Set.of("PRINCIPAL", "LEADERSHIP", "MIDDLE", "OTHER").contains(text(tag, "code"))) {
            if (!condition.equals(Map.of("field", "roleLevel", "operator", "EQ", "value", tag.id()))) {
                throw new BusinessConflict("职务层级使用统一最高层级映射，不能用年龄或关键词替代");
            }
        }
    }
    private String date() { return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")).toString(); }
    record Publish(String previewId, long expectedVersion, String confirmedDigest) {}
    record RuleView(String id, long version, String name, String tagId, String tagName, String state, Map<String, Object> condition, String currentVersionId) {}
    record PreviewRow(String personId, String name, String organization, String outcome, String change, boolean suppressed, String reason, List<String> missingFields) {}
}
