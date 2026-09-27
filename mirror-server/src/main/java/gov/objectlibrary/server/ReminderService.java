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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reminder preparation and independent review. Channel delivery runs in a separate worker. */
@Service
public class ReminderService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final Accounts accounts;
    private final BusinessCommands commands;
    private final DomainContracts contracts;
    private final ReminderContentPolicy content;
    private final ReminderSelection selections;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReminderService(StorageProvider storage, DirectoryService directory, Accounts accounts,
                           BusinessCommands commands, DomainContracts contracts, ReminderContentPolicy content,
                           ReminderSelection selections, ObjectMapper json, JdbcTemplate jdbc, Clock clock) {
        this.storage = storage;
        this.directory = directory;
        this.accounts = accounts;
        this.commands = commands;
        this.contracts = contracts;
        this.content = content;
        this.selections = selections;
        this.json = json;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<TaskView> list(Accounts.Actor actor) {
        accounts.requirePermission(actor, "REMINDER_READ");
        return directory.all(actor, "ReminderTask").stream().filter(t -> visible(actor, t))
                .sorted(java.util.Comparator.comparing(ObjectRecord::createdAt).reversed())
                .map(t -> view(actor, t)).toList();
    }

    public Detail detail(Accounts.Actor actor, String id) {
        accounts.requirePermission(actor, "REMINDER_READ");
        var task = visibleTask(actor, id);
        var version = required(actor, "ReminderTaskVersion", text(task, "pendingVersionId"));
        var selection = required(actor, "RecipientSelection", text(task, "selectionId"));
        var rounds = directory.links(actor, task.key(), "ReviewForTask", StorageProvider.Direction.INBOUND).stream()
                .map(l -> required(actor, "ReviewRound", l.from().id()))
                .sorted(java.util.Comparator.comparing(ObjectRecord::createdAt))
                .map(r -> new ReviewView(r.id(), r.version(), text(r, "state"), number(r, "roundNumber"),
                        text(r, "submittedBy"), text(r, "decidedBy"), text(r, "comment"), text(r, "snapshotDigest"))).toList();
        List<ReminderSelection.Entry> entries = decodeEntries(text(selection, "entriesJson"));
        if (!text(version, "recipientsJson").isEmpty() && !"DRAFT".equals(text(version, "state"))) {
            entries = decodeEntries(text(version, "recipientsJson"));
        }
        return new Detail(view(actor, task), text(version, "bodySnapshot"), text(version, "contentDigest"),
                decodeFilter(text(selection, "criteriaJson")), entries, rounds, text(task, "reviewComment"));
    }

    public ReminderSelection.Result preview(Accounts.Actor actor, ReminderSelection.Filter filter) {
        accounts.requirePermission(actor, "REMINDER_WRITE");
        return selections.resolve(actor, filter);
    }

    public Map<String, Object> save(Accounts.Actor actor, String taskId, Draft input, String key) {
        String action = "SaveReminderDraft";
        validateDraft(input);
        String id = taskId == null ? "task-" + UUID.randomUUID() : taskId;
        Runnable authorize = () -> {
            contracts.authorize(actor, action);
            requireActiveOrganization(actor);
            if (taskId != null) requireWrite(actor, visibleTask(actor, id));
        };
        return commands.executeDefined(actor, action, key, List.of(taskId == null ? "new" : taskId, input), authorize, tx -> {
            var previous = taskId == null ? null : required(actor, "ReminderTask", id);
            if (previous == null && input.expectedVersion() != 0) throw new BusinessConflict("新任务版本必须为0");
            if (previous != null) {
                checkVersion(previous, input.expectedVersion());
                if (!Set.of("DRAFT", "REJECTED", "REVIEW_EXPIRED").contains(text(previous, "state"))) {
                    throw new BusinessConflict("待审核或已批准任务不可直接编辑");
                }
            }
            requireFuture(input.plannedAt());
            String body = content.clean(input.bodyHtml());
            var parameters = values("taskId", id, "expectedVersion", input.expectedVersion(), "title", input.title(),
                    "body", Map.of("html", body), "category", input.category(), "sendMode", input.plannedAt() == null ? "IMMEDIATE" : "SCHEDULED",
                    "plannedAt", input.plannedAt() == null ? null : input.plannedAt().toString(), "readingWindow", input.readingWindow());
            contracts.validateInputs(actor, action, parameters);
            var oldSelection = previous == null ? null : required(actor, "RecipientSelection", text(previous, "selectionId"));
            // Exclusions survive criteria edits; restoring them is a separate, explicit request field.
            var excluded = new java.util.TreeSet<>(input.filter().excludedIds());
            if (oldSelection != null) excluded.addAll(decodeFilter(text(oldSelection, "criteriaJson")).excludedIds());
            excluded.removeAll(input.restoreIds());
            var filter = new ReminderSelection.Filter(input.filter().organizationIds(), input.filter().tagIds(),
                    input.filter().tagOperator(), input.filter().personIds(), List.copyOf(excluded));
            if (oldSelection != null) {
                contracts.authorize(actor, "UpdateRecipientSelection");
                contracts.validateInputs(actor, "UpdateRecipientSelection", values("selectionId", oldSelection.id(),
                        "expectedVersion", oldSelection.version(), "organizationRoots", filter.organizationIds(),
                        "tagVersionIds", tagVersions(actor, filter.tagIds()), "tagOperator", filter.tagOperator(),
                        "explicitPersonIds", filter.personIds(), "excludedPersonIds", filter.excludedIds(), "restoredPersonIds", input.restoreIds()));
            }
            var resolved = selections.resolve(actor, filter);
            String digest = digest(resolved);
            String selectionId = previous == null ? "selection-" + id : text(previous, "selectionId");
            int sequence = previous == null ? 1 : number(previous, "draftSequence") + 1;
            String versionId = id + "-v" + sequence;
            var taskValues = values("state", "DRAFT", "title", input.title().strip(), "sendMode", input.plannedAt() == null ? "IMMEDIATE" : "SCHEDULED",
                    "plannedAt", input.plannedAt() == null ? null : input.plannedAt().toString(), "readingWindow", input.readingWindow(),
                    "pendingVersionId", versionId, "snapshotId", versionId, "selectionId", selectionId, "draftSequence", sequence,
                    "contentCheckId", null, "reviewComment", "");
            ObjectRecord task;
            if (previous == null) {
                taskValues.putAll(values("createdBy", actor.username(), "organizationId", actor.organizationId(),
                        "createdAt", clock.instant().toString(), "reviewRound", 0));
                task = tx.createObject("ReminderTask", id, taskValues);
                tx.createLink("TaskCreatedByAccount", "creator-" + id, task.key(), principalReference(actor, tx), Map.of());
                tx.createLink("TaskOwnedByOrganization", "owner-" + id, task.key(), new EntityKey("Organization", actor.organizationId()), Map.of());
            } else {
                contracts.requireTransition("ReminderTask", "state", text(previous, "state"), "DRAFT", action);
                task = tx.updateObject("ReminderTask", id, taskValues, previous.version());
            }
            String contentDigest = BusinessCommands.hash(encode(List.of(input.title().strip(), body, input.category(), input.readingWindow(),
                    input.plannedAt() == null ? "IMMEDIATE" : input.plannedAt().toString())));
            var version = tx.createObject("ReminderTaskVersion", versionId, values("taskId", id, "version", String.valueOf(sequence),
                    "state", "DRAFT", "titleSnapshot", input.title().strip(), "bodySnapshot", body, "readingWindow", input.readingWindow(),
                    "categorySnapshot", input.category(), "contentDigest", contentDigest, "versionKind", "INITIAL",
                    "plannedAt", input.plannedAt() == null ? null : input.plannedAt().toString(), "sendMode", taskValues.get("sendMode")));
            tx.createLink("TaskHasVersion", "link-" + versionId, task.key(), version.key(), Map.of());
            var selectionValues = values("state", "DRAFT", "criteriaJson", encode(filter), "entriesJson", encode(resolved.entries()),
                    "organizationRootsJson", encode(filter.organizationIds()), "tagVersionIdsJson", encode(tagVersions(actor, filter.tagIds())),
                    "tagOperator", filter.tagOperator(), "criteriaDigest", digest, "revision", sequence,
                    "confirmedDigest", null, "confirmedAt", null, "confirmedBy", null);
            if (oldSelection == null) {
                var selection = tx.createObject("RecipientSelection", selectionId, selectionValues);
                tx.createLink("TaskHasSelection", "task-" + selectionId, task.key(), selection.key(), Map.of());
            } else tx.updateObject("RecipientSelection", selectionId, selectionValues, oldSelection.version());
            saveEntries(actor, tx, selectionId, resolved.entries());
            return values("id", id, "version", task.version(), "state", "DRAFT", "selectionDigest", digest,
                    "contentDigest", contentDigest, "recipientCount", resolved.included().size(), "contractDigest", contracts.digest());
        }, contracts.eventType(action));
    }

    public Map<String, Object> confirm(Accounts.Actor actor, String taskId, Confirmation input, String key) {
        String action = "ConfirmRecipientSelection";
        return commands.executeDefined(actor, action, key, List.of(taskId, input), () -> authorizeWrite(actor, taskId, action), tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, input.expectedVersion());
            requireState(task, "DRAFT");
            var selection = required(actor, "RecipientSelection", text(task, "selectionId"));
            var resolved = currentSelection(actor, selection);
            requireConfirmedInput(selection, resolved, input.selectionDigest(), input.recipientCount());
            if (resolved.included().size() == 1 && !input.singleRecipientAcknowledged()) throw new BusinessConflict("请明确确认向1人定向提醒");
            contracts.validateInputs(actor, action, values("selectionId", selection.id(), "expectedVersion", selection.version(),
                    "digest", input.selectionDigest(), "count", input.recipientCount(), "singleRecipientAcknowledged", input.singleRecipientAcknowledged()));
            contracts.requireTransition("RecipientSelection", "state", text(selection, "state"), "CONFIRMED", action);
            var version = required(actor, "ReminderTaskVersion", text(task, "pendingVersionId"));
            requireState(version, "DRAFT");
            if (!text(version, "contentDigest").equals(input.contentDigest()) || !input.contentAcknowledged()) {
                throw new BusinessConflict("请预览并确认当前最终内容");
            }
            contracts.authorize(actor, "ConfirmReminderContent");
            contracts.validateInputs(actor, "ConfirmReminderContent", values("versionId", version.id(),
                    "expectedVersion", version.version(), "contentDigest", input.contentDigest(),
                    "selectionDigest", input.selectionDigest(), "confirmedMediaDigests", List.of(),
                    "duplicateAcknowledged", input.duplicateAcknowledged()));
            int duplicates = duplicateRecipients(actor, taskId, text(version, "contentDigest"), resolved.included());
            if (duplicates > 0 && !input.duplicateAcknowledged()) throw new BusinessConflict("发现重复提醒对象，请核对后明确确认");
            tx.updateObject(selection.type(), selection.id(), values("state", "CONFIRMED", "confirmedDigest", input.selectionDigest(),
                    "confirmedAt", clock.instant().toString(), "confirmedBy", actor.username()), selection.version());
            String checkId = "check-" + UUID.randomUUID();
            var check = tx.createObject("ContentSafetyCheck", checkId, values("state", "CONFIRMED", "contentDigest", input.contentDigest(),
                    "selectionDigest", input.selectionDigest(), "mediaDigest", BusinessCommands.hash("[]"), "linksJson", encode(org.jsoup.Jsoup.parseBodyFragment(text(version, "bodySnapshot")).select("a[href]").eachAttr("href")),
                    "duplicateRecipientCount", duplicates, "confirmedBy", actor.username(), "confirmedAt", clock.instant().toString()));
            tx.createLink("SafetyCheckForVersion", "version-" + checkId, check.key(), version.key(), Map.of());
            var changed = tx.updateObject(task.type(), task.id(), Map.of("contentCheckId", checkId), task.version());
            return values("id", taskId, "version", changed.version(), "state", "DRAFT", "contentCheckId", checkId,
                    "duplicateRecipientCount", duplicates, "actionsApplied", List.of(action, "ConfirmReminderContent"));
        }, contracts.eventType(action));
    }

    public Map<String, Object> submit(Accounts.Actor actor, String taskId, long expectedVersion, String key) {
        String action = "SubmitReminderReview";
        return commands.executeDefined(actor, action, key, List.of(taskId, expectedVersion), () -> authorizeWrite(actor, taskId, action), tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, expectedVersion);
            requireState(task, "DRAFT");
            requireFuture(instant(task, "plannedAt"));
            if (!actor.organizationId().equals(text(task, "organizationId"))) throw new BusinessConflict("当前单位已改变，请在原单位上下文重新提交");
            if (!hasReviewer(actor, task)) throw new BusinessConflict("创建单位没有可用的独立审核员，不能提交");
            var selection = required(actor, "RecipientSelection", text(task, "selectionId"));
            var resolved = currentSelection(actor, selection);
            if (!"CONFIRMED".equals(text(selection, "state"))) throw new BusinessConflict("请先确认接收名单和内容");
            requireConfirmedInput(selection, resolved, text(selection, "confirmedDigest"), resolved.included().size());
            var version = required(actor, "ReminderTaskVersion", text(task, "pendingVersionId"));
            var check = required(actor, "ContentSafetyCheck", text(task, "contentCheckId"));
            if (!text(check, "contentDigest").equals(text(version, "contentDigest"))
                    || !text(check, "selectionDigest").equals(digest(resolved))) throw new BusinessConflict("内容或名单确认已失效");
            content.clean(text(version, "bodySnapshot"));
            contracts.validateInputs(actor, action, values("taskId", taskId, "expectedVersion", expectedVersion,
                    "selectionDigest", digest(resolved), "contentCheckId", check.id()));
            int roundNumber = number(task, "reviewRound") + 1;
            String roundId = taskId + "-review-" + roundNumber;
            String snapshotDigest = BusinessCommands.hash(text(version, "contentDigest") + digest(resolved));
            contracts.requireTransition("ReminderTaskVersion", "state", text(version, "state"), "FROZEN", action);
            contracts.requireTransition("RecipientSelection", "state", text(selection, "state"), "FROZEN", action);
            tx.updateObject(version.type(), version.id(), values("state", "FROZEN", "submittedAt", clock.instant().toString(),
                    "recipientsJson", encode(resolved.included()), "selectionSnapshotJson", encode(resolved.filter()),
                    "selectionDigest", digest(resolved), "snapshotHash", snapshotDigest,
                    "linksSnapshotJson", text(check, "linksJson")), version.version());
            tx.updateObject(selection.type(), selection.id(), Map.of("state", "FROZEN"), selection.version());
            var round = tx.createObject("ReviewRound", roundId, values("roundNumber", roundNumber, "state", "PENDING",
                    "taskId", taskId, "versionId", version.id(), "organizationId", actor.organizationId(), "submittedBy", actor.username(),
                    "submittedAt", clock.instant().toString(), "snapshotDigest", snapshotDigest));
            tx.createLink("ReviewSubmittedBy", "submitter-" + roundId, round.key(), principalReference(actor, tx), Map.of());
            tx.createLink("ReviewForTask", "task-" + roundId, round.key(), task.key(), Map.of());
            tx.createLink("ReviewForVersion", "version-" + roundId, round.key(), version.key(), Map.of());
            tx.createLink("ReviewOwnedByOrganization", "org-" + roundId, round.key(), new EntityKey("Organization", actor.organizationId()), Map.of());
            for (var person : resolved.included()) {
                String recipientId = "recipient-" + BusinessCommands.hash(taskId + "/" + person.personId());
                var existing = storage.getObject(actor.context(), "RecipientRecord", recipientId);
                EntityKey recipientKey = new EntityKey("RecipientRecord", recipientId);
                if (existing == null) {
                    var recipient = tx.createObject("RecipientRecord", recipientId, values("taskId", taskId, "personId", person.personId(),
                            "deliveryState", "PENDING", "withdrawalState", "NONE", "personNameSnapshot", person.name(),
                            "organizationIdSnapshot", person.organizationId(), "organizationNameSnapshot", person.organizationName(),
                            "readingWindow", text(task, "readingWindow"), "eligibilitySnapshotJson", encode(person)));
                    tx.createLink("TaskHasRecipient", "task-" + recipientId, task.key(), recipient.key(), Map.of());
                    tx.createLink("RecipientForPerson", "person-" + recipientId, recipient.key(), new EntityKey("Person", person.personId()), Map.of());
                } else {
                    if (!text(existing, "firstDeliveredAt").isEmpty()) throw new BusinessConflict("已发送任务只能通过内容修订流程更新");
                    tx.updateObject(existing.type(), existing.id(), values("personNameSnapshot", person.name(),
                            "organizationIdSnapshot", person.organizationId(), "organizationNameSnapshot", person.organizationName(),
                            "readingWindow", text(task, "readingWindow"), "eligibilitySnapshotJson", encode(person)), existing.version());
                }
                tx.createLink("VersionTargetsRecipient", version.id() + "-" + recipientId, version.key(), recipientKey, Map.of());
            }
            contracts.requireTransition("ReminderTask", "state", text(task, "state"), "PENDING_REVIEW", action);
            var changed = tx.updateObject(task.type(), task.id(), values("state", "PENDING_REVIEW", "reviewRound", roundNumber,
                    "activeReviewId", roundId, "submittedBy", actor.username()), task.version());
            return values("id", taskId, "version", changed.version(), "state", "PENDING_REVIEW", "roundId", roundId,
                    "recipientCount", resolved.included().size(), "snapshotDigest", snapshotDigest);
        }, contracts.eventType(action));
    }

    public Map<String, Object> decide(Accounts.Actor actor, String taskId, Decision input, String key) {
        String action = "DecideReminderReview";
        return commands.executeDefined(actor, action, key, List.of(taskId, input), () -> {
            contracts.authorize(actor, action);
            requireActiveOrganization(actor);
            var task = visibleTask(actor, taskId);
            if (!actor.organizationId().equals(text(task, "organizationId"))) throw new AccessDeniedException("只允许本单位审核");
            if (actor.username().equals(text(task, "createdBy")) || actor.username().equals(text(task, "submittedBy"))) {
                throw new AccessDeniedException("不能审核本人创建或提交的任务");
            }
        }, tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, input.expectedVersion());
            requireState(task, "PENDING_REVIEW");
            requireFuture(instant(task, "plannedAt"));
            if (input.decision() == null || !Set.of("APPROVE", "REJECT").contains(input.decision()) || input.comment() == null
                    || input.comment().length() > 1000 || (input.decision().equals("REJECT") && input.comment().isBlank())) {
                throw new IllegalArgumentException("Invalid review decision");
            }
            var round = required(actor, "ReviewRound", text(task, "activeReviewId"));
            requireState(round, "PENDING");
            var version = required(actor, "ReminderTaskVersion", text(round, "versionId"));
            if (!text(round, "snapshotDigest").equals(text(version, "snapshotHash"))) throw new BusinessConflict("审核快照不一致");
            contracts.validateInputs(actor, action, values("roundId", round.id(), "expectedVersion", round.version(),
                    "decision", input.decision(), "comment", input.comment()));
            boolean approved = input.decision().equals("APPROVE");
            String roundState = approved ? "APPROVED" : "REJECTED";
            String taskState = approved ? "APPROVED_WAITING" : "REJECTED";
            contracts.requireTransition("ReviewRound", "state", "PENDING", roundState, action);
            contracts.requireTransition("ReminderTask", "state", "PENDING_REVIEW", taskState, action);
            tx.updateObject(round.type(), round.id(), values("state", roundState, "decidedBy", actor.username(),
                    "decidedAt", clock.instant().toString(), "comment", input.comment()), round.version());
            tx.createLink("ReviewDecidedBy", "decider-" + round.id(), round.key(), principalReference(actor, tx), Map.of());
            if (approved) {
                contracts.requireTransition("ReminderTaskVersion", "state", text(version, "state"), "APPROVED", action);
                tx.updateObject(version.type(), version.id(), Map.of("state", "APPROVED"), version.version());
                String jobId = "send-" + version.id();
                var job = tx.createObject("ReminderSendJob", jobId, values("taskId", taskId, "snapshotId", version.id(), "state", "QUEUED",
                        "dueAt", text(task, "plannedAt").isBlank() ? clock.instant().toString() : text(task, "plannedAt"),
                        "createdAt", clock.instant().toString()));
                tx.createLink("SendJobForTask", "task-" + jobId, job.key(), task.key(), Map.of());
                tx.createLink("SendJobForVersion", "version-" + jobId, job.key(), version.key(), Map.of());
            }
            var changed = tx.updateObject(task.type(), task.id(), values("state", taskState, "reviewedBy", actor.username(),
                    "reviewComment", input.comment()), task.version());
            return values("id", taskId, "version", changed.version(), "state", taskState, "roundId", round.id());
        }, contracts.eventType(action));
    }

    public Map<String, Object> withdrawReview(Accounts.Actor actor, String taskId, long expectedVersion, String key) {
        String action = "WithdrawReminderReview";
        return commands.executeDefined(actor, action, key, List.of(taskId, expectedVersion), () -> authorizeWrite(actor, taskId, action), tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, expectedVersion);
            requireState(task, "PENDING_REVIEW");
            var round = required(actor, "ReviewRound", text(task, "activeReviewId"));
            contracts.validateInputs(actor, action, values("roundId", round.id(), "expectedVersion", round.version()));
            contracts.requireTransition("ReviewRound", "state", text(round, "state"), "WITHDRAWN", action);
            tx.updateObject(round.type(), round.id(), values("state", "WITHDRAWN", "decidedBy", actor.username(),
                    "decidedAt", clock.instant().toString()), round.version());
            contracts.requireTransition("ReminderTask", "state", text(task, "state"), "DRAFT", action);
            var changed = tx.updateObject(task.type(), task.id(), values("state", "DRAFT", "contentCheckId", null), task.version());
            // Old frozen version is retained. Saving a new draft creates another version before resubmission.
            return values("id", taskId, "version", changed.version(), "state", "DRAFT");
        }, contracts.eventType(action));
    }

    public Map<String, Object> cancel(Accounts.Actor actor, String taskId, long expectedVersion, String reason, String key) {
        String action = "CancelScheduledReminder";
        return commands.executeDefined(actor, action, key, List.of(taskId, expectedVersion, reason), () -> authorizeWrite(actor, taskId, action), tx -> {
            var task = required(actor, "ReminderTask", taskId);
            checkVersion(task, expectedVersion);
            requireState(task, "APPROVED_WAITING");
            if (!"SCHEDULED".equals(text(task, "sendMode")) || reason.isBlank() || reason.length() > 1000) {
                throw new BusinessConflict("只有尚未发送的定时任务可填写原因取消");
            }
            var job = required(actor, "ReminderSendJob", "send-" + text(task, "pendingVersionId"));
            requireState(job, "QUEUED");
            contracts.validateInputs(actor, action, values("taskId", taskId, "expectedVersion", expectedVersion, "reason", reason));
            contracts.requireTransition("ReminderTask", "state", text(task, "state"), "CANCELLED", action);
            tx.updateObject(job.type(), job.id(), Map.of("state", "CANCELLED"), job.version());
            var changed = tx.updateObject(task.type(), task.id(), values("state", "CANCELLED", "cancelReason", reason), task.version());
            return values("id", taskId, "version", changed.version(), "state", "CANCELLED");
        }, contracts.eventType(action));
    }

    int expireDueReviews() {
        int expired = 0;
        String action = "ExpireReminderReview";
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var worker = new Accounts.Actor("__review_scheduler__", "审核到期调度", tenant, "", "SYSTEM");
            for (var pending : directory.all(worker, "ReminderTask")) {
                if (!"PENDING_REVIEW".equals(text(pending, "state")) || instant(pending, "plannedAt") == null
                        || instant(pending, "plannedAt").isAfter(clock.instant())) continue;
                String roundId = text(pending, "activeReviewId");
                try {
                    commands.executeDefined(worker, action, "expire-" + roundId, List.of(pending.id(), roundId),
                            () -> contracts.authorizeSystem(action), tx -> {
                        var task = required(worker, "ReminderTask", pending.id());
                        requireState(task, "PENDING_REVIEW");
                        if (instant(task, "plannedAt").isAfter(clock.instant())) throw new BusinessConflict("计划时间已改变");
                        var round = required(worker, "ReviewRound", roundId);
                        requireState(round, "PENDING");
                        contracts.validateInputs(worker, action, values("roundId", roundId, "expectedVersion", round.version(),
                                "evaluatedAt", clock.instant().toString()));
                        contracts.requireTransition("ReviewRound", "state", "PENDING", "EXPIRED", action);
                        tx.updateObject(round.type(), round.id(), values("state", "EXPIRED", "decidedAt", clock.instant().toString()), round.version());
                        tx.updateObject(task.type(), task.id(), Map.of("state", "REVIEW_EXPIRED"), task.version());
                        return values("taskId", task.id(), "roundId", roundId, "state", "REVIEW_EXPIRED");
                    }, contracts.eventType(action));
                    expired++;
                } catch (BusinessConflict conflict) {
                    // A simultaneous review/withdrawal won the version check; inspect again next sweep.
                }
            }
        }
        return expired;
    }

    private EntityKey principalReference(Accounts.Actor actor, Transaction tx) {
        var account = storage.getObject(actor.context(), "UserAccount", actor.username());
        if (account == null) {
            return tx.createObject("UserAccount", actor.username(), values("username", actor.username(),
                    "state", "ACTIVE", "changedAt", clock.instant().toString())).key();
        }
        return account.key();
    }

    private void saveEntries(Accounts.Actor actor, Transaction tx, String selectionId, List<ReminderSelection.Entry> entries) {
        Set<String> currentIds = entries.stream().map(e -> "entry-" + BusinessCommands.hash(selectionId + "/" + e.personId()))
                .collect(java.util.stream.Collectors.toSet());
        for (var link : directory.links(actor, new EntityKey("RecipientSelection", selectionId), "SelectionHasEntry", StorageProvider.Direction.OUTBOUND)) {
            if (!currentIds.contains(link.to().id())) {
                var stale = required(actor, "SelectionEntry", link.to().id());
                tx.updateObject(stale.type(), stale.id(), values("state", "EXCLUDED", "matchedByCondition", false,
                        "explicitlyIncluded", false, "exclusionReason", "不再命中当前条件"), stale.version());
            }
        }
        for (var entry : entries) {
            String id = "entry-" + BusinessCommands.hash(selectionId + "/" + entry.personId());
            var existing = storage.getObject(actor.context(), "SelectionEntry", id);
            var properties = values("state", entry.included() ? "INCLUDED" : entry.ineligibleReason().isEmpty() ? "EXCLUDED" : "INELIGIBLE",
                    "matchedByCondition", entry.matchedByCondition(), "explicitlyIncluded", entry.explicitlyIncluded(),
                    "manuallyExcluded", entry.manuallyExcluded(), "exclusionReason", entry.ineligibleReason(),
                    "sourceOrganizationId", entry.organizationId(), "eligibilityDigest", entry.eligibilityDigest(),
                    "confirmedPersonVersion", entry.personVersion());
            if (existing == null) {
                var created = tx.createObject("SelectionEntry", id, properties);
                tx.createLink("SelectionHasEntry", "selection-" + id, new EntityKey("RecipientSelection", selectionId), created.key(), Map.of());
                tx.createLink("SelectionEntryForPerson", "person-" + id, created.key(), new EntityKey("Person", entry.personId()), Map.of());
            } else tx.updateObject(existing.type(), existing.id(), properties, existing.version());
        }
    }

    private int duplicateRecipients(Accounts.Actor actor, String currentTaskId, String contentDigest, List<ReminderSelection.Entry> entries) {
        var personIds = entries.stream().map(ReminderSelection.Entry::personId).collect(java.util.stream.Collectors.toSet());
        var matchingTasks = directory.all(actor, "ReminderTaskVersion").stream()
                .filter(v -> contentDigest.equals(text(v, "contentDigest")) && !currentTaskId.equals(text(v, "taskId")))
                .map(v -> text(v, "taskId")).collect(java.util.stream.Collectors.toSet());
        return (int) directory.all(actor, "RecipientRecord").stream()
                .filter(r -> matchingTasks.contains(text(r, "taskId")) && personIds.contains(text(r, "personId"))
                        && "DELIVERED".equals(text(r, "deliveryState")))
                .map(r -> text(r, "personId")).distinct().count();
    }

    private List<String> tagVersions(Accounts.Actor actor, List<String> ids) {
        return ids.stream().map(id -> text(required(actor, "TagDefinition", id), "currentVersionId")).sorted().toList();
    }

    private boolean hasReviewer(Accounts.Actor actor, ObjectRecord task) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mirror_accounts a WHERE a.tenant_id = ? AND a.organization_id = ?
                  AND a.enabled = TRUE AND a.username <> ? AND a.username <> ?
                  AND EXISTS (SELECT 1 FROM mirror_role_permissions p
                              WHERE p.role_name = a.role_name AND p.permission_name = 'REMINDER_REVIEW')
                """, Integer.class, actor.tenantId(), actor.organizationId(), actor.username(), text(task, "createdBy"));
        return count != null && count > 0;
    }

    private TaskView view(Accounts.Actor actor, ObjectRecord task) {
        var selection = storage.getObject(actor.context(), "RecipientSelection", text(task, "selectionId"));
        var version = storage.getObject(actor.context(), "ReminderTaskVersion", text(task, "pendingVersionId"));
        int count = selection == null ? 0 : (int) decodeEntries(text(selection, "entriesJson")).stream().filter(ReminderSelection.Entry::included).count();
        boolean expired = "PENDING_REVIEW".equals(text(task, "state")) && instant(task, "plannedAt") != null
                && !instant(task, "plannedAt").isAfter(clock.instant());
        return new TaskView(task.id(), task.version(), expired ? "REVIEW_EXPIRED" : text(task, "state"), text(task, "title"),
                text(task, "organizationId"), text(task, "createdBy"), number(task, "reviewRound"), text(task, "sendMode"),
                text(task, "plannedAt"), text(task, "readingWindow"), version == null ? "" : text(version, "categorySnapshot"), count,
                selection == null ? "" : text(selection, "criteriaDigest"), version == null ? "" : text(version, "contentDigest"),
                !text(task, "contentCheckId").isEmpty());
    }

    private boolean visible(Accounts.Actor actor, ObjectRecord task) {
        if (actor.role().equals("SUPER_ADMIN")) return true;
        if (actor.role().equals("REVIEWER")) return actor.organizationId().equals(text(task, "organizationId"))
                && !"DRAFT".equals(text(task, "state"));
        return actor.organizationId().equals(text(task, "organizationId")) || actor.username().equals(text(task, "createdBy"));
    }

    private ObjectRecord visibleTask(Accounts.Actor actor, String id) {
        var task = storage.getObject(actor.context(), "ReminderTask", id);
        if (task == null || task.isDeleted() || !visible(actor, task)) throw new AccessDeniedException("无权访问此任务");
        return task;
    }

    private void requireWrite(Accounts.Actor actor, ObjectRecord task) {
        if (!actor.organizationId().equals(text(task, "organizationId"))) throw new AccessDeniedException("请在任务创建单位上下文操作");
    }

    private void authorizeWrite(Accounts.Actor actor, String taskId, String action) {
        contracts.authorize(actor, action);
        requireActiveOrganization(actor);
        requireWrite(actor, visibleTask(actor, taskId));
    }

    private void requireActiveOrganization(Accounts.Actor actor) {
        if (!"ACTIVE".equals(text(required(actor, "Organization", actor.organizationId()), "status"))) {
            throw new BusinessConflict("当前操作单位已停用");
        }
    }

    private ReminderSelection.Result currentSelection(Accounts.Actor actor, ObjectRecord selection) {
        return selections.resolve(actor, decodeFilter(text(selection, "criteriaJson")));
    }

    private void requireConfirmedInput(ObjectRecord selection, ReminderSelection.Result resolved, String providedDigest, int count) {
        if (count <= 0 || count != resolved.included().size() || !digest(resolved).equals(providedDigest)
                || !text(selection, "criteriaDigest").equals(providedDigest)) throw new BusinessConflict("名单或人员资料已变化，请保存并重新确认");
    }

    private void validateDraft(Draft input) {
        if (input.title() == null || input.title().isBlank() || input.title().length() > 120
                || input.category() == null || input.category().isBlank() || input.category().length() > 100
                || input.readingWindow() == null || !Set.of("1d", "2d", "3d", "1w").contains(input.readingWindow()) || input.expectedVersion() < 0
                || input.filter() == null || input.restoreIds() == null) throw new IllegalArgumentException("Invalid draft");
        input.filter().validate();
        if (input.restoreIds().size() > 50000 || input.restoreIds().stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Invalid exclusion restore list");
        }
    }

    private void requireFuture(Instant plannedAt) {
        if (plannedAt != null && !plannedAt.isAfter(clock.instant())) throw new BusinessConflict("计划时间已过，请重新设时并提交审核");
    }

    private ObjectRecord required(Accounts.Actor actor, String type, String id) {
        var record = storage.getObject(actor.context(), type, id);
        if (record == null || record.isDeleted()) throw new BusinessConflict("业务记录不存在或已删除");
        return record;
    }

    private static void checkVersion(ObjectRecord record, long expected) {
        if (record.version() != expected) throw new BusinessConflict("记录已变化，请刷新后重试");
    }

    private static void requireState(ObjectRecord record, String expected) {
        if (!text(record, "state").equals(expected)) throw new BusinessConflict("当前状态不允许此操作");
    }

    private String digest(ReminderSelection.Result result) { return BusinessCommands.hash(encode(result)); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid snapshot", e); }
    }
    private ReminderSelection.Filter decodeFilter(String value) {
        try { return json.readValue(value, ReminderSelection.Filter.class); }
        catch (Exception e) { throw new BusinessConflict("名单条件需要核实迁移"); }
    }
    private List<ReminderSelection.Entry> decodeEntries(String value) {
        try { return json.readValue(value, new TypeReference<List<ReminderSelection.Entry>>() {}); }
        catch (Exception e) { throw new BusinessConflict("名单明细需要核实迁移"); }
    }
    private static Instant instant(ObjectRecord record, String field) {
        return text(record, field).isBlank() ? null : Instant.parse(text(record, field));
    }
    private static String text(ObjectRecord record, String field) {
        Object value = record.properties().get(field);
        return value == null ? "" : value.toString();
    }
    private static int number(ObjectRecord record, String field) {
        return Integer.parseInt(text(record, field).isEmpty() ? "0" : text(record, field));
    }
    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    public record Draft(long expectedVersion, String title, String bodyHtml, String category, String readingWindow,
                        Instant plannedAt, ReminderSelection.Filter filter, List<String> restoreIds) {}
    public record Confirmation(long expectedVersion, String selectionDigest, int recipientCount,
                               boolean singleRecipientAcknowledged, String contentDigest,
                               boolean contentAcknowledged, boolean duplicateAcknowledged) {}
    public record Decision(long expectedVersion, String decision, String comment) {}
    public record TaskView(String id, long version, String state, String title, String organizationId, String createdBy,
                           int reviewRound, String sendMode, String plannedAt, String readingWindow, String category, int recipientCount,
                           String selectionDigest, String contentDigest, boolean confirmed) {}
    public record ReviewView(String id, long version, String state, int roundNumber, String submittedBy,
                             String decidedBy, String comment, String snapshotDigest) {}
    public record Detail(TaskView task, String bodyHtml, String contentDigest, ReminderSelection.Filter filter,
                         List<ReminderSelection.Entry> entries, List<ReviewView> rounds, String reviewComment) {}
}
