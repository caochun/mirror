package gov.objectlibrary.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;
import static gov.objectlibrary.server.RuleBatchService.number;

@Service
class MappingService {
    private static final String PREVIEW = "PreviewClassificationMapping";
    private static final String PUBLISH = "PublishClassificationMapping";
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final DomainContracts contracts;
    private final BusinessCommands commands;
    private final ClassificationMappings mappings;
    private final RuleFacts facts;
    private final RuleBatchService batches;
    private final RuleInputStamp stamps;
    private final TagActivity activity;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    MappingService(StorageProvider storage, DirectoryService directory, Accounts accounts, DomainContracts contracts,
                   BusinessCommands commands, ClassificationMappings mappings, RuleFacts facts, RuleBatchService batches,
                   RuleInputStamp stamps, TagActivity activity, ObjectMapper json, JdbcTemplate jdbc, Clock clock) {
        this.storage = storage;
        this.directory = directory;
        this.accounts = accounts;
        this.contracts = contracts;
        this.commands = commands;
        this.mappings = mappings;
        this.facts = facts;
        this.batches = batches;
        this.stamps = stamps;
        this.activity = activity;
        this.json = json;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<Map<String, Object>> list(Accounts.Actor actor) {
        accounts.requirePermission(actor, "PERSON_READ");
        var scope = directory.scope(actor);
        var catalog = mappings.load(actor);
        return catalog.entries().stream().filter(entry -> actor.role().equals("SUPER_ADMIN")
                || scope.stream().anyMatch(org -> catalog.applies(entry, org))).map(entry -> {
            var tag = storage.getObject(actor.context(), "TagDefinition", entry.targetTagId());
            return values("id", entry.id(), "version", entry.version(), "kind", entry.kind(), "sourceCode", entry.sourceCode(),
                    "tagId", entry.targetTagId(), "tagName", tag == null ? "关联待核实" : text(tag, "name"),
                    "organizationIds", entry.organizationIds(), "priority", entry.priority(), "inherit", entry.inherit(), "enabled", entry.enabled());
        }).toList();
    }

    List<Map<String, Object>> history(Accounts.Actor actor, String id) {
        authorize(actor, PREVIEW);
        batches.required(actor, "ClassificationMapping", id);
        return storage.getEntityHistory(actor.context(), new EntityKey("ClassificationMapping", id)).stream()
                .map(event -> values("version", event.version(), "recordedAt", event.recordedAt().toString(),
                        "kind", event.state().get("kind"), "sourceCode", event.state().get("sourceCode"),
                        "state", event.state().get("state"), "targetName", event.state().getOrDefault("targetTagNameSnapshot", "旧记录未保存名称快照"),
                        "organizationNames", event.state().getOrDefault("organizationNamesJson", "[]"),
                        "priority", event.state().get("priority"), "inherit", event.state().get("inherited"))).toList();
    }

    Map<String, Object> preview(Accounts.Actor actor, Proposal proposed, String key) {
        return commands.executeDefined(actor, PREVIEW, key, proposed, () -> authorize(actor, PREVIEW), tx -> {
            Proposal input = normalize(actor, proposed);
            contracts.validateInputs(actor, PREVIEW, parameters(input));
            String sourceStamp = stamps.read(actor.tenantId(), true);
            var catalog = mappings.load(actor);
            var replacement = entry(actor, input);
            var previous = catalog.entries().stream().filter(candidate -> candidate.id().equals(input.mappingId())).findFirst().orElse(null);
            var people = new ArrayList<String>();
            for (var person : directory.all(actor, "Person")) {
                var orgs = directory.links(actor, person.key(), "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND);
                String org = orgs.size() == 1 ? orgs.getFirst().to().id() : "";
                boolean global = !input.kind().equals("ORGANIZATION_NATURE") && input.organizationIds().isEmpty()
                        || previous != null && !previous.kind().equals("ORGANIZATION_NATURE") && previous.organizationIds().isEmpty();
                if (global || catalog.applies(replacement, org) || previous != null && catalog.applies(previous, org)) people.add(person.id());
            }
            people.sort(String::compareTo);
            String field = ClassificationMappings.FIELDS.get(input.kind());
            var versions = activeVersions(actor, field);
            String scope = batches.encode(new Scope(people, versions.stream().map(ObjectRecord::id).toList()));
            String id = "mapping-preview-" + UUID.randomUUID();
            var preview = tx.createObject("MappingImpactPreview", id, values("mappingId", input.mappingId(), "expectedMappingVersion", input.expectedVersion(),
                    "state", "QUEUED", "proposedMappingJson", batches.encode(input), "configurationDigest", BusinessCommands.hash(batches.encode(input)),
                    "inputDigest", "PENDING", "sourceStamp", sourceStamp, "scopeJson", scope, "resultJson", "[]", "processedCount", 0,
                    "totalCount", people.size(), "createdBy", actor.username(), "createdAt", clock.instant().toString(), "evaluatedDate", date()));
            if (previous != null) tx.createLink("MappingPreviewForMapping", id + "-mapping", preview.key(), new EntityKey("ClassificationMapping", input.mappingId()), Map.of());
            tx.createLink("MappingPreviewForTag", id + "-tag", preview.key(), new EntityKey("TagDefinition", input.targetTagId()), Map.of());
            for (String org : input.organizationIds()) tx.createLink("MappingPreviewForOrganization", id + "-" + org, preview.key(), new EntityKey("Organization", org), Map.of());
            return Map.of("id", id, "state", "QUEUED", "total", people.size(), "rules", versions.size());
        }, contracts.eventType(PREVIEW));
    }

    int processPending() {
        int count = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var worker = RuleBatchService.worker(tenant);
            for (var preview : directory.all(worker, "MappingImpactPreview")) {
                if (!Set.of("QUEUED", "RUNNING").contains(text(preview, "state"))) continue;
                if (!text(preview, "leaseUntil").isEmpty() && Instant.parse(text(preview, "leaseUntil")).isAfter(clock.instant())) continue;
                try {
                    count += process(worker, preview);
                } catch (BusinessConflict changed) {
                    // A competing worker owns the persisted lease or cursor.
                }
            }
        }
        return count;
    }

    private int process(Accounts.Actor worker, ObjectRecord observed) {
        String lease = UUID.randomUUID().toString();
        commands.executeDefined(worker, PREVIEW, "mapping-claim-" + lease, List.of(observed.id(), observed.version()),
                () -> contracts.authorizeSystem(PREVIEW), tx -> {
            var preview = batches.required(worker, "MappingImpactPreview", observed.id());
            if (preview.version() != observed.version()) throw new BusinessConflict("预览已被其他作业领取");
            contracts.requireTransition(preview.type(), "state", text(preview, "state"), "RUNNING", PREVIEW);
            tx.updateObject(preview.type(), preview.id(), values("state", "RUNNING", "leaseOwner", lease,
                    "leaseUntil", clock.instant().plusSeconds(120).toString()), preview.version());
            return Map.of("id", preview.id());
        }, contracts.eventType(PREVIEW));
        try {
            var preview = batches.required(worker, "MappingImpactPreview", observed.id());
            if (!fresh(worker, preview)) {
                storeResult(worker, preview, lease, "STALE", rows(preview), number(preview, "processedCount"), "资料或配置已变化，请重新预览");
                return 0;
            }
            Proposal input = proposal(preview);
            Scope scope = scope(preview);
            var before = mappings.load(worker);
            var after = before.replacing(entry(worker, input));
            var versions = scope.ruleVersionIds().stream().map(id -> batches.required(worker, "RuleVersion", id)).toList();
            List<ImpactRow> rows = new ArrayList<>(rows(preview));
            int start = number(preview, "processedCount");
            int end = Math.min(start + 100, scope.personIds().size());
            for (int i = start; i < end; i++) {
                var person = batches.required(worker, "Person", scope.personIds().get(i));
                rows.add(impact(worker, person, input.kind(), before, after, versions));
                if ((i - start + 1) % 20 == 0) renew(worker, preview.id(), lease);
            }
            String state = !fresh(worker, preview) ? "STALE" : end == scope.personIds().size() ? "COMPLETED" : "RUNNING";
            storeResult(worker, preview, lease, state, rows, end, state.equals("STALE") ? "计算期间资料或配置变化，请重新预览" : "");
            return end - start;
        } catch (BusinessConflict conflictingWriter) {
            throw conflictingWriter;
        } catch (RuntimeException invalidData) {
            var current = batches.required(worker, "MappingImpactPreview", observed.id());
            storeResult(worker, current, lease, "FAILED", rows(current), number(current, "processedCount"), "映射或规则依据不完整，请核实关联数据后重新预览");
            return 0;
        }
    }

    private ImpactRow impact(Accounts.Actor actor, ObjectRecord person, String kind, ClassificationMappings.Snapshot before,
                             ClassificationMappings.Snapshot after, List<ObjectRecord> versions) {
        String field = ClassificationMappings.FIELDS.get(kind);
        var fields = new java.util.HashSet<>(Set.of(field));
        versions.forEach(version -> fields.addAll(RuleExpression.validate(batches.condition(text(version, "conditionJson")))));
        var oldInput = facts.load(actor, person, fields, before);
        var newInput = facts.load(actor, person, fields, after);
        var grouped = versions.stream().collect(Collectors.groupingBy(version -> text(version, "tagDefinitionId"), TreeMap::new, Collectors.toList()));
        List<Effect> effects = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            var tag = batches.required(actor, "TagDefinition", group.getKey());
            var assignment = storage.getObject(actor.context(), "PersonTagAssignment", TagService.assignmentId(person.id(), tag.id()));
            var results = group.getValue().stream().map(version -> RuleExpression.evaluate(batches.condition(text(version, "conditionJson")), newInput)).toList();
            Set<String> changedRules = group.getValue().stream().map(version -> text(version, "ruleId")).collect(Collectors.toSet());
            boolean other = assignment != null && batches.activeContributions(actor, assignment).stream()
                    .anyMatch(contribution -> !changedRules.contains(text(contribution, "ruleId")) && activity.contributionActive(actor, contribution));
            if (assignment != null && directory.links(actor, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND).isEmpty()) {
                other = text(assignment, "state").equals("ACTIVE");
            }
            boolean suppressed = assignment != null && activity.state(actor, assignment).equals("SUPPRESSED");
            boolean activeBefore = assignment != null && activity.state(actor, assignment).equals("ACTIVE");
            boolean activeAfter = newInput.eligible() && !suppressed && (other || results.stream().anyMatch(result -> result.outcome().equals("MATCH")));
            String change = activeAfter && !activeBefore ? "ADD" : !activeAfter && activeBefore ? "EXPIRE" : "UNCHANGED";
            effects.add(new Effect(tag.id(), text(tag, "name"), change, suppressed,
                    results.stream().anyMatch(result -> result.outcome().equals("UNKNOWN")), !newInput.eligible(),
                    results.stream().map(RuleExpression.Result::reason).distinct().collect(Collectors.joining("；"))));
        }
        Object previous = oldInput.fields().get(field);
        Object proposed = newInput.fields().get(field);
        return new ImpactRow(person.id(), newInput.name(), newInput.organizationName(), describe(actor, previous), describe(actor, proposed),
                newInput.eligible(), proposed == null || proposed instanceof RuleFacts.PartialValues partial && !partial.complete(),
                !batches.encode(previous).equals(batches.encode(proposed)), effects);
    }

