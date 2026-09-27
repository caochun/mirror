package gov.objectlibrary.server;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;
import static gov.objectlibrary.server.ReminderService.revisionState;

/** An unpublished revision is independent of the task's delivery summary and of its currently visible content. */
@Service
class ReminderRevisionService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final ReminderService reminders;
    private final ReminderContentPolicy content;
    private final BusinessCommands commands;
    private final DomainContracts contracts;
    private final ReadingService reading;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    ReminderRevisionService(StorageProvider storage, DirectoryService directory, ReminderService reminders,
                            ReminderContentPolicy content, BusinessCommands commands, DomainContracts contracts,
                            ReadingService reading, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.storage = storage;
        this.directory = directory;
        this.reminders = reminders;
        this.content = content;
        this.commands = commands;
        this.contracts = contracts;
        this.reading = reading;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    Map<String, Object> save(Accounts.Actor actor, String taskId, Draft input, String key) {
        if (input.title() == null || input.title().isBlank() || input.title().length() > 120) throw new IllegalArgumentException("Invalid title");
        String action = "SaveReminderRevision";
        return commands.executeDefined(actor, action, key, List.of(taskId, input), () -> authorizeWrite(actor, taskId, action), tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, input.expectedVersion());
            requirePublishedTask(task);
            if (!Set.of("NONE", "DRAFT", "REJECTED", "WITHDRAWN").contains(revisionState(task))) throw new BusinessConflict("修订正在审核或等待发布");
            var base = required(actor, "ReminderTaskVersion", text(task, "currentPublishedVersionId"));
            var inspected = content.inspect(actor, input.bodyHtml());
            contracts.validateInputs(actor, action, Map.of("taskId", taskId, "expectedVersion", task.version(),
                    "title", input.title(), "body", Map.of("html", inspected.html())));
            int sequence = ((Number) task.properties().get("draftSequence")).intValue() + 1;
            String versionId = taskId + "-v" + sequence;
            String digest = BusinessCommands.hash(encode(List.of(base.id(), input.title().strip(), inspected.html(), inspected.mediaDigest())));
            var properties = new HashMap<>(base.properties());
            properties.putAll(values("state", "DRAFT", "version", Integer.toString(sequence), "versionKind", "REVISION",
                    "basePublishedVersionId", base.id(), "titleSnapshot", input.title().strip(), "bodySnapshot", inspected.html(),
                    "contentDigest", digest, "mediaManifestDigest", inspected.mediaDigest(), "publishedAt", null, "submittedAt", null,
                    "snapshotHash", null, "linksSnapshotJson", encode(inspected.links())));
            var version = tx.createObject("ReminderTaskVersion", versionId, properties);
            tx.createLink("TaskHasVersion", "version-" + versionId, task.key(), version.key(), Map.of());
            copyLinks(actor, tx, base, version, "VersionTargetsRecipient");
            copyLinks(actor, tx, base, version, "TaskVersionUsesTagVersion");
            copyLinks(actor, tx, base, version, "TaskVersionFromContent");
            for (String id : inspected.images().stream().map(ReminderContentPolicy.ImageReference::id).distinct().toList()) {
                tx.createLink("TaskVersionUsesMedia", versionId + "-" + id, version.key(), new EntityKey("MediaAsset", id), Map.of());
            }
            contracts.requireTransition(task.type(), "revisionState", revisionState(task), "DRAFT", action);
            var changed = tx.updateObject(task.type(), taskId, values("revisionState", "DRAFT", "pendingVersionId", versionId,
                    "draftSequence", sequence, "contentCheckId", null), task.version());
            return values("id", taskId, "version", changed.version(), "revisionState", "DRAFT", "contentDigest", digest);
        }, contracts.eventType(action));
    }

    Map<String, Object> confirm(Accounts.Actor actor, String taskId, Confirmation input, String key) {
        String action = "ConfirmReminderContent";
        return commands.executeDefined(actor, action, key, List.of(taskId, input), () -> {
            authorizeWrite(actor, taskId, "SaveReminderRevision");
            contracts.authorize(actor, action);
        }, tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, input.expectedVersion());
            requireRevision(task, "DRAFT");
            var version = required(actor, "ReminderTaskVersion", text(task, "pendingVersionId"));
            requireBase(task, version);
            if (!input.contentAcknowledged() || !text(version, "contentDigest").equals(input.contentDigest())) throw new BusinessConflict("请确认当前修订内容");
            var inspected = content.inspect(actor, text(version, "bodySnapshot"));
            var expected = inspected.images().stream().map(ReminderContentPolicy.ImageReference::confirmationKey).toList();
            if (input.confirmedMediaDigests() == null || expected.size() != input.confirmedMediaDigests().size()
                    || !Set.copyOf(expected).equals(Set.copyOf(input.confirmedMediaDigests()))) throw new BusinessConflict("请逐张确认当前修订的全部图片");
            contracts.validateInputs(actor, action, values("versionId", version.id(), "expectedVersion", version.version(),
                    "contentDigest", input.contentDigest(), "selectionDigest", text(version, "selectionDigest"),
                    "confirmedMediaDigests", input.confirmedMediaDigests(), "duplicateAcknowledged", true));
            String checkId = "revision-check-" + UUID.randomUUID();
            var check = tx.createObject("ContentSafetyCheck", checkId, values("state", "CONFIRMED", "contentDigest", input.contentDigest(),
                    "selectionDigest", text(version, "selectionDigest"), "mediaDigest", inspected.mediaDigest(), "linksJson", encode(inspected.links()),
                    "duplicateRecipientCount", 0, "confirmedBy", actor.username(), "confirmedAt", clock.instant().toString()));
            tx.createLink("SafetyCheckForVersion", "version-" + checkId, check.key(), version.key(), Map.of());
            for (var image : inspected.images()) {
                String id = checkId + "-image-" + image.index();
                var confirmed = tx.createObject("MediaConfirmation", id, values("mediaDigest", image.digest(), "confirmedBy", actor.username(),
                        "confirmedAt", clock.instant().toString(), "assertion", "不含身份证号、内部标签、预警原文和台账信息"));
                tx.createLink("MediaConfirmationForCheck", "check-" + id, confirmed.key(), check.key(), Map.of());
                tx.createLink("MediaConfirmationForAsset", "asset-" + id, confirmed.key(), new EntityKey("MediaAsset", image.id()), Map.of());
            }
            var changed = tx.updateObject(task.type(), task.id(), Map.of("contentCheckId", checkId), task.version());
            return values("id", taskId, "version", changed.version(), "contentCheckId", checkId);
        }, contracts.eventType(action));
    }

    Map<String, Object> submit(Accounts.Actor actor, String taskId, long expectedVersion, String key) {
        String action = "SubmitReminderRevision";
        return commands.executeDefined(actor, action, key, List.of(taskId, expectedVersion), () -> authorizeWrite(actor, taskId, action), tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, expectedVersion);
            requireRevision(task, "DRAFT");
            if (!reminders.hasReviewer(actor, task)) throw new BusinessConflict("没有本级独立审核员");
            var version = required(actor, "ReminderTaskVersion", text(task, "pendingVersionId"));
            requireBase(task, version);
            var check = required(actor, "ContentSafetyCheck", text(task, "contentCheckId"));
            var inspected = content.inspect(actor, text(version, "bodySnapshot"));
            if (!text(check, "contentDigest").equals(text(version, "contentDigest"))
                    || !text(check, "selectionDigest").equals(text(version, "selectionDigest"))
                    || !text(check, "mediaDigest").equals(inspected.mediaDigest())) throw new BusinessConflict("修订确认已失效");
            contracts.validateInputs(actor, action, Map.of("taskId", taskId, "expectedVersion", task.version(),
                    "title", text(version, "titleSnapshot"), "body", Map.of("html", inspected.html()),
                    "mediaIds", inspected.images().stream().map(ReminderContentPolicy.ImageReference::id).distinct().toList(), "contentCheckId", check.id()));
            int roundNumber = ((Number) task.properties().get("reviewRound")).intValue() + 1;
            String roundId = taskId + "-review-" + roundNumber;
            String digest = BusinessCommands.hash(text(version, "contentDigest") + text(version, "selectionDigest"));
            var round = tx.createObject("ReviewRound", roundId, values("taskId", taskId, "versionId", version.id(), "organizationId", actor.organizationId(),
                    "roundNumber", roundNumber, "state", "PENDING", "submittedBy", actor.username(), "submittedAt", clock.instant().toString(), "snapshotDigest", digest));
            tx.createLink("ReviewForTask", "task-" + roundId, round.key(), task.key(), Map.of());
            tx.createLink("ReviewForVersion", "version-" + roundId, round.key(), version.key(), Map.of());
            tx.createLink("ReviewOwnedByOrganization", "org-" + roundId, round.key(), new EntityKey("Organization", actor.organizationId()), Map.of());
            tx.createLink("ReviewSubmittedBy", "submitter-" + roundId, round.key(), reminders.principalReference(actor, tx), Map.of());
            contracts.requireTransition(version.type(), "state", text(version, "state"), "FROZEN", action);
            tx.updateObject(version.type(), version.id(), values("state", "FROZEN", "snapshotHash", digest, "submittedAt", clock.instant().toString()), version.version());
            contracts.requireTransition(task.type(), "revisionState", "DRAFT", "PENDING_REVIEW", action);
            var changed = tx.updateObject(task.type(), task.id(), values("revisionState", "PENDING_REVIEW", "reviewRound", roundNumber,
                    "activeReviewId", roundId, "submittedBy", actor.username()), task.version());
            return values("id", taskId, "version", changed.version(), "revisionState", "PENDING_REVIEW");
        }, contracts.eventType(action));
    }

    Map<String, Object> decide(Accounts.Actor actor, String taskId, ReminderService.Decision input, String key) {
        String action = "DecideReminderReview";
        return commands.executeDefined(actor, action, key, List.of(taskId, input), () -> {
            contracts.authorize(actor, action);
            var task = reminders.getTaskForOperator(actor, taskId, false);
            if (!actor.organizationId().equals(text(task, "organizationId")) || actor.username().equals(text(task, "createdBy"))
                    || actor.username().equals(text(task, "submittedBy"))) throw new AccessDeniedException("须由本级独立审核员处理");
            if (!text(required(actor, "Organization", actor.organizationId()), "status").equals("ACTIVE")) throw new BusinessConflict("审核单位已停用");
        }, tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, input.expectedVersion());
            requireRevision(task, "PENDING_REVIEW");
            if (input.decision() == null || !Set.of("APPROVE", "REJECT").contains(input.decision()) || input.comment() == null || input.comment().length() > 1000
                    || input.decision().equals("REJECT") && input.comment().isBlank()) throw new IllegalArgumentException("Invalid decision");
            var version = required(actor, "ReminderTaskVersion", text(task, "pendingVersionId"));
            var round = required(actor, "ReviewRound", text(task, "activeReviewId"));
            requireBase(task, version);
            if (!text(round, "state").equals("PENDING") || !text(round, "versionId").equals(version.id())
                    || !text(round, "snapshotDigest").equals(text(version, "snapshotHash"))) throw new BusinessConflict("审核快照已变化");
            contracts.validateInputs(actor, action, values("roundId", round.id(), "expectedVersion", round.version(), "decision", input.decision(), "comment", input.comment()));
            String state = input.decision().equals("APPROVE") ? "APPROVED" : "REJECTED";
            contracts.requireTransition(round.type(), "state", "PENDING", state, action);
            tx.updateObject(round.type(), round.id(), values("state", state, "decidedBy", actor.username(), "decidedAt", clock.instant().toString(), "comment", input.comment()), round.version());
            tx.createLink("ReviewDecidedBy", "decider-" + round.id(), round.key(), reminders.principalReference(actor, tx), Map.of());
            if (state.equals("APPROVED")) {
                contracts.requireTransition(version.type(), "state", "FROZEN", "APPROVED", action);
                tx.updateObject(version.type(), version.id(), Map.of("state", "APPROVED"), version.version());
            }
            contracts.requireTransition(task.type(), "revisionState", "PENDING_REVIEW", state, action);
            var changed = tx.updateObject(task.type(), task.id(), values("revisionState", state, "reviewComment", input.comment(),
                    "reviewedBy", actor.username()), task.version());
            return values("id", taskId, "version", changed.version(), "revisionState", state);
        }, contracts.eventType(action));
    }

    Map<String, Object> withdrawReview(Accounts.Actor actor, String taskId, long expectedVersion, String key) {
        String action = "WithdrawReminderReview";
        return commands.executeDefined(actor, action, key, List.of(taskId, expectedVersion), () -> {
            authorizeWrite(actor, taskId, "SubmitReminderRevision");
            contracts.authorize(actor, action);
        }, tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, expectedVersion);
            requireRevision(task, "PENDING_REVIEW");
            var round = required(actor, "ReviewRound", text(task, "activeReviewId"));
            contracts.validateInputs(actor, action, Map.of("roundId", round.id(), "expectedVersion", round.version()));
            contracts.requireTransition(round.type(), "state", text(round, "state"), "WITHDRAWN", action);
            tx.updateObject(round.type(), round.id(), values("state", "WITHDRAWN", "decidedBy", actor.username(), "decidedAt", clock.instant().toString()), round.version());
            contracts.requireTransition(task.type(), "revisionState", "PENDING_REVIEW", "WITHDRAWN", action);
            var changed = tx.updateObject(task.type(), task.id(), values("revisionState", "WITHDRAWN", "contentCheckId", null), task.version());
            return values("id", taskId, "version", changed.version(), "revisionState", "WITHDRAWN");
        }, contracts.eventType(action));
    }

    int publishApproved() {
        int published = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var worker = new Accounts.Actor("__publication_worker__", "修订发布", tenant, "", "SYSTEM");
            for (var task : directory.all(worker, "ReminderTask")) {
                if (!revisionState(task).equals("APPROVED")) continue;
                try {
                    publish(worker, task.id(), text(task, "pendingVersionId"));
                    published++;
                } catch (BusinessConflict conflict) {
                    // A concurrent workflow won; reload the persisted approved revision on the next scan.
                }
            }
        }
        if (published > 0) reading.evaluateDue();
        return published;
    }

    private void publish(Accounts.Actor worker, String taskId, String versionId) {
        String action = "PublishReminderRevision";
        commands.executeDefinedWithRetry(worker, action, "publish-" + BusinessCommands.hash(versionId), List.of(taskId, versionId),
                () -> contracts.authorizeSystem(action), tx -> {
            var task = required(worker, "ReminderTask", taskId);
            requireRevision(task, "APPROVED");
            if (!text(task, "pendingVersionId").equals(versionId)) throw new BusinessConflict("待发布修订已变化");
            var version = required(worker, "ReminderTaskVersion", versionId);
            requireBase(task, version);
            var old = required(worker, "ReminderTaskVersion", text(task, "currentPublishedVersionId"));
            var round = required(worker, "ReviewRound", text(task, "activeReviewId"));
            if (!text(version, "state").equals("APPROVED") || !text(round, "state").equals("APPROVED")
                    || !text(round, "versionId").equals(versionId) || !text(round, "snapshotDigest").equals(text(version, "snapshotHash"))) {
                throw new BusinessConflict("没有匹配的修订审核快照");
            }
            contracts.validateInputs(worker, action, Map.of("roundId", round.id(), "versionId", versionId,
                    "expectedTaskVersion", task.version(), "publishedAt", clock.instant().toString()));
            contracts.requireTransition(old.type(), "state", "PUBLISHED", "SUPERSEDED", action);
            contracts.requireTransition(version.type(), "state", "APPROVED", "PUBLISHED", action);
            tx.updateObject(old.type(), old.id(), Map.of("state", "SUPERSEDED"), old.version());
            tx.updateObject(version.type(), version.id(), values("state", "PUBLISHED", "publishedAt", clock.instant().toString()), version.version());
            for (String id : roster(worker, version)) {
                String stateId = DeliveryService.readingStateId(id, versionId);
                var state = tx.createObject("RecipientVersionState", stateId, values("state", "UNREAD", "publishedAt", clock.instant().toString(), "updatedAt", clock.instant().toString()));
                tx.createLink("VersionStateForRecipient", "recipient-" + stateId, state.key(), new EntityKey("RecipientRecord", id), Map.of());
                tx.createLink("VersionStateForVersion", "version-" + stateId, state.key(), version.key(), Map.of());
                var overdue = storage.getObject(worker.context(), "OverdueRecord", ReadingService.overdueId(id, old.id()));
                if (overdue != null && text(overdue, "state").equals("OPEN")) {
                    contracts.requireTransition(overdue.type(), "state", "OPEN", "CLOSED", action);
                    tx.updateObject(overdue.type(), overdue.id(), values("state", "CLOSED", "closedReason", "SUPERSEDED", "resolvedAt", clock.instant().toString()), overdue.version());
                }
            }
            contracts.requireTransition(task.type(), "revisionState", "APPROVED", "NONE", action);
            tx.updateObject(task.type(), task.id(), values("currentPublishedVersionId", versionId, "revisionState", "NONE",
                    "title", text(version, "titleSnapshot"), "contentCheckId", null), task.version());
            return Map.of("taskId", taskId, "publishedVersionId", versionId);
        }, contracts.eventType(action));
    }

    private void requireBase(ObjectRecord task, ObjectRecord version) {
        requirePublishedTask(task);
        if (!text(version, "versionKind").equals("REVISION") || !text(version, "basePublishedVersionId").equals(text(task, "currentPublishedVersionId"))) {
            throw new BusinessConflict("基准发布版已变化，请重新创建修订");
        }
        var worker = new Accounts.Actor("__revision_check__", "修订校验", task.tenantId(), "", "SYSTEM");
        var base = required(worker, "ReminderTaskVersion", text(task, "currentPublishedVersionId"));
        if (!roster(worker, base).equals(roster(worker, version)) || !text(base, "readingWindow").equals(text(version, "readingWindow"))
                || !text(base, "categorySnapshot").equals(text(version, "categorySnapshot"))) throw new BusinessConflict("修订不能改变原名单、分类或阅读时限");
    }

    private Set<String> roster(Accounts.Actor actor, ObjectRecord version) {
        return directory.links(actor, version.key(), "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND).stream()
                .map(link -> link.to().id()).collect(java.util.stream.Collectors.toSet());
    }

    private void copyLinks(Accounts.Actor actor, Transaction tx, ObjectRecord from, ObjectRecord to, String type) {
        for (var link : directory.links(actor, from.key(), type, StorageProvider.Direction.OUTBOUND)) {
            tx.createLink(type, to.id() + "-" + type + "-" + link.to().id(), to.key(), link.to(), link.properties());
        }
    }

    private void authorizeWrite(Accounts.Actor actor, String taskId, String action) {
        contracts.authorize(actor, action);
        reminders.getTaskForOperator(actor, taskId, true);
    }

    private void requireRevision(ObjectRecord task, String state) {
        requirePublishedTask(task);
        if (!revisionState(task).equals(state)) throw new BusinessConflict("当前修订状态不允许操作");
    }

    private void requirePublishedTask(ObjectRecord task) {
        if (text(task, "currentPublishedVersionId").isEmpty() || !Set.of("SENDING", "ALL_SUCCESS", "PARTIAL_FAILED", "ALL_FAILED",
                "PARTIAL_WITHDRAWN", "WITHDRAW_FAILED").contains(text(task, "state"))) {
            throw new BusinessConflict("当前任务不允许内容修订");
        }
    }

    private ObjectRecord required(Accounts.Actor actor, String type, String id) {
        var object = storage.getObject(actor.context(), type, id);
        if (object == null || object.isDeleted()) throw new BusinessConflict("修订记录不存在");
        return object;
    }

    private void checkVersion(ObjectRecord object, long expected) {
        if (object.version() != expected) throw new BusinessConflict("任务已变化，请刷新后再操作");
    }

    private String encode(Object object) {
        try { return json.writeValueAsString(object); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid revision snapshot", invalid); }
    }

    record Draft(long expectedVersion, String title, String bodyHtml) {
        @JsonAnySetter public void rejectUnknown(String property, Object value) {
            throw new IllegalArgumentException("Revision cannot change " + property);
        }
    }
    record Confirmation(long expectedVersion, String contentDigest, boolean contentAcknowledged, List<String> confirmedMediaDigests) {}
}
