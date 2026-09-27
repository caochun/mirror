package gov.objectlibrary.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static gov.objectlibrary.server.MetricsSnapshotReader.Fact;

/** Permission-scoped metrics and their drilldowns come from the same immutable, short-lived query snapshot. */
@Service
class BusinessMetricsService {
    private static final Set<String> CODES = Set.of("effectivePeople", "associationIssues", "nonObjectAccounts", "tagCoverage",
            "tagSourceDistribution", "tagPending", "targetRecipients", "deliveryRate", "readRateAmongDelivered",
            "readCoverageAmongTargets", "overdueUnread", "integrationIssues");
    private final MetricsSnapshotReader reader;
    private final Accounts accounts;
    private final DomainContracts contracts;
    private final Clock clock;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>();

    BusinessMetricsService(MetricsSnapshotReader reader, Accounts accounts, DomainContracts contracts, Clock clock,
                           @Value("${mirror.pack}") String pack) throws Exception {
        this.reader = reader;
        this.accounts = accounts;
        this.contracts = contracts;
        this.clock = clock;
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> document = new Yaml(new SafeConstructor(options)).load(Files.readString(Path.of(pack).resolve("contracts/metrics.yaml")));
        for (Object item : (List<?>) document.get("metrics")) {
            Map<?, ?> row = (Map<?, ?>) item;
            String code = row.get("code").toString();
            definitions.put(code, new Definition(code, row.get("grain").toString(), row.get("definition").toString(), row.get("organizationBasis").toString()));
        }
        if (!definitions.keySet().equals(CODES)) throw new IllegalStateException("Metrics handlers and Domain Pack definitions do not match");
    }