    private String describe(Accounts.Actor actor, Object classification) {
        if (classification == null) return "无法可靠判断";
        var ids = classification instanceof RuleFacts.PartialValues partial ? partial.values().stream().sorted().toList() : List.of(classification.toString());
        String names = ids.stream().map(id -> {
            var tag = storage.getObject(actor.context(), "TagDefinition", id);
            return tag == null ? "标签待核实" : text(tag, "name");
        }).collect(Collectors.joining("、"));
        return classification instanceof RuleFacts.PartialValues partial && !partial.complete() ? names + "（另有未确定领域）" : names;
    }

    private void renew(Accounts.Actor actor, String id, String lease) {
        commands.executeDefined(actor, PREVIEW, "mapping-renew-" + UUID.randomUUID(), List.of(id, lease), () -> contracts.authorizeSystem(PREVIEW), tx -> {
            var current = batches.required(actor, "MappingImpactPreview", id);
            if (!lease.equals(text(current, "leaseOwner"))) throw new BusinessConflict("预览租约已变化");
            tx.updateObject(current.type(), id, Map.of("leaseUntil", clock.instant().plusSeconds(120).toString()), current.version());
            return Map.of("id", id);
        }, contracts.eventType(PREVIEW));
    }

    private void storeResult(Accounts.Actor actor, ObjectRecord observed, String lease, String state, List<ImpactRow> rows, int processed, String error) {
        commands.executeDefined(actor, PREVIEW, "mapping-save-" + lease, List.of(observed.id(), state, processed), () -> contracts.authorizeSystem(PREVIEW), tx -> {
            var current = batches.required(actor, "MappingImpactPreview", observed.id());
            if (!lease.equals(text(current, "leaseOwner"))) throw new BusinessConflict("预览租约已变化");
            contracts.requireTransition(current.type(), "state", text(current, "state"), state, PREVIEW);
            String encoded = batches.encode(rows);
            String digest = BusinessCommands.hash(text(current, "configurationDigest") + text(current, "sourceStamp") + text(current, "scopeJson") + text(current, "evaluatedDate") + encoded);
            tx.updateObject(current.type(), current.id(), values("state", state, "resultJson", encoded, "processedCount", processed, "error", error,
                    "inputDigest", digest, "confirmedDigest", state.equals("COMPLETED") ? digest : null, "evaluatedAt", clock.instant().toString(),
                    "leaseOwner", null, "leaseUntil", null), current.version());
            return Map.of("id", current.id(), "state", state);
        }, contracts.eventType(PREVIEW));
    }

