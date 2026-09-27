package gov.objectlibrary.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Database work is committed before channel I/O. Recovery reuses the persisted attempt key. */
@Service
public class DeliveryService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final ReminderService reminders;
    private final DomainContracts contracts;
    private final BusinessCommands commands;
    private final ReminderChannel channel;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final Accounts accounts;
    private final boolean demo;

    public DeliveryService(StorageProvider storage, DirectoryService directory, ReminderService reminders,
                           DomainContracts contracts, BusinessCommands commands, ReminderChannel channel,
                           JdbcTemplate jdbc, ObjectMapper json, Clock clock, Accounts accounts,
                           @Value("${mirror.demo:false}") boolean demo) {
        this.storage = storage;
        this.directory = directory;
        this.reminders = reminders;
        this.contracts = contracts;
        this.commands = commands;
        this.channel = channel;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.accounts = accounts;
        this.demo = demo;
    }

    public String mode() {
        return channel.mode();
    }

    public boolean mockEnabled() {
        return demo && mode().equals("mock");
    }

    public Map<String, Object> dispatchMock(Accounts.Actor actor, String taskId, long expectedVersion) {
        var task = reminders.getTaskForOperator(actor, taskId, true);
        accounts.requirePermission(actor, "REMINDER_WRITE");
        if (!mockEnabled()) throw new org.springframework.security.access.AccessDeniedException("Mock发送未启用");
        if (task.version() != expectedVersion) throw new BusinessConflict("任务已变化，请刷新");
        int attempts = dispatchTenant(actor.tenantId(), taskId);
        return Map.of("attempts", attempts, "mode", mode(), "taskId", taskId);
    }

    public int dispatchDue() {
        if (channel.mode().equals("disabled")) return 0;
        int count = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            count += dispatchTenant(tenant, null);
        }
        return count;
    }

    private int dispatchTenant(String tenant, String taskId) {
        int count = 0;
        var worker = worker(tenant);
        for (var job : directory.all(worker, "ReminderSendJob")) {
            if (taskId != null && !taskId.equals(text(job, "taskId"))) continue;
            if (!Set.of("QUEUED", "RUNNING").contains(text(job, "state"))) continue;
            if (Instant.parse(text(job, "dueAt")).isAfter(clock.instant())) continue;
            if (!text(job, "leaseUntil").isEmpty() && Instant.parse(text(job, "leaseUntil")).isAfter(clock.instant())) continue;
            try {
                count += runJob(worker, job);
            } catch (BusinessConflict concurrentChange) {
                // A competing worker or user changed the job. Reload on the next scan.
            }
        }
        return count;
    }

    private int runJob(Accounts.Actor worker, ObjectRecord observed) {
        String lease = UUID.randomUUID().toString();
        commands.executeDefined(worker, "DispatchReminder", "claim-" + lease, List.of(observed.id(), observed.version()),
                () -> contracts.authorizeSystem("DispatchReminder"), tx -> {
            var job = require(worker, "ReminderSendJob", observed.id());
            if (job.version() != observed.version()) throw new BusinessConflict("作业已被其他worker领取");
            var task = require(worker, "ReminderTask", text(job, "taskId"));
            if (!Set.of("APPROVED_WAITING", "SENDING").contains(text(task, "state"))) throw new BusinessConflict("任务不允许发送");
            var version = require(worker, "ReminderTaskVersion", text(job, "snapshotId"));
            var approved = directory.links(worker, version.key(), "ReviewForVersion", StorageProvider.Direction.INBOUND).stream()
                    .map(link -> require(worker, "ReviewRound", link.from().id()))
                    .anyMatch(round -> text(round, "state").equals("APPROVED")
                            && text(round, "taskId").equals(task.id())
                            && text(round, "snapshotDigest").equals(text(version, "snapshotHash")));
            if (!approved || !text(version, "taskId").equals(task.id())
                    || !Set.of("APPROVED", "PUBLISHED").contains(text(version, "state"))) {
                throw new BusinessConflict("没有匹配的有效审核快照");
            }
            contracts.validateInputs(worker, "DispatchReminder", values("jobId", job.id(), "expectedVersion", job.version(),
                    "evaluatedAt", clock.instant().toString()));
            if (text(version, "state").equals("APPROVED")) {
                contracts.requireTransition(version.type(), "state", "APPROVED", "PUBLISHED", "DispatchReminder");
                tx.updateObject(version.type(), version.id(), values("state", "PUBLISHED", "publishedAt", clock.instant().toString()), version.version());
            }
            contracts.requireTransition(task.type(), "state", text(task, "state"), "SENDING", "DispatchReminder");
            tx.updateObject(task.type(), task.id(), values("state", "SENDING", "currentPublishedVersionId", version.id()), task.version());
            tx.updateObject(job.type(), job.id(), values("state", "RUNNING", "leaseOwner", lease,
                    "leaseUntil", clock.instant().plusSeconds(60).toString()), job.version());
            return Map.of("jobId", job.id(), "state", "RUNNING", "mode", channel.mode());
        }, contracts.eventType("DispatchReminder"));

        var job = require(worker, "ReminderSendJob", observed.id());
        String versionId = text(job, "snapshotId");
        List<String> roster = roster(worker, job);
        int attempts = 0;
        for (String id : roster) {
            var recipient = require(worker, "RecipientRecord", id);
            if (text(recipient, "deliveryState").equals("DELIVERED") || !text(recipient, "withdrawalState").equals("NONE")) continue;
            if (attempts >= 500) break;
            var currentJob = require(worker, "ReminderSendJob", job.id());
            if (!lease.equals(text(currentJob, "leaseOwner"))) break;
            String attemptId = "attempt-" + BusinessCommands.hash(job.id() + "/" + id);
            var existing = storage.getObject(worker.context(), "DeliveryAttempt", attemptId);
            if (existing != null && !text(existing, "state").equals("SUBMITTED")) continue;
            if (existing == null) {
                commands.executeDefined(worker, "DispatchReminder", "prepare-" + BusinessCommands.hash(attemptId),
                        List.of(job.id(), id), () -> contracts.authorizeSystem("DispatchReminder"), tx -> {
                    var current = require(worker, "RecipientRecord", id);
                    var lockedJob = require(worker, "ReminderSendJob", job.id());
                    if (!lease.equals(text(lockedJob, "leaseOwner"))) throw new BusinessConflict("发送租约已变化");
                    if (text(current, "deliveryState").equals("DELIVERED") || !text(current, "withdrawalState").equals("NONE")) {
                        return Map.of("skipped", true);
                    }
                    if (!text(require(worker, "ReminderTask", text(job, "taskId")), "state").equals("SENDING")) {
                        throw new BusinessConflict("任务已停止发送");
                    }
                    if (!text(current, "taskId").equals(text(job, "taskId"))) throw new BusinessConflict("接收记录与任务不一致");
                    var person = require(worker, "Person", text(current, "personId"));
                    var attempt = tx.createObject("DeliveryAttempt", attemptId, values("recipientId", id, "state", "SUBMITTED",
                            "attemptedAt", clock.instant().toString(), "submittedAt", clock.instant().toString(),
                            "attemptKey", attemptId, "taskVersionId", versionId, "channelMode", channel.mode(),
                            "identityReference", text(person, "identityReference")));
                    tx.createLink("RecipientHasDeliveryAttempt", "recipient-" + attemptId, current.key(), attempt.key(), Map.of());
                    tx.createLink("SendJobHasAttempt", "job-" + attemptId, lockedJob.key(), attempt.key(), Map.of());
                    tx.updateObject(current.type(), id, values("deliveryState", "SUBMITTED", "channelMode", channel.mode(), "latestAttemptId", attemptId), current.version());
                    String stateId = readingStateId(id, versionId);
                    if (storage.getObject(worker.context(), "RecipientVersionState", stateId) == null) {
                        var reading = tx.createObject("RecipientVersionState", stateId, values("state", "UNREAD",
                                "publishedAt", clock.instant().toString(), "updatedAt", clock.instant().toString()));
                        tx.createLink("VersionStateForRecipient", "recipient-" + stateId, reading.key(), current.key(), Map.of());
                        tx.createLink("VersionStateForVersion", "version-" + stateId, reading.key(), new EntityKey("ReminderTaskVersion", versionId), Map.of());
                    }
                    return Map.of("attemptId", attemptId, "mode", channel.mode());
                }, contracts.eventType("DispatchReminder"));
            }
            var latest = require(worker, "RecipientRecord", id);
            if (text(latest, "deliveryState").equals("DELIVERED") || !text(latest, "withdrawalState").equals("NONE")) continue;
            var attempt = storage.getObject(worker.context(), "DeliveryAttempt", attemptId);
            if (attempt == null || !text(attempt, "state").equals("SUBMITTED")) continue;
            renewLease(worker, job.id(), lease);
            var version = require(worker, "ReminderTaskVersion", versionId);
            ReminderChannel.Result result;
            try {
                result = channel.send(new ReminderChannel.Request(worker.tenantId(), attemptId, id,
                        text(attempt, "identityReference"), versionId, text(version, "titleSnapshot")));
            } catch (RuntimeException uncertain) {
                result = new ReminderChannel.Result("UNKNOWN", "unknown-" + attemptId, clock.instant(), "CHANNEL_UNCONFIRMED");
            }
            recordResult(worker.tenantId(), id, attemptId, result);
            attempts++;
        }
        finishBatch(worker, job.id(), lease, roster);
        return attempts;
    }

    void recordResult(String tenant, String recipientId, String attemptId, ReminderChannel.Result result) {
        var worker = worker(tenant);
        var registeredAttempt = require(worker, "DeliveryAttempt", attemptId);
        String eventKey = text(registeredAttempt, "channelMode") + "/" + result.eventId();
        var input = List.of(recipientId, attemptId, result);
        commands.executeDefined(worker, "RecordDeliveryReceipt", "receipt-" + BusinessCommands.hash(eventKey), input,
                () -> contracts.authorizeSystem("RecordDeliveryReceipt"), tx -> {
            var recipient = require(worker, "RecipientRecord", recipientId);
            var attempt = require(worker, "DeliveryAttempt", attemptId);
            if (!recipientId.equals(text(attempt, "recipientId"))) throw new BusinessConflict("回执与接收记录不一致");
            var version = require(worker, "ReminderTaskVersion", text(attempt, "taskVersionId"));
            if (!text(version, "taskId").equals(text(recipient, "taskId"))
                    || result.occurredAt().isBefore(Instant.parse(text(attempt, "attemptedAt")))
                    || result.occurredAt().isAfter(clock.instant().plusSeconds(300))) {
                throw new BusinessConflict("回执任务或发生时间无效");
            }
            contracts.validateInputs(worker, "RecordDeliveryReceipt", values("recipientId", recipientId, "attemptId", attemptId,
                    "externalEventId", result.eventId(), "outcome", result.state(), "occurredAt", result.occurredAt().toString(),
                    "observedAt", clock.instant().toString()));
            String receiptId = "receipt-" + BusinessCommands.hash(eventKey);
            var receipt = tx.createObject("DeliveryReceipt", receiptId, values("recipientId", recipientId, "state", result.state(),
                    "receivedAt", clock.instant().toString(), "occurredAt", result.occurredAt().toString(), "attemptId", attemptId,
                    "externalEventId", result.eventId(), "externalId", result.eventId(), "errorCode", result.errorCode(),
                    "channelMode", text(attempt, "channelMode")));
            tx.createLink("RecipientHasDeliveryReceipt", "recipient-" + receiptId, recipient.key(), receipt.key(), Map.of());
            tx.createLink("DeliveryReceiptForAttempt", "attempt-" + receiptId, receipt.key(), attempt.key(), Map.of());
            if (!text(attempt, "state").equals("DELIVERED")
                    && !(text(attempt, "state").equals("FAILED") && result.state().equals("UNKNOWN"))) {
                tx.updateObject(attempt.type(), attempt.id(), values("state", result.state(), "resultObservedAt", clock.instant().toString(),
                        "errorCode", result.errorCode()), attempt.version());
            }
            var updates = new HashMap<String, Object>();
            if (result.state().equals("DELIVERED")) {
                updates.put("deliveryState", "DELIVERED");
                String first = text(recipient, "firstDeliveredAt");
                if (first.isEmpty() || result.occurredAt().isBefore(Instant.parse(first))) {
                    updates.put("firstDeliveredAt", result.occurredAt().toString());
                    updates.put("deadlineAt", result.occurredAt().plus(hours(text(recipient, "readingWindow")), ChronoUnit.HOURS).toString());
                }
            } else if (!text(recipient, "deliveryState").equals("DELIVERED")
                    && attemptId.equals(text(recipient, "latestAttemptId"))
                    && !(text(recipient, "deliveryState").equals("FAILED") && result.state().equals("UNKNOWN"))) {
                updates.put("deliveryState", result.state());
            }
            if (!updates.isEmpty()) {
                contracts.requireTransition(recipient.type(), "deliveryState", text(recipient, "deliveryState"),
                        updates.get("deliveryState").toString(), "RecordDeliveryReceipt");
                tx.updateObject(recipient.type(), recipientId, updates, recipient.version());
            }
            var task = require(worker, "ReminderTask", text(recipient, "taskId"));
            if (Set.of("ALL_FAILED", "PARTIAL_FAILED").contains(text(task, "state"))) {
                String summary = taskSummary(worker, task, recipientId, updates);
                contracts.requireTransition(task.type(), "state", text(task, "state"), summary, "RecordDeliveryReceipt");
                tx.updateObject(task.type(), task.id(), Map.of("state", summary), task.version());
            }
            return values("recipientId", recipientId, "result", result.state(), "channelMode", text(attempt, "channelMode"));
        }, contracts.eventType("RecordDeliveryReceipt"));
    }

    private void finishBatch(Accounts.Actor worker, String jobId, String lease, List<String> roster) {
        commands.executeDefined(worker, "DispatchReminder", "finish-" + lease, List.of(jobId, lease),
                () -> contracts.authorizeSystem("DispatchReminder"), tx -> {
            var job = require(worker, "ReminderSendJob", jobId);
            if (!lease.equals(text(job, "leaseOwner"))) throw new BusinessConflict("发送租约已变化");
            var recipients = roster.stream().map(id -> require(worker, "RecipientRecord", id)).toList();
            boolean pending = recipients.stream().anyMatch(r -> Set.of("PENDING", "SUBMITTED").contains(text(r, "deliveryState")));
            long delivered = recipients.stream().filter(r -> text(r, "deliveryState").equals("DELIVERED")).count();
            String jobState = pending ? "QUEUED" : delivered == recipients.size() ? "SUCCEEDED" : delivered == 0 ? "FAILED" : "PARTIAL_FAILED";
            tx.updateObject(job.type(), job.id(), values("state", jobState, "leaseUntil", null, "leaseOwner", null), job.version());
            var task = require(worker, "ReminderTask", text(job, "taskId"));
            List<String> all = directory.links(worker, new EntityKey("ReminderTaskVersion", text(job, "snapshotId")),
                    "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND).stream().map(l -> l.to().id()).toList();
            long totalDelivered = all.stream().map(id -> require(worker, "RecipientRecord", id))
                    .filter(r -> text(r, "deliveryState").equals("DELIVERED")).count();
            String taskState = pending ? "SENDING" : totalDelivered == all.size() ? "ALL_SUCCESS" : totalDelivered == 0 ? "ALL_FAILED" : "PARTIAL_FAILED";
            if (text(task, "state").equals("SENDING")) {
                contracts.requireTransition(task.type(), "state", "SENDING", taskState, "RecordDeliveryReceipt");
                tx.updateObject(task.type(), task.id(), Map.of("state", taskState), task.version());
            }
            return Map.of("jobId", jobId, "state", jobState, "delivered", delivered, "total", roster.size(), "mode", channel.mode());
        }, contracts.eventType("DispatchReminder"));
    }

    private String taskSummary(Accounts.Actor actor, ObjectRecord task, String changedId, Map<String, Object> changes) {
        var recipients = directory.links(actor, new EntityKey("ReminderTaskVersion", text(task, "currentPublishedVersionId")),
                "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND);
        long delivered = recipients.stream().filter(link -> {
            var recipient = require(actor, "RecipientRecord", link.to().id());
            String state = recipient.id().equals(changedId) && changes.containsKey("deliveryState")
                    ? changes.get("deliveryState").toString() : text(recipient, "deliveryState");
            return state.equals("DELIVERED");
        }).count();
        return delivered == recipients.size() ? "ALL_SUCCESS" : delivered == 0 ? "ALL_FAILED" : "PARTIAL_FAILED";
    }

    private void renewLease(Accounts.Actor worker, String jobId, String lease) {
        commands.executeDefined(worker, "DispatchReminder", "renew-" + UUID.randomUUID(), List.of(jobId, lease),
                () -> contracts.authorizeSystem("DispatchReminder"), tx -> {
            var job = require(worker, "ReminderSendJob", jobId);
            var task = require(worker, "ReminderTask", text(job, "taskId"));
            if (!lease.equals(text(job, "leaseOwner")) || !text(task, "state").equals("SENDING")) {
                throw new BusinessConflict("发送作业已停止或租约已变化");
            }
            tx.updateObject(job.type(), job.id(), Map.of("leaseUntil", clock.instant().plusSeconds(60).toString()), job.version());
            return Map.of("jobId", jobId);
        }, contracts.eventType("DispatchReminder"));
    }

    public Map<String, Object> retry(Accounts.Actor actor, String taskId, long expectedVersion, String key) {
        String action = "RetryFailedRecipients";
        return commands.executeDefined(actor, action, key, List.of(taskId, expectedVersion), () -> {
            contracts.authorize(actor, action);
            reminders.getTaskForOperator(actor, taskId, true);
        }, tx -> {
            var task = require(actor, "ReminderTask", taskId);
            if (task.version() != expectedVersion || !Set.of("PARTIAL_FAILED", "ALL_FAILED").contains(text(task, "state"))) {
                throw new BusinessConflict("任务状态已变化或仍在发送中");
            }
            String versionId = text(task, "currentPublishedVersionId");
            var ids = directory.links(actor, new EntityKey("ReminderTaskVersion", versionId), "VersionTargetsRecipient",
                    StorageProvider.Direction.OUTBOUND).stream().map(l -> require(actor, "RecipientRecord", l.to().id()))
                    .filter(r -> Set.of("FAILED", "UNKNOWN").contains(text(r, "deliveryState")) && text(r, "withdrawalState").equals("NONE"))
                    .map(ObjectRecord::id).toList();
            if (ids.isEmpty()) throw new BusinessConflict("没有可重试的失败或未知接收记录");
            contracts.validateInputs(actor, action, values("taskId", taskId, "expectedVersion", expectedVersion, "recipientIds", ids));
            String jobId = "retry-" + UUID.randomUUID();
            var job = tx.createObject("ReminderSendJob", jobId, values("taskId", taskId, "snapshotId", versionId,
                    "state", "QUEUED", "dueAt", clock.instant().toString(), "createdAt", clock.instant().toString(), "recipientIdsJson", encode(ids)));
            tx.createLink("SendJobForTask", "task-" + jobId, job.key(), task.key(), Map.of());
            tx.createLink("SendJobForVersion", "version-" + jobId, job.key(), new EntityKey("ReminderTaskVersion", versionId), Map.of());
            contracts.requireTransition("ReminderTask", "state", text(task, "state"), "SENDING", action);
            var changed = tx.updateObject(task.type(), task.id(), Map.of("state", "SENDING"), task.version());
            return Map.of("id", taskId, "version", changed.version(), "retryCount", ids.size());
        }, contracts.eventType(action));
    }

    public List<RecipientView> recipients(Accounts.Actor actor, String taskId) {
        var task = reminders.getTaskForOperator(actor, taskId, false);
        accounts.requirePermission(actor, "OVERDUE_READ");
        String versionId = text(task, "currentPublishedVersionId");
        if (versionId.isEmpty()) return List.of();
        return directory.links(actor, new EntityKey("ReminderTaskVersion", versionId), "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND).stream()
                .map(link -> require(actor, "RecipientRecord", link.to().id())).map(r -> {
                    var reading = storage.getObject(actor.context(), "RecipientVersionState", readingStateId(r.id(), versionId));
                    return new RecipientView(r.id(), text(r, "personNameSnapshot"), text(r, "organizationNameSnapshot"),
                            text(r, "deliveryState"), reading == null ? "UNREAD" : text(reading, "state"), text(r, "firstDeliveredAt"),
                            text(r, "deadlineAt"), text(r, "channelMode"));
                }).toList();
    }

    private List<String> roster(Accounts.Actor worker, ObjectRecord job) {
        if (!text(job, "recipientIdsJson").isEmpty()) {
            try {
                return json.readValue(text(job, "recipientIdsJson"), new TypeReference<List<String>>() {});
            } catch (Exception invalid) {
                throw new BusinessConflict("重试名单损坏");
            }
        }
        return directory.links(worker, new EntityKey("ReminderTaskVersion", text(job, "snapshotId")), "VersionTargetsRecipient",
                StorageProvider.Direction.OUTBOUND).stream().map(link -> link.to().id()).toList();
    }

    static String readingStateId(String recipientId, String versionId) {
        return "read-state-" + BusinessCommands.hash(recipientId + "/" + versionId);
    }

    static Accounts.Actor worker(String tenant) {
        return new Accounts.Actor("__delivery_worker__", "发送作业", tenant, "", "SYSTEM");
    }
    static long hours(String window) {
        return switch (window) {
            case "1d" -> 24;
            case "2d" -> 48;
            case "3d" -> 72;
            case "1w" -> 168;
            default -> throw new BusinessConflict("阅读时限无效");
        };
    }
    private ObjectRecord require(Accounts.Actor actor, String type, String id) {
        var object = storage.getObject(actor.context(), type, id);
        if (object == null || object.isDeleted()) throw new BusinessConflict("发送记录不存在");
        return object;
    }
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    static String text(ObjectRecord object, String key) {
        var value = object.properties().get(key);
        return value == null ? "" : value.toString();
    }
    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
    public record RecipientView(String id, String name, String organization, String deliveryState, String readState,
                                String firstDeliveredAt, String deadlineAt, String channelMode) {}
}