    Report query(Accounts.Actor actor, Filter filter) {
        authorize(actor);
        filter.validate();
        var source = reader.read(actor.tenantId());
        var data = new Projection(actor, filter, source);
        contracts.validateInputs(actor, "QueryBusinessMetrics", Map.of("metricCodes", List.copyOf(CODES), "filters", filter.parameters(), "asOf", source.asOf().toString()));
        data.calculate();
        authorize(actor);
        var currentAuthority = reader.authorityView(actor.tenantId());
        String publication = publication(source, data.usedTaskIds);
        if (!source.authority().equals(currentAuthority.authority()) || !publication.equals(publication(currentAuthority, data.usedTaskIds))) {
            throw new BusinessConflict("查询期间权限范围或发布版本变化，请重新查询");
        }
        String id = UUID.randomUUID().toString();
        List<Metric> metrics = definitions.values().stream().map(def -> data.metric(def)).toList();
        var report = new Report(id, source.asOf().toString(), filter, data.scopeLabel(), data.mode(), metrics,
                data.bars("organizations"), data.bars("tagHierarchy"), data.bars("sources"), data.bars("pending"), data.bars("integration"),
                data.taskViews(), data.counts(), data.options(), List.of(
                "对象与质量按当前单位；标签目录影响覆盖分子、来源和待处理项，不改变有效对象基数。提醒标签版本仅用于历史提醒。",
                "任务时间按首次发布时点，未发布的批准名单按创建时间；对接问题和重试按事件时间。",
                "提醒统计限有权任务及发送时组织；逾期按人员当前主管范围，已撤回者不再督促。",
                "快照下钻保留同一统计时点，处置前可进入阅读清单查看最新状态。",
                "问题及AI统计反映已记录事实；没有评估记录不等于没有问题。"));
        String authorization = authorization(actor, source.authority());
        var cached = new Cached(actor, authorization, publication, Set.copyOf(data.usedTaskIds), clock.instant().plusSeconds(120), report, data.freezeRows());
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
            long weight = cached.weight();
            if (weight > 2_000_000) throw new BusinessConflict("查询结果过大，请按组织或任务缩小范围");
            while (!cache.isEmpty() && (cache.size() >= 16 || cache.values().stream().mapToLong(Cached::weight).sum() + weight > 2_000_000)) {
                cache.remove(cache.keySet().iterator().next());
            }
            cache.put(id, cached);
        }
        return report;
    }

    Details details(Accounts.Actor actor, String snapshotId, String metric, String group, int page, int size) {
        authorize(actor);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        Cached cached;
        synchronized (cache) { cached = cache.get(snapshotId); }
        if (cached == null || !cached.expiresAt().isAfter(clock.instant())) throw new BusinessConflict("统计快照已过期，请刷新大屏");
        if (!cached.actor().equals(actor)) throw new AccessDeniedException("统计快照不属于当前账号");
        var authority = reader.authorityView(actor.tenantId());
        if (!cached.authorization().equals(authorization(actor, authority.authority())) || !cached.publication().equals(publication(authority, cached.taskIds()))) {
            throw new BusinessConflict("权限范围或发布版本已变化，请刷新大屏");
        }
        List<Row> rows = cached.rows().get(metric);
        if (rows == null) throw new IllegalArgumentException("Unknown metric");
        if (group != null && !group.isEmpty()) rows = rows.stream().filter(row -> group.equals(row.group())).toList();
        int start = (int) Math.min((long) page * size, rows.size());
        return new Details(rows.subList(start, Math.min(start + size, rows.size())), rows.size(), page, size, cached.report().asOf());
    }

    private void authorize(Accounts.Actor actor) {
        contracts.authorize(actor, "QueryBusinessMetrics");
        accounts.requirePermission(actor, "PERSON_READ");
        accounts.requirePermission(actor, "REMINDER_READ");
        accounts.requirePermission(actor, "OVERDUE_READ");
    }

    private String publication(MetricsSnapshotReader.Snapshot source, Set<String> ids) {
        var keys = new TreeSet<String>();
        for (String id : ids) {
            var task = source.facts().getOrDefault("ReminderTask", Map.of()).get(id);
            String version = task == null ? "missing" : !task.text("currentPublishedVersionId").isEmpty()
                    ? task.text("currentPublishedVersionId") : task.text("pendingVersionId");
            keys.add(id + "/" + version);
        }
        return BusinessCommands.hash(String.join("\n", keys));
    }

    private String authorization(Accounts.Actor actor, String source) {
        return BusinessCommands.hash(source + "/" + new TreeSet<>(accounts.permissions(actor)) + "/" + new TreeSet<>(accounts.roots(actor.username())));
    }

    private class Projection {
        private final Accounts.Actor actor;
        private final Filter filter;
        private final MetricsSnapshotReader.Snapshot source;
        private final Map<String, Map<String, List<String>>> outgoing = new HashMap<>();
        private final Map<String, Map<String, List<String>>> incoming = new HashMap<>();
        private final Map<String, List<Row>> rows = new LinkedHashMap<>();
        private final Map<String, Long> counters = new LinkedHashMap<>();
        private final Map<String, Set<String>> activeTags = new HashMap<>();
        private final Map<String, String> currentOrganizations = new HashMap<>();
        private final Set<String> scope;
        private final Set<String> personScope;
        private final Set<String> tagScope;
        private final Set<String> creatorScope;
        private final Set<String> recipientScope;
        private final Map<String, Instant> publishedTimes = new HashMap<>();
        private final Set<String> integrationRecipients = new HashSet<>();
        private final Set<String> usedTaskIds = new HashSet<>();
        private final Map<String, TaskView> taskViews = new LinkedHashMap<>();
        private final Map<String, List<String>> recipientTags = new HashMap<>();
        private final Map<String, String> readStates = new HashMap<>();
        private final Map<String, String> firstReads = new HashMap<>();
        private final Set<String> effectivePeople = new HashSet<>();
        private final Set<String> visiblePeople = new HashSet<>();
        private final Set<String> visibleTaskIds = new HashSet<>();
        private final Set<String> matchedRecipients = new HashSet<>();
        private final Set<String> supervisedRecipients = new HashSet<>();
        private long unknownRead;
        private long unknownDelivery;

        Projection(Accounts.Actor actor, Filter filter, MetricsSnapshotReader.Snapshot source) {
            this.actor = actor;
            this.filter = filter;
            this.source = source;
            for (var edge : source.edges()) {
                outgoing.computeIfAbsent(edge.type(), ignored -> new HashMap<>()).computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
                incoming.computeIfAbsent(edge.type(), ignored -> new HashMap<>()).computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
                if (edge.type().equals("VersionTargetsRecipient") && edge.tagVersions() != null) {
                    recipientTags.merge(edge.from() + "/" + edge.to(), edge.tagVersions(), (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList());
                }
            }
            outgoing.values().forEach(index -> index.replaceAll((id, targets) -> targets.stream().distinct().toList()));
            incoming.values().forEach(index -> index.replaceAll((id, origins) -> origins.stream().distinct().collect(Collectors.toCollection(ArrayList::new))));
            for (var tag : facts("TagDefinition").values()) {
                if (!tag.text("parentId").isEmpty() && to("TagParent", tag.id()).isEmpty()) {
                    outgoing.computeIfAbsent("TagParent", ignored -> new HashMap<>()).put(tag.id(), List.of(tag.text("parentId")));
                    incoming.computeIfAbsent("TagParent", ignored -> new HashMap<>()).computeIfAbsent(tag.text("parentId"), ignored -> new ArrayList<>()).add(tag.id());
                }
            }
            scope = switch (actor.role()) {
                case "SUPER_ADMIN" -> new HashSet<>(facts("Organization").keySet());
                case "UNIT_ADMIN" -> descendants("OrganizationParent", List.of(actor.organizationId()));
                case "AREA_ADMIN" -> descendants("OrganizationParent", source.roots().getOrDefault(actor.username(), List.of()));
                default -> throw new AccessDeniedException("无统计数据范围");
            };
            for (String org : List.of(filter.organizationId(), filter.recipientOrganization())) {
                if (!org.isEmpty() && !scope.contains(org)) throw new AccessDeniedException("筛选组织超出权限范围");
            }
            creatorScope = filter.creatorOrganization().isEmpty() ? scope : descendants("OrganizationParent", List.of(filter.creatorOrganization()));
            recipientScope = filter.recipientOrganization().isEmpty() ? scope : descendants("OrganizationParent", List.of(filter.recipientOrganization()));
            for (var version : facts("ReminderTaskVersion").values()) {
                Instant published = instant(version.text("publishedAt"));
                if (published != null) publishedTimes.merge(version.text("taskId"), published, (a, b) -> a.isBefore(b) ? a : b);
            }
            personScope = filter.organizationId().isEmpty() ? scope : descendants("OrganizationParent", List.of(filter.organizationId()));
            personScope.retainAll(scope);
            tagScope = filter.tagId().isEmpty() ? facts("TagDefinition").keySet() : descendants("TagParent", List.of(filter.tagId()));
            if (!filter.tagId().isEmpty() && !facts("TagDefinition").containsKey(filter.tagId())) throw new IllegalArgumentException("Unknown tag");
            if (!filter.tagVersionId().isEmpty()) {
                var version = fact("TagVersion", filter.tagVersionId());
                if (version == null || !from("TagParent", version.text("tagDefinitionId")).isEmpty()) {
                    throw new IllegalArgumentException("标签版本筛选仅支持末级标签，上级请使用目录筛选");
                }
            }
            for (var person : facts("Person").values()) {
                String org = single(to("PersonBelongsToOrganization", person.id()));
                currentOrganizations.put(person.id(), org);
                if (personScope.contains(org) || actor.role().equals("SUPER_ADMIN") && filter.organizationId().isEmpty() && org.isEmpty()) visiblePeople.add(person.id());
            }
            for (var task : facts("ReminderTask").values()) {
                Fact version = effectiveVersion(task);
                boolean supervised = version != null && to("VersionTargetsRecipient", version.id()).stream().map(id -> fact("RecipientRecord", id))
                        .anyMatch(recipient -> recipient != null && scope.contains(currentOrganizations.getOrDefault(recipient.text("personId"), "")));
                if (canReadTask(task) || supervised) visibleTaskIds.add(task.id());
            }
            boolean creatorAllowed = filter.creatorOrganization().isEmpty() || actor.role().equals("SUPER_ADMIN") && scope.contains(filter.creatorOrganization())
                    || filter.creatorOrganization().equals(actor.organizationId()) || visibleTaskIds.stream()
                    .anyMatch(id -> filter.creatorOrganization().equals(fact("ReminderTask", id).text("organizationId")));
            if (!creatorAllowed) throw new AccessDeniedException("筛选创建单位超出可见任务范围");
            if (!filter.taskId().isEmpty() && !visibleTaskIds.contains(filter.taskId())) throw new AccessDeniedException("筛选任务超出权限范围");
            CODES.forEach(code -> rows.put(code, new ArrayList<>()));
            for (String code : List.of("organizations", "tags", "tagHierarchy", "sources", "pending", "integration", "submitted", "delivered", "failed", "unknown", "unread", "withdrawn", "notPublished")) rows.put(code, new ArrayList<>());
        }

        void calculate() {
            peopleAndTags();
            accountsAndIssues();
            reminderMetrics();
            pendingTags();
            integrationMetrics();
            rows.values().forEach(values -> values.sort(Comparator.comparing(Row::name).thenComparing(Row::id).thenComparing(Row::group)));
        }

        private void peopleAndTags() {
            for (String id : visiblePeople) {
                var person = fact("Person", id);
                boolean eligible = from("EligibilityForPerson", id).stream().map(e -> fact("ObjectEligibility", e)).allMatch(e -> e != null && e.text("state").equals("ELIGIBLE"));
                String org = currentOrganizations.get(id);
                if (person.text("status").equals("ACTIVE") && eligible && fact("Organization", org) != null && fact("Organization", org).text("status").equals("ACTIVE")) effectivePeople.add(id);
            }
            for (var assignment : facts("PersonTagAssignment").values()) {
                String person = assignment.text("personId");
                String tag = assignment.text("tagDefinitionId");
                if (!effectivePeople.contains(person) || !assignment.text("state").equals("ACTIVE") || assignment.flag("manualSuppressed") || !enabledTag(tag)
                        || !validInterval(assignment, "effectiveFrom", "effectiveTo")) continue;
                if (!tagScope.contains(tag)) continue;
                List<Fact> contributions = to("AssignmentHasContribution", assignment.id()).stream().map(id -> fact("TagContribution", id))
                        .filter(c -> c != null && c.text("state").equals("ACTIVE") && validInterval(c, "effectiveFrom", "effectiveTo")).toList();
                if (contributions.isEmpty()) { addCount("tagsWithoutEvidence", 1); continue; }
                if (!tagScope.contains(tag)) continue;
                boolean firstLogicalTag = activeTags.computeIfAbsent(person, ignored -> new HashSet<>()).add(tag);
                String label = fact("TagDefinition", tag).text("name");
                if (firstLogicalTag) rows.get("tags").add(personRow(person, assignment.id(), tag, label, "ACTIVE", ""));
                for (var contribution : contributions) {
                    var row = personRow(person, contribution.id(), contribution.text("source"), contribution.text("source"), "ACTIVE", contribution.text("effectiveFrom"));
                    rows.get("tagSourceDistribution").add(row);
                    rows.get("sources").add(row);
                }
            }
            Set<String> seenHierarchy = new HashSet<>();
            for (var row : rows.get("tags")) {
                for (String ancestor : tagLineage(row.group())) {
                    if (tagScope.contains(ancestor) && seenHierarchy.add(ancestor + "/" + row.personId())) {
                        rows.get("tagHierarchy").add(personRow(row.personId(), row.personId(), ancestor, tagPath(ancestor), "ACTIVE", ""));
                    }
                }
            }
            for (String id : effectivePeople) {
                var row = personRow(id, id, currentOrganizations.get(id), organizationName(currentOrganizations.get(id)), "ACTIVE", "");
                rows.get("effectivePeople").add(row);
                rows.get("organizations").add(row);
                if (activeTags.containsKey(id)) rows.get("tagCoverage").add(row);
                if (fact("Person", id).text("profileStatus").isEmpty() && from("ProfileForPerson", id).isEmpty()) addCount("unverifiedProfiles", 1);
            }
        }

        private void accountsAndIssues() {
            Map<String, Set<String>> categories = new HashMap<>();
            for (var issue : facts("DataAssociationIssue").values()) {
                if (!issue.text("status").equals("OPEN")) continue;
                for (String person : to("IssueForPerson", issue.id())) {
                    if (!visiblePeople.contains(person)) continue;
                    categories.computeIfAbsent(person, ignored -> new TreeSet<>()).add(issue.text("category"));
                }
            }
            categories.forEach((person, types) -> {
                rows.get("associationIssues").add(personRow(person, person, "", String.join("、", types), "OPEN", ""));
                types.forEach(type -> addCount("association." + type, 1));
            });
            Set<String> nonObjects = new HashSet<>();
            for (var eligibility : facts("ObjectEligibility").values()) {
                if (!eligibility.text("state").equals("NON_OBJECT")) continue;
                Set<String> ids = new HashSet<>(to("EligibilityForAccount", eligibility.id()));
                for (String person : to("EligibilityForPerson", eligibility.id())) ids.addAll(from("AccountForPerson", person));
                for (String id : ids) {
                    String org = single(to("AccountCurrentOrganization", id));
                    if (org.isEmpty()) org = source.nativeAccountOrganizations().getOrDefault(id, "");
                    if (!personScope.contains(org) || !nonObjects.add(id)) continue;
                    var account = fact("UserAccount", id);
                    rows.get("nonObjectAccounts").add(new Row(id, "", account == null ? id : account.text("username"), organizationName(org), "非对象账号", "NON_OBJECT", "", "business", "", ""));
                }
            }
        }

        private void reminderMetrics() {
            for (var state : facts("RecipientVersionState").values()) {
                String recipient = single(to("VersionStateForRecipient", state.id()));
                String version = single(to("VersionStateForVersion", state.id()));
                if (!recipient.isEmpty() && !version.isEmpty()) readStates.put(recipient + "/" + version, Set.of("READ", "UNREAD").contains(state.text("state")) ? state.text("state") : "UNKNOWN");
            }
            for (var read : facts("ReadReceipt").values()) {
                if (read.text("readAt").isEmpty()) continue;
                String key = read.text("recipientId") + "/" + read.text("taskVersionId");
                readStates.put(key, "READ");
                firstReads.merge(key, read.text("readAt"), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
            for (var task : facts("ReminderTask").values()) {
                Fact version = effectiveVersion(task);
                if (version == null || !taskMatches(task, version, false)) continue;
                boolean inTaskTime = inTime(firstPublished(task).toString());
                var members = to("VersionTargetsRecipient", version.id());
                int targets = 0;
                int read = 0;
                for (String id : members) {
                    var recipient = fact("RecipientRecord", id);
                    if (recipient == null || !recipient.text("taskId").equals(task.id())) { if (canReadTask(task)) addCount("invalidRecipientLinks", 1); continue; }
                    String person = recipient.text("personId");
                    String currentOrg = currentOrganizations.getOrDefault(person, "");
                    String snapshotOrg = recipient.text("organizationIdSnapshot");
                    if (!filter.recipientOrganization().isEmpty() && !recipientScope.contains(snapshotOrg)) continue;
                    boolean owned = canReadTask(task) && (actor.role().equals("SUPER_ADMIN") || scope.contains(snapshotOrg));
                    boolean supervised = personScope.contains(currentOrg) || actor.role().equals("SUPER_ADMIN") && filter.organizationId().isEmpty() && currentOrg.isEmpty();
                    if (!filter.organizationId().isEmpty()) owned &= personScope.contains(snapshotOrg);
                    if (!owned && !supervised) continue;
                    usedTaskIds.add(task.id());
                    var frozenTags = recipientTags.get(version.id() + "/" + id);
                    if (!filter.tagId().isEmpty() || !filter.tagVersionId().isEmpty()) {
                        if (frozenTags == null) { if (owned) addCount("missingTagSnapshots", 1); if (supervised) addCount("missingOverdueTagSnapshots", 1); continue; }
                        boolean match = frozenTags.stream().anyMatch(tagVersion -> {
                            var tag = fact("TagVersion", tagVersion);
                            return tag != null && tagScope.contains(tag.text("tagDefinitionId"))
                                    && (filter.tagVersionId().isEmpty() || filter.tagVersionId().equals(tagVersion));
                        });
                        if (!match) continue;
                    }
                    String state = readStates.getOrDefault(id + "/" + version.id(), task.text("currentPublishedVersionId").isEmpty() ? "NOT_PUBLISHED" : "UNKNOWN");
                    boolean withdrawn = recipient.text("withdrawalState").equals("WITHDRAWN");
                    String delivery = recipient.text("deliveryState");
                    boolean deliveryKnown = Set.of("PENDING", "SUBMITTED", "DELIVERED", "FAILED", "UNKNOWN").contains(delivery)
                            && (!delivery.equals("DELIVERED") || instant(recipient.text("firstDeliveredAt")) != null);
                    boolean delivered = deliveryKnown && delivery.equals("DELIVERED");
                    boolean overdue = delivered && !withdrawn && state.equals("UNREAD") && !recipient.text("firstDeliveredAt").isEmpty()
                            && instant(recipient.text("deadlineAt")) != null && !instant(recipient.text("deadlineAt")).isAfter(source.asOf());
                    String mode = recipient.text("channelMode").isEmpty() ? personMode(person) : recipient.text("channelMode");
                    var row = new Row(id, person, recipient.text("personNameSnapshot"), recipient.text("organizationNameSnapshot"), task.text("title"),
                            delivery + "/" + state + "/" + recipient.text("withdrawalState"), "", mode, recipient.text("firstDeliveredAt"), canReadTask(task) ? "/reminders/" + task.id() : "/reading");
                    if ((owned || supervised) && (filter.includeWithdrawn() || !withdrawn)) integrationRecipients.add(id);
                    if (!inTaskTime) continue;
                    if (supervised) supervisedRecipients.add(id);
                    if (supervised && overdue) rows.get("overdueUnread").add(new Row(id, person, personName(person, recipient), organizationName(currentOrg),
                            task.text("title"), "OVERDUE", "", mode, recipient.text("deadlineAt"), "/reading"));
                    if (!owned) continue;
                    if (withdrawn) rows.get("withdrawn").add(row);
                    if (withdrawn && !filter.includeWithdrawn()) continue;
                    matchedRecipients.add(id);
                    if (!deliveryKnown) unknownDelivery++;
                    targets++;
                    rows.get("targetRecipients").add(row);
                    if (deliveryKnown && !delivery.equals("PENDING")) rows.get("submitted").add(row);
                    if (delivered) { rows.get("delivered").add(row); rows.get("deliveryRate").add(row); }
                    if (delivery.equals("FAILED")) rows.get("failed").add(row);
                    if (delivery.equals("UNKNOWN")) rows.get("unknown").add(row);
                    if (state.equals("READ")) {
                        read++;
                        rows.get("readCoverageAmongTargets").add(row);
                        if (delivered) rows.get("readRateAmongDelivered").add(row);
                    } else if (state.equals("UNREAD")) rows.get("unread").add(row);
                    else if (state.equals("NOT_PUBLISHED")) rows.get("notPublished").add(row);
                    else if (state.equals("UNKNOWN")) unknownRead++;
                }
                if (canReadTask(task) && targets > 0) taskViews.put(task.id(), new TaskView(task.id(), task.text("title"), organizationName(task.text("organizationId")),
                        targets, read, task.text("state"), firstPublished(task).toString()));
            }
            addCount("unknownReading", unknownRead);
            addCount("unknownDelivery", unknownDelivery);
            addCount("targetPeople", rows.get("targetRecipients").stream().map(Row::personId).distinct().count());
            addCount("overduePeople", rows.get("overdueUnread").stream().map(Row::personId).distinct().count());
        }

        private void pendingTags() {
            Map<String, Row> pending = new HashMap<>();
            for (var assignment : facts("PersonTagAssignment").values()) {
                String person = assignment.text("personId");
                String tag = assignment.text("tagDefinitionId");
                if (visiblePeople.contains(person) && tagScope.contains(tag) && (assignment.text("state").equals("SUPPRESSED") || assignment.flag("manualSuppressed"))) {
                    pending.put("SUPPRESSED/" + person + "/" + tag, personRow(person, person + "/" + tag, "SUPPRESSED", tagName(tag), "SUPPRESSED", ""));
                }
            }
            for (var candidate : facts("TagCandidate").values()) {
                if (!candidate.text("state").equals("PENDING")) continue;
                String person = single(to("TagCandidateForPerson", candidate.id()));
                String version = single(to("CandidateForTagVersion", candidate.id()));
                addPending(pending, candidate, person, version, "AI_PENDING");
            }
            for (var issue : facts("TagProcessingIssue").values()) {
                if (!issue.text("state").equals("OPEN")) continue;
                String person = single(to("TagIssueForPerson", issue.id()));
                String version = single(to("TagIssueForTagVersion", issue.id()));
                addPending(pending, issue, person, version, issue.text("category"));
            }
            var pairs = pending.values().stream().collect(Collectors.groupingBy(Row::id));
            pairs.forEach((id, values) -> {
                Row first = values.getFirst();
                rows.get("tagPending").add(new Row(id, first.personId(), first.name(), first.organization(), first.label(),
                        values.stream().map(Row::state).distinct().sorted().collect(Collectors.joining(" / ")), "", first.mode(), first.time(), first.href()));
            });
            rows.get("pending").addAll(pending.values());
        }

        private void addPending(Map<String, Row> pending, Fact source, String person, String versionId, String category) {
            var version = fact("TagVersion", versionId);
            if (!visiblePeople.contains(person) || version == null) return;
            String tag = version.text("tagDefinitionId");
            if (!tagScope.contains(tag)) return;
            pending.putIfAbsent(category + "/" + person + "/" + tag, personRow(person, person + "/" + tag, category, tagName(tag), category, source.text("detectedAt")));
        }

        private void integrationMetrics() {
            for (var issue : facts("IntegrationIssue").values()) {
                String recipient = single(to("IntegrationIssueForRecipient", issue.id()));
                if (!integrationRecipients.contains(recipient) || !inTime(issue.text("occurredAt"))) continue;
                var record = fact("RecipientRecord", recipient);
                if (record == null) continue;
                var row = new Row(issue.id(), record.text("personId"), record.text("personNameSnapshot"), record.text("organizationNameSnapshot"),
                        issue.text("category"), issue.text("state"), issue.text("category"), record.text("channelMode"), issue.text("occurredAt"), "");
                rows.get("integrationIssues").add(row);
                rows.get("integration").add(row);
            }
            Map<String, List<Fact>> attempts = facts("DeliveryAttempt").values().stream().filter(a -> integrationRecipients.contains(a.text("recipientId")))
                    .collect(Collectors.groupingBy(a -> a.text("recipientId")));
            long retries = 0;
            for (var attemptsForRecipient : attempts.values()) {
                var retryAttempts = attemptsForRecipient.stream().sorted(Comparator.comparing((Fact a) -> a.text("attemptedAt")).thenComparing(Fact::id))
                        .skip(1).filter(a -> inTime(a.text("attemptedAt"))).toList();
                retries += retryAttempts.size();
                for (var attempt : retryAttempts) addCount("retry." + attempt.text("state"), 1);
            }
            addCount("deliveryRetries", retries);
            long withdrawalRetries = 0;
            var withdrawals = facts("WithdrawalRecord").values().stream().filter(record -> integrationRecipients.contains(record.text("recipientId")))
                    .collect(Collectors.groupingBy(record -> record.text("recipientId")));
            for (var history : withdrawals.values()) {
                var retried = history.stream().sorted(Comparator.comparing((Fact row) -> row.text("requestedAt")).thenComparing(Fact::id))
                        .skip(1).filter(row -> inTime(row.text("requestedAt"))).toList();
                withdrawalRetries += retried.size();
                for (var attempt : retried) addCount("withdrawalRetry." + attempt.text("state"), 1);
            }
            addCount("withdrawalRetries", withdrawalRetries);
            addCount("withdrawn", rows.get("withdrawn").size());
        }

        private boolean taskMatches(Fact task, Fact version, boolean time) {
            if (!filter.taskId().isEmpty() && !filter.taskId().equals(task.id())) return false;
            if (!filter.creatorOrganization().isEmpty() && !creatorScope.contains(task.text("organizationId"))) return false;
            if (!filter.category().isEmpty() && !filter.category().equals(version.text("categorySnapshot"))) return false;
            return !time || inTime(firstPublished(task).toString());
        }

        private Instant firstPublished(Fact task) {
            return publishedTimes.getOrDefault(task.id(), instant(task.text("createdAt")) == null ? task.createdAt() : instant(task.text("createdAt")));
        }

        private Fact effectiveVersion(Fact task) {
            if (task.text("state").equals("CANCELLED")) return null;
            var published = fact("ReminderTaskVersion", task.text("currentPublishedVersionId"));
            if (published != null && published.text("state").equals("PUBLISHED")) return published;
            var approved = fact("ReminderTaskVersion", task.text("pendingVersionId"));
            return approved != null && approved.text("state").equals("APPROVED") ? approved : null;
        }

        private boolean canReadTask(Fact task) {
            return actor.role().equals("SUPER_ADMIN") || actor.organizationId().equals(task.text("organizationId")) || actor.username().equals(task.text("createdBy"));
        }
        private boolean matchesCurrentTag(String person) { return filter.tagId().isEmpty() && filter.tagVersionId().isEmpty() || activeTags.containsKey(person); }
        private boolean enabledTag(String id) {
            Set<String> seen = new HashSet<>();
            while (!id.isEmpty()) {
                var tag = fact("TagDefinition", id);
                if (tag == null || !seen.add(id) || !tag.text("status").equals("ACTIVE")) return false;
                String parent = single(to("TagParent", id));
                id = parent.isEmpty() ? tag.text("parentId") : parent;
            }
            return true;
        }
        private boolean validInterval(Fact fact, String from, String to) {
            Instant start = instant(fact.text(from));
            Instant end = instant(fact.text(to));
            return (start == null || !start.isAfter(source.asOf())) && (end == null || end.isAfter(source.asOf()));
        }
        private boolean inTime(String value) {
            Instant time = instant(value);
            return time != null && (filter.from() == null || !time.isBefore(filter.from())) && (filter.to() == null || time.isBefore(filter.to()));
        }
        private Row personRow(String person, String id, String group, String label, String state, String time) {
            var p = fact("Person", person);
            return new Row(id, person, p == null ? "待核实人员" : p.text("name"), organizationName(currentOrganizations.getOrDefault(person, "")), label,
                    state, group, personMode(person), time, "/people/" + person);
        }
        private String personMode(String id) { var p = fact("Person", id); return p != null && p.flag("mock") ? "mock" : "business"; }
        private String personName(String id, Fact recipient) { var p = fact("Person", id); return p == null ? recipient.text("personNameSnapshot") : p.text("name"); }
        private List<String> tagLineage(String id) {
            var result = new ArrayList<String>();
            var seen = new HashSet<String>();
            while (!id.isEmpty() && seen.add(id)) {
                var tag = fact("TagDefinition", id);
                if (tag == null) break;
                result.add(id);
                String parent = single(to("TagParent", id));
                id = parent.isEmpty() ? tag.text("parentId") : parent;
            }
            return result;
        }
        private String tagPath(String id) {
            var ids = tagLineage(id);
            java.util.Collections.reverse(ids);
            return ids.stream().map(this::tagName).collect(Collectors.joining(" / "));
        }
        private String tagName(String id) { var tag = fact("TagDefinition", id); return tag == null ? "待核实标签" : tag.text("name"); }
        private String organizationName(String id) { var org = fact("Organization", id); return org == null ? "单位待核实" : org.text("name"); }
        private Map<String, Fact> facts(String type) { return source.facts().getOrDefault(type, Map.of()); }
        private Fact fact(String type, String id) { return facts(type).get(id); }
        private List<String> to(String type, String from) { return outgoing.getOrDefault(type, Map.of()).getOrDefault(from, List.of()); }
        private List<String> from(String type, String to) { return incoming.getOrDefault(type, Map.of()).getOrDefault(to, List.of()); }
        private String single(List<String> ids) { return ids.size() == 1 ? ids.getFirst() : ""; }
        private Set<String> descendants(String type, Collection<String> roots) {
            Set<String> seen = new HashSet<>();
            var pending = new ArrayDeque<>(roots);
            while (!pending.isEmpty()) { String id = pending.removeFirst(); if (seen.add(id)) pending.addAll(from(type, id)); }
            return seen;
        }
        private void addCount(String code, long value) { counters.merge(code, value, Long::sum); }
        private Map<String, Long> counts() {
            for (String key : List.of("submitted", "delivered", "failed", "unknown", "unread", "withdrawn", "notPublished")) counters.put(key, (long) rows.get(key).size());
            counters.put("tasks", (long) taskViews.size());
            return Map.copyOf(counters);
        }
        private Metric metric(Definition definition) {
            List<Row> values = rows.get(definition.code());
            Long denominator = switch (definition.code()) {
                case "tagCoverage" -> (long) rows.get("effectivePeople").size();
                case "deliveryRate" -> (long) rows.get("submitted").size();
                case "readRateAmongDelivered" -> (long) rows.get("delivered").size();
                case "readCoverageAmongTargets" -> (long) rows.get("targetRecipients").size();
                default -> null;
            };
            long missing = definition.code().startsWith("read") ? unknownRead : definition.code().equals("tagCoverage") ? counters.getOrDefault("tagsWithoutEvidence", 0L) : 0;
            if (Set.of("targetRecipients", "deliveryRate", "readRateAmongDelivered", "readCoverageAmongTargets", "overdueUnread").contains(definition.code())) {
                missing += counters.getOrDefault(definition.code().equals("overdueUnread") ? "missingOverdueTagSnapshots" : "missingTagSnapshots", 0L);
            }
            if (definition.code().equals("deliveryRate") || definition.code().equals("readRateAmongDelivered")) missing += unknownDelivery;
            Double value = missing > 0 || denominator != null && denominator == 0 ? null : denominator == null ? (double) values.size() : 100.0 * values.size() / denominator;
            return new Metric(definition.code(), value, values.size(), denominator, definition.grain(), definition.text(),
                    missing > 0 ? "UNKNOWN" : denominator != null && denominator == 0 ? "NOT_APPLICABLE" : "RECORDED", missing, definition.organizationBasis());
        }
        private List<Bar> bars(String code) {
            return rows.get(code).stream().collect(Collectors.groupingBy(Row::group)).entrySet().stream().map(entry -> {
                var values = entry.getValue();
                return new Bar(entry.getKey(), code.equals("organizations") ? values.getFirst().organization() : values.getFirst().label(), values.size(),
                        values.stream().map(Row::personId).filter(id -> !id.isEmpty()).distinct().count());
            }).sorted(Comparator.comparingLong(Bar::count).reversed().thenComparing(Bar::id)).toList();
        }
        private List<TaskView> taskViews() { return taskViews.values().stream().sorted(Comparator.comparing(TaskView::firstPublishedAt).reversed()).toList(); }
        private String scopeLabel() { return actor.role().equals("SUPER_ADMIN") ? "全市授权范围" : actor.role().equals("AREA_ADMIN") ? "片区授权范围" : organizationName(actor.organizationId()) + "及下级"; }
        private String mode() {
            Set<String> modes = rows.values().stream().flatMap(Collection::stream).map(Row::mode).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
            return modes.isEmpty() ? counters.entrySet().stream().anyMatch(entry -> (entry.getKey().startsWith("missing") || entry.getKey().equals("tagsWithoutEvidence")) && entry.getValue() > 0) ? "UNVERIFIED" : "EMPTY" : modes.equals(Set.of("mock")) ? "MOCK_RECORDS" : modes.contains("mock") ? "MIXED" : "BUSINESS_RECORDS";
        }
        private Map<String, List<Option>> options() {
            return Map.of("organizations", facts("Organization").values().stream().filter(o -> scope.contains(o.id())).map(o -> new Option(o.id(), o.text("name"))).sorted(Comparator.comparing(Option::label)).toList(),
                    "tags", facts("TagDefinition").values().stream().map(t -> new Option(t.id(), tagPath(t.id()))).sorted(Comparator.comparing(Option::label)).toList(),
                    "tagVersions", facts("TagVersion").values().stream().filter(t -> from("TagParent", t.text("tagDefinitionId")).isEmpty()).map(t -> new Option(t.id(), t.text("nameSnapshot") + " · 版本 " + t.text("version"))).toList(),
                    "creatorOrganizations", facts("Organization").values().stream().filter(org -> scope.contains(org.id()) || org.id().equals(actor.organizationId()) || visibleTaskIds.stream().anyMatch(id -> org.id().equals(fact("ReminderTask", id).text("organizationId")))).map(org -> new Option(org.id(), org.text("name"))).sorted(Comparator.comparing(Option::label)).toList(),
                    "tasks", facts("ReminderTask").values().stream().filter(task -> visibleTaskIds.contains(task.id())).map(t -> new Option(t.id(), t.text("title"))).toList(),
                    "categories", facts("ReminderTask").values().stream().filter(task -> visibleTaskIds.contains(task.id())).map(this::effectiveVersion).filter(java.util.Objects::nonNull)
                            .map(v -> v.text("categorySnapshot")).distinct().sorted().map(s -> new Option(s, s)).toList());
        }
        private Map<String, List<Row>> freezeRows() {
            return rows.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        }
    }

    private static Instant instant(String value) {
        try { return value == null || value.isEmpty() ? null : Instant.parse(value); }
        catch (java.time.format.DateTimeParseException invalid) { return null; }
    }

    record Filter(String organizationId, String creatorOrganization, String recipientOrganization, String tagId, String tagVersionId,
                  String category, String taskId, Instant from, Instant to, boolean includeWithdrawn) {
        void validate() {
            for (String value : List.of(organizationId, creatorOrganization, recipientOrganization, tagId, tagVersionId, category, taskId)) {
                if (value.length() > 512) throw new IllegalArgumentException("Filter is too long");
            }
            if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("Invalid time range");
        }
        Map<String, Object> parameters() {
            return Map.of("organizationId", organizationId, "creatorOrganization", creatorOrganization, "recipientOrganization", recipientOrganization,
                    "tagId", tagId, "tagVersionId", tagVersionId, "category", category, "taskId", taskId,
                    "from", from == null ? "" : from.toString(), "to", to == null ? "" : to.toString(), "includeWithdrawn", includeWithdrawn);
        }
        static Filter all() { return new Filter("", "", "", "", "", "", "", null, null, true); }
    }
    record Definition(String code, String grain, String text, String organizationBasis) {}
    record Metric(String code, Double value, long numerator, Long denominator, String grain, String definition, String status, long missingInputs, String organizationBasis) {}
    record Row(String id, String personId, String name, String organization, String label, String state, String group, String mode, String time, String href) {}
    record Bar(String id, String label, long count, long people) {}
    record TaskView(String id, String title, String organization, int target, int read, String state, String firstPublishedAt) {}
    record Option(String id, String label) {}
    record Report(String snapshotId, String asOf, Filter filters, String scope, String dataMode, List<Metric> metrics, List<Bar> organizations,
                  List<Bar> tags, List<Bar> sources, List<Bar> pending, List<Bar> integration, List<TaskView> tasks,
                  Map<String, Long> counts, Map<String, List<Option>> options, List<String> notes) {}
    record Details(List<Row> items, int total, int page, int size, String asOf) {}
    private record Cached(Accounts.Actor actor, String authorization, String publication, Set<String> taskIds, Instant expiresAt, Report report, Map<String, List<Row>> rows) {
        long weight() { return rows.values().stream().mapToLong(List::size).sum(); }
    }
}