    Map<String, Object> detail(Accounts.Actor actor, String id, int page, int size) {
        authorize(actor, PREVIEW);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        var preview = batches.required(actor, "MappingImpactPreview", id);
        if (!actor.username().equals(text(preview, "createdBy"))) throw new AccessDeniedException("预览属于其他操作者");
        var rows = rows(preview);
        int start = (int) Math.min((long) page * size, rows.size());
        var counts = new HashMap<String, Long>();
        for (String key : List.of("ADD", "EXPIRE", "UNCHANGED", "UNKNOWN", "SKIPPED", "SUPPRESSED")) counts.put(key, 0L);
        for (var row : rows) for (var effect : row.effects()) {
            counts.merge(effect.change(), 1L, Long::sum);
            if (effect.unknown()) counts.merge("UNKNOWN", 1L, Long::sum);
            if (effect.skipped()) counts.merge("SKIPPED", 1L, Long::sum);
            if (effect.suppressed()) counts.merge("SUPPRESSED", 1L, Long::sum);
        }
        return values("id", id, "state", text(preview, "state"), "mappingId", text(preview, "mappingId"),
                "expectedVersion", preview.properties().get("expectedMappingVersion"), "digest", text(preview, "confirmedDigest"),
                "proposal", proposal(preview), "processed", number(preview, "processedCount"), "total", number(preview, "totalCount"),
                "ruleCount", scope(preview).ruleVersionIds().size(), "counts", counts,
                "changedPeople", rows.stream().filter(row -> row.classificationChanged() || row.effects().stream().anyMatch(effect -> !effect.change().equals("UNCHANGED"))).count(),
                "unknownPeople", rows.stream().filter(row -> row.eligible() && row.classificationUnknown()).count(),
                "items", rows.subList(start, Math.min(start + size, rows.size())), "page", page, "size", size, "error", text(preview, "error"));
    }

    Map<String, Object> publish(Accounts.Actor actor, String mappingId, Publish input, String key) {
        return commands.executeDefined(actor, PUBLISH, key, List.of(mappingId, input), () -> authorize(actor, PUBLISH), tx -> {
            var preview = batches.required(actor, "MappingImpactPreview", input.previewId());
            if (!text(preview, "createdBy").equals(actor.username()) || !text(preview, "mappingId").equals(mappingId)
                    || !text(preview, "state").equals("COMPLETED") || !text(preview, "confirmedDigest").equals(input.confirmedDigest())) {
                throw new BusinessConflict("预览或确认信息已变化，请重新预览");
            }
            Proposal proposed = normalize(actor, proposal(preview));
            if (input.expectedVersion() != proposed.expectedVersion() || !fresh(actor, preview)) throw new BusinessConflict("人员、组织、映射、规则或日期已变化，请重新预览");
            contracts.validateInputs(actor, PUBLISH, Map.of("mappingId", mappingId, "previewId", input.previewId(),
                    "expectedVersion", input.expectedVersion(), "confirmedDigest", input.confirmedDigest()));
            var target = batches.required(actor, "TagDefinition", proposed.targetTagId());
            var prior = storage.getObject(actor.context(), "ClassificationMapping", mappingId);
            var properties = values("kind", proposed.kind(), "sourceCode", proposed.sourceCode(), "state", proposed.enabled() ? "ACTIVE" : "INACTIVE",
                    "version", Long.toString(proposed.expectedVersion() + 1), "inherited", proposed.inherit(), "priority", proposed.priority(),
                    "publishedAt", clock.instant().toString(), "targetTagId", target.id(), "targetTagVersionId", text(target, "currentVersionId"),
                    "targetTagNameSnapshot", text(target, "name"), "organizationIdsJson", batches.encode(proposed.organizationIds()),
                    "organizationNamesJson", batches.encode(proposed.organizationIds().stream().map(id -> text(batches.required(actor, "Organization", id), "name")).toList()));
            ObjectRecord mapping;
            if (prior == null) mapping = tx.createObject("ClassificationMapping", mappingId, properties);
            else {
                contracts.requireTransition("ClassificationMapping", "state", text(prior, "state"), properties.get("state").toString(), PUBLISH);
                mapping = tx.updateObject(prior.type(), prior.id(), properties, prior.version());
                for (String type : List.of("MappingForTag", "MappingForOrganization")) {
                    for (var link : directory.links(actor, prior.key(), type, StorageProvider.Direction.OUTBOUND)) tx.deleteLink(type, link.id(), link.version());
                }
            }
            String suffix = "-v" + mapping.version();
            tx.createLink("MappingForTag", mappingId + suffix + "-tag", mapping.key(), target.key(), Map.of());
            for (String org : proposed.organizationIds()) tx.createLink("MappingForOrganization", mappingId + suffix + "-" + org,
                    mapping.key(), new EntityKey("Organization", org), Map.of());
            if (prior == null) tx.createLink("MappingPreviewForMapping", preview.id() + "-mapping", preview.key(), mapping.key(), Map.of());
            var scope = scope(preview);
            var versions = scope.ruleVersionIds().stream().map(id -> batches.required(actor, "RuleVersion", id)).toList();
            String batchId = "";
            if (!versions.isEmpty() && !scope.personIds().isEmpty()) {
                batchId = batches.enqueue(tx, actor, versions, scope.personIds(), "MAPPING:" + mappingId + suffix).id();
            }
            contracts.requireTransition(preview.type(), "state", "COMPLETED", "CONSUMED", PUBLISH);
            tx.updateObject(preview.type(), preview.id(), values("state", "CONSUMED", "publishedAt", clock.instant().toString(), "batchId", batchId), preview.version());
            return Map.of("id", mappingId, "version", mapping.version(), "state", properties.get("state"), "batchId", batchId);
        }, contracts.eventType(PUBLISH));
    }

    private Proposal normalize(Accounts.Actor actor, Proposal input) {
        if (input == null || input.mappingId() == null || !input.mappingId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")
                || input.kind() == null || input.targetTagId() == null || input.targetTagId().isBlank() || !ClassificationMappings.FIELDS.containsKey(input.kind()) || input.sourceCode() == null || input.sourceCode().length() > 120
                || input.organizationIds() == null || input.organizationIds().size() > 50000 || input.organizationIds().stream().anyMatch(id -> id == null || id.isBlank())
                || input.priority() < 0 || input.priority() > 10000 || input.expectedVersion() < 0) throw new IllegalArgumentException("映射配置不完整或不在支持范围内");
        var existing = storage.getObject(actor.context(), "ClassificationMapping", input.mappingId());
        if ((existing == null ? 0 : existing.version()) != input.expectedVersion() || existing != null && existing.isDeleted()) throw new BusinessConflict("映射版本已变化");
        if (existing != null && !text(existing, "kind").equals(input.kind())) throw new BusinessConflict("更换分类种类须新建映射，旧映射可停用");
        if (existing == null && !input.enabled()) throw new BusinessConflict("新建映射须先发布启用配置");
        var target = batches.required(actor, "TagDefinition", input.targetTagId());
        if (!input.enabled()) {
            var links = directory.links(actor, existing.key(), "MappingForTag", StorageProvider.Direction.OUTBOUND);
            if (links.size() != 1 || !links.getFirst().to().id().equals(target.id())) throw new BusinessConflict("停用时不得同时换目标标签");
        } else {
            if (!text(target, "status").equals("ACTIVE") || directory.all(actor, "TagDefinition").stream().anyMatch(tag -> text(tag, "parentId").equals(target.id()))) {
                throw new BusinessConflict("分类目标须为启用的末级标签");
            }
            if (text(target, "assignmentMode").equals("MANUAL_ONLY") || Set.of("NEW_PROMOTION", "RETIREMENT", "YOUNG", "NEW_ENTRY").contains(text(target, "code"))) {
                throw new BusinessConflict("成长特质标签不能作为单位、职务或岗位映射目标");
            }
            if (!text(target, "dimension").equals(input.kind().equals("POSITION_DOMAIN") ? "WORK" : "PERSON")) throw new BusinessConflict("目标标签维度与分类种类不符");
            var canonical = Map.of("PRINCIPAL", 400, "LEADERSHIP", 300, "MIDDLE", 200, "OTHER", 100);
            if (input.kind().equals("ROLE_LEVEL") && (!canonical.containsKey(text(target, "code")) || input.priority() != canonical.get(text(target, "code")))) {
                throw new BusinessConflict("职务层级使用一把手400、班子成员300、中层骨干200、其他干部100的固定高低顺序");
            }
            if (!input.kind().equals("ROLE_LEVEL") && canonical.containsKey(text(target, "code"))) throw new BusinessConflict("职务层级标签不能用于其他分类");
        }
        String source = input.sourceCode().strip();
        if (input.kind().equals("ORGANIZATION_NATURE")) {
            if (input.organizationIds().isEmpty() || !source.equals("ORGANIZATION")) throw new IllegalArgumentException("单位性质须指定组织根，来源类型为ORGANIZATION");
        } else if (!(source.startsWith("POSITION:") || source.startsWith("RANK:")) || source.substring(source.indexOf(':') + 1).isBlank()
                || source.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("只支持明确的标准岗位或职级编码");
        for (String orgId : input.organizationIds()) {
            var org = batches.required(actor, "Organization", orgId);
            if (input.enabled() && !text(org, "status").equals("ACTIVE")) throw new BusinessConflict("适用组织已停用");
        }
        return new Proposal(input.mappingId(), input.kind(), source, input.targetTagId(), input.organizationIds().stream().distinct().sorted().toList(),
                input.priority(), input.inherit(), input.enabled(), input.expectedVersion());
    }

    private ClassificationMappings.Entry entry(Accounts.Actor actor, Proposal input) {
        var tag = batches.required(actor, "TagDefinition", input.targetTagId());
        return new ClassificationMappings.Entry(input.mappingId(), input.expectedVersion() + 1, input.kind(), input.sourceCode(), input.targetTagId(),
                text(tag, "currentVersionId"), text(tag, "code"), text(tag, "status").equals("ACTIVE"), input.organizationIds(), input.priority(), input.inherit(), input.enabled());
    }

    private List<ObjectRecord> activeVersions(Accounts.Actor actor, String field) {
        List<ObjectRecord> versions = new ArrayList<>();
        for (var rule : directory.all(actor, "TagRule")) {
            if (!text(rule, "status").equals("ACTIVE")) continue;
            var tag = batches.required(actor, "TagDefinition", text(rule, "tagDefinitionId"));
            if (!text(tag, "status").equals("ACTIVE")) continue;
            var version = batches.required(actor, "RuleVersion", text(rule, "currentVersionId"));
            if (RuleExpression.validate(batches.condition(text(version, "conditionJson"))).contains(field)) versions.add(version);
        }
        return versions.stream().sorted(java.util.Comparator.comparing(ObjectRecord::id)).toList();
    }

    private boolean fresh(Accounts.Actor actor, ObjectRecord preview) {
        return text(preview, "sourceStamp").equals(stamps.read(actor.tenantId(), true)) && text(preview, "evaluatedDate").equals(date());
    }

    private void authorize(Accounts.Actor actor, String action) {
        contracts.authorize(actor, action);
        if (!actor.role().equals("SUPER_ADMIN")) throw new AccessDeniedException("映射维护与全市影响预览仅对超级管理员开放");
    }

    private String date() { return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")).toString(); }
    private Proposal proposal(ObjectRecord preview) { return decode(text(preview, "proposedMappingJson"), new TypeReference<>() {}); }
    private Scope scope(ObjectRecord preview) { return decode(text(preview, "scopeJson"), new TypeReference<>() {}); }
    private List<ImpactRow> rows(ObjectRecord preview) { return decode(text(preview, "resultJson"), new TypeReference<>() {}); }
    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception invalid) { throw new BusinessConflict("映射预览记录不完整，请重新创建"); }
    }
    private Map<String, Object> parameters(Proposal proposal) { return json.convertValue(proposal, new TypeReference<>() {}); }

    record Proposal(String mappingId, String kind, String sourceCode, String targetTagId, List<String> organizationIds,
                    int priority, boolean inherit, boolean enabled, long expectedVersion) {}
    record Publish(String previewId, long expectedVersion, String confirmedDigest) {}
    record Scope(List<String> personIds, List<String> ruleVersionIds) {}
    record Effect(String tagId, String tagName, String change, boolean suppressed, boolean unknown, boolean skipped, String reason) {}
    record ImpactRow(String personId, String name, String organization, String before, String after, boolean eligible,
                     boolean classificationUnknown, boolean classificationChanged, List<Effect> effects) {}
}
