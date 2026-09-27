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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;

/** Withdrawal intent and channel result are different facts. Nothing here deletes a delivery or a read. */
@Service
class WithdrawalService {
    private final StorageProvider storage;
    private final Accounts accounts;
    private final DirectoryService directory;
    private final ReminderService reminders;
    private final DomainContracts contracts;
    private final BusinessCommands commands;
    private final ReminderChannel channel;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    WithdrawalService(StorageProvider storage, DirectoryService directory, ReminderService reminders, DomainContracts contracts,
                      BusinessCommands commands, ReminderChannel channel, JdbcTemplate jdbc, ObjectMapper json, Clock clock, Accounts accounts) {
        this.storage = storage;
        this.accounts = accounts;
        this.directory = directory;
        this.reminders = reminders;
        this.contracts = contracts;
        this.commands = commands;
        this.channel = channel;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    Map<String, Object> request(Accounts.Actor actor, String taskId, Request input, String key, boolean retry) {
        if (input.recipientIds() == null || input.recipientIds().isEmpty() || input.recipientIds().size() > 50000
                || input.recipientIds().stream().anyMatch(id -> id == null || id.isBlank())
                || Set.copyOf(input.recipientIds()).size() != input.recipientIds().size()
                || input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000) {
            throw new IllegalArgumentException("撤回须明确接收人并填写原因");
        }
        String action = retry ? "RetryWithdrawal" : "RequestReminderWithdrawal";
        return commands.executeDefined(actor, action, key, List.of(taskId, input), () -> {
            contracts.authorize(actor, action);
            reminders.getTaskForOperator(actor, taskId, true);
        }, tx -> {
            var task = require(actor, "ReminderTask", taskId);
            if (task.version() != input.expectedVersion() || text(task, "currentPublishedVersionId").isEmpty()
                    || !Set.of("SENDING", "ALL_SUCCESS", "PARTIAL_FAILED", "ALL_FAILED", "WITHDRAWING", "PARTIAL_WITHDRAWN", "WITHDRAW_FAILED")
                    .contains(text(task, "state"))) throw new BusinessConflict("任务已变化或不允许撤回");
            var roster = roster(actor, task);
            if (!roster.containsAll(input.recipientIds())) throw new BusinessConflict("接收记录不属于当前任务原名单");
            List<String> previous = new ArrayList<>();
            for (String id : input.recipientIds()) {
                var recipient = require(actor, "RecipientRecord", id);
                if (!text(recipient, "taskId").equals(taskId)) throw new BusinessConflict("接收记录任务不一致");
                if (retry) {
                    var old = require(actor, "WithdrawalRecord", text(recipient, "latestWithdrawalId"));
                    if (!Set.of("FAILED", "UNKNOWN").contains(text(recipient, "withdrawalState"))
                            || !Set.of("FAILED", "UNKNOWN").contains(text(old, "state"))) throw new BusinessConflict("仅失败或结果未知的撤回可重试");
                    previous.add(old.id());
                } else if (!text(recipient, "withdrawalState").equals("NONE")) throw new BusinessConflict("已申请撤回的人员不能重复申请，请按逐人结果重试");
            }
            if (retry) contracts.validateInputs(actor, action, Map.of("withdrawalIds", previous, "reason", input.reason()));
            else contracts.validateInputs(actor, action, Map.of("taskId", taskId, "recipientIds", input.recipientIds(),
                    "expectedVersion", task.version(), "reason", input.reason()));
            for (String id : input.recipientIds()) {
                var recipient = require(actor, "RecipientRecord", id);
                var attempts = directory.links(actor, recipient.key(), "RecipientHasDeliveryAttempt", StorageProvider.Direction.OUTBOUND).stream()
                        .map(link -> require(actor, "DeliveryAttempt", link.to().id())).toList();
                if (attempts.stream().anyMatch(attempt -> !text(attempt, "recipientId").equals(id) || text(attempt, "attemptKey").isBlank()
                        || !text(require(actor, "ReminderTaskVersion", text(attempt, "taskVersionId")), "taskId").equals(taskId))) {
                    throw new BusinessConflict("原发送请求标识或关联不完整，须先核实");
                }
                var modes = attempts.stream().map(a -> text(a, "channelMode")).distinct().toList();
                if (modes.size() > 1 || modes.stream().anyMatch(String::isEmpty)) throw new BusinessConflict("历史渠道不明确，须先核实");
                var keys = attempts.stream().map(a -> text(a, "attemptKey")).sorted().toList();
                String idempotency = "withdraw-" + UUID.randomUUID();
                var withdrawal = tx.createObject("WithdrawalRecord", idempotency, values("recipientId", id, "taskId", taskId,
                        "taskVersionId", text(task, "currentPublishedVersionId"), "state", "REQUESTED", "requestedAt", clock.instant().toString(),
                        "requestedBy", actor.username(), "organizationId", actor.organizationId(), "reason", input.reason().strip(),
                        "requestKey", idempotency, "channelMode", modes.isEmpty() ? "local" : modes.getFirst(), "deliveryRequestKeysJson", encode(keys)));
                tx.createLink("RecipientHasWithdrawal", "recipient-" + idempotency, recipient.key(), withdrawal.key(), Map.of());
                contracts.requireTransition(recipient.type(), "withdrawalState", text(recipient, "withdrawalState"), "REQUESTED", action);
                tx.updateObject(recipient.type(), id, values("withdrawalState", "REQUESTED", "latestWithdrawalId", idempotency), recipient.version());
            }
            var changes = values("state", "WITHDRAWING");
            cancelPendingRevision(actor, task, action, tx, changes);
            contracts.requireTransition(task.type(), "state", text(task, "state"), "WITHDRAWING", action);
            var changed = tx.updateObject(task.type(), taskId, changes, task.version());
            return values("id", taskId, "version", changed.version(), "state", "WITHDRAWING", "requested", input.recipientIds().size());
        }, contracts.eventType(action));
    }

    private void cancelPendingRevision(Accounts.Actor actor, ObjectRecord task, String action, Transaction tx, Map<String, Object> changes) {
        String state = ReminderService.revisionState(task);
        if (!Set.of("DRAFT", "PENDING_REVIEW", "APPROVED").contains(state)) return;
        if (state.equals("PENDING_REVIEW")) {
            var round = require(actor, "ReviewRound", text(task, "activeReviewId"));
            contracts.requireTransition(round.type(), "state", "PENDING", "WITHDRAWN", action);
            tx.updateObject(round.type(), round.id(), values("state", "WITHDRAWN", "decidedBy", actor.username(),
                    "decidedAt", clock.instant().toString(), "comment", "已申请消息撤回，终止本次修订"), round.version());
        }
        contracts.requireTransition(task.type(), "revisionState", state, "WITHDRAWN", action);
        changes.put("revisionState", "WITHDRAWN");
        changes.put("contentCheckId", null);
    }

    int dispatchPending() {
        int count = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) count += dispatchTenant(tenant, null);
        return count;
    }

    int dispatchTenant(String tenant, String taskId) {
        var worker = worker(tenant);
        int count = 0;
        for (var candidate : directory.all(worker, "WithdrawalRecord")) {
            if (count >= 500) break;
            if (taskId != null && !taskId.equals(text(candidate, "taskId"))) continue;
            if (!Set.of("REQUESTED", "RUNNING").contains(text(candidate, "state"))) continue;
            if (!text(candidate, "leaseUntil").isEmpty() && Instant.parse(text(candidate, "leaseUntil")).isAfter(clock.instant())) continue;
            if (!text(candidate, "channelMode").equals("local") && !text(candidate, "channelMode").equals(channel.mode())) continue;
            if (channel.mode().equals("disabled") && !text(candidate, "channelMode").equals("local")) continue;
            try {
                dispatch(worker, candidate);
                count++;
            } catch (BusinessConflict conflict) {
                // A competing worker or callback won. Recovery reloads the persisted intent.
            }
        }
        return count;
    }

    private void dispatch(Accounts.Actor worker, ObjectRecord candidate) {
        String lease = UUID.randomUUID().toString();
        commands.executeDefined(worker, "DispatchReminderWithdrawal", "claim-withdraw-" + lease, List.of(candidate.id(), candidate.version()),
                () -> contracts.authorizeSystem("DispatchReminderWithdrawal"), tx -> {
            var current = require(worker, "WithdrawalRecord", candidate.id());
            if (current.version() != candidate.version() || !Set.of("REQUESTED", "RUNNING").contains(text(current, "state"))) throw new BusinessConflict("撤回作业已变化");
            contracts.validateInputs(worker, "DispatchReminderWithdrawal", Map.of("withdrawalId", current.id(), "expectedVersion", current.version(),
                    "evaluatedAt", clock.instant().toString()));
            contracts.requireTransition(current.type(), "state", text(current, "state"), "RUNNING", "DispatchReminderWithdrawal");
            tx.updateObject(current.type(), current.id(), values("state", "RUNNING", "leaseOwner", lease,
                    "leaseUntil", clock.instant().plusSeconds(60).toString()), current.version());
            return Map.of("withdrawalId", current.id());
        }, contracts.eventType("DispatchReminderWithdrawal"));
        var current = require(worker, "WithdrawalRecord", candidate.id());
        var recipient = require(worker, "RecipientRecord", text(current, "recipientId"));
        var keys = decodeKeys(text(current, "deliveryRequestKeysJson"));
        ReminderChannel.WithdrawalResult result;
        if (text(recipient, "withdrawalState").equals("WITHDRAWN")) {
            result = new ReminderChannel.WithdrawalResult("WITHDRAWN", "already-" + current.id(), clock.instant(), "ALREADY_CONFIRMED");
        } else if (keys.isEmpty()) {
            if (!directory.links(worker, recipient.key(), "RecipientHasDeliveryAttempt", StorageProvider.Direction.OUTBOUND).isEmpty()) {
                throw new BusinessConflict("本地取消发现新的发送尝试，须核实");
            }
            result = new ReminderChannel.WithdrawalResult("WITHDRAWN", "local-" + current.id(), clock.instant(), "CANCELLED_BEFORE_DISPATCH");
        } else {
            try {
                result = channel.withdraw(new ReminderChannel.WithdrawalRequest(worker.tenantId(), text(current, "requestKey"), recipient.id(), keys, text(current, "reason")));
            } catch (UnsupportedOperationException unsupported) {
                result = new ReminderChannel.WithdrawalResult("FAILED", "unsupported-" + current.id(), clock.instant(), "WITHDRAWAL_UNSUPPORTED");
            } catch (RuntimeException uncertain) {
                result = new ReminderChannel.WithdrawalResult("UNKNOWN", "unknown-" + current.id(), clock.instant(), "CHANNEL_UNCONFIRMED");
            }
        }
        recordResult(worker.tenantId(), current.id(), result);
    }

    void recordResult(String tenant, String withdrawalId, ReminderChannel.WithdrawalResult result) {
        var worker = worker(tenant);
        var known = require(worker, "WithdrawalRecord", withdrawalId);
        String eventKey = text(known, "channelMode") + "/" + result.eventId();
        commands.executeDefinedWithRetry(worker, "RecordWithdrawalResult", "withdraw-result-" + BusinessCommands.hash(eventKey), List.of(withdrawalId, result),
                () -> contracts.authorizeSystem("RecordWithdrawalResult"), tx -> {
            var withdrawal = require(worker, "WithdrawalRecord", withdrawalId);
            var recipient = require(worker, "RecipientRecord", text(withdrawal, "recipientId"));
            var task = require(worker, "ReminderTask", text(withdrawal, "taskId"));
            if (!text(recipient, "taskId").equals(task.id()) || result.occurredAt().isBefore(Instant.parse(text(withdrawal, "requestedAt")))
                    || result.occurredAt().isAfter(clock.instant().plusSeconds(300))) throw new BusinessConflict("撤回回执不匹配");
            contracts.validateInputs(worker, "RecordWithdrawalResult", values("withdrawalId", withdrawalId, "externalEventId", result.eventId(),
                    "outcome", result.state(), "occurredAt", result.occurredAt().toString(), "errorCode", result.errorCode()));
            var observed = values("lastResultState", result.state(), "lastResultAt", result.occurredAt().toString(),
                    "lastEventId", result.eventId(), "lastErrorCode", result.errorCode());
            if (!text(withdrawal, "state").equals("WITHDRAWN")
                    && !(text(withdrawal, "state").equals("FAILED") && result.state().equals("UNKNOWN"))) {
                contracts.requireTransition(withdrawal.type(), "state", text(withdrawal, "state"), result.state(), "RecordWithdrawalResult");
                observed.putAll(values("state", result.state(), "completedAt", result.occurredAt().toString(),
                        "errorCode", result.errorCode(), "externalEventId", result.eventId(), "externalRequestId", text(withdrawal, "requestKey"),
                        "leaseOwner", null, "leaseUntil", null));
            }
            tx.updateObject(withdrawal.type(), withdrawalId, observed, withdrawal.version());
            String state = text(recipient, "withdrawalState");
            if (result.state().equals("WITHDRAWN")) state = "WITHDRAWN";
            else if (!state.equals("WITHDRAWN") && withdrawalId.equals(text(recipient, "latestWithdrawalId"))
                    && !(state.equals("FAILED") && result.state().equals("UNKNOWN"))) state = result.state();
            if (!state.equals(text(recipient, "withdrawalState"))) {
                contracts.requireTransition(recipient.type(), "withdrawalState", text(recipient, "withdrawalState"), state, "RecordWithdrawalResult");
                var changes = values("withdrawalState", state);
                if (state.equals("WITHDRAWN")) changes.put("withdrawnAt", result.occurredAt().toString());
                tx.updateObject(recipient.type(), recipient.id(), changes, recipient.version());
            }
            if (state.equals("WITHDRAWN")) closeOverdue(worker, recipient, tx);
            String summary = summary(worker, task, recipient.id(), state);
            contracts.requireTransition(task.type(), "state", text(task, "state"), summary, "RecordWithdrawalResult");
            var taskChanges = values("state", summary);
            if (summary.equals("WITHDRAWN")) cancelPendingRevision(worker, task, "RecordWithdrawalResult", tx, taskChanges);
            tx.updateObject(task.type(), task.id(), taskChanges, task.version());
            if (summary.equals("WITHDRAWN")) cancelSendJobs(worker, task, tx);
            return values("withdrawalId", withdrawalId, "recipientId", recipient.id(), "outcome", result.state(), "effectiveState", state,
                    "taskState", summary, "externalEventId", result.eventId(), "occurredAt", result.occurredAt().toString(), "errorCode", result.errorCode());
        }, contracts.eventType("RecordWithdrawalResult"));
    }

    DirectoryService.Page<HistoryView> history(Accounts.Actor actor, String taskId, int page, int size) {
        reminders.getTaskForOperator(actor, taskId, false);
        accounts.requirePermission(actor, "OVERDUE_READ");
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        var records = directory.all(actor, "WithdrawalRecord").stream().filter(record -> taskId.equals(text(record, "taskId")))
                .sorted(java.util.Comparator.comparing(ObjectRecord::createdAt).reversed()).toList();
        int start = (int) Math.min((long) page * size, records.size());
        var views = records.subList(start, Math.min(start + size, records.size())).stream().map(record -> {
            var recipient = require(actor, "RecipientRecord", text(record, "recipientId"));
            var events = storage.getEntityHistory(actor.context(), record.key()).stream()
                    .filter(event -> event.state().get("lastEventId") instanceof String id && !id.isEmpty())
                    .map(event -> new ResultView(event.version(), event.recordedAt().toString(),
                            event.state().get("lastEventId").toString(), event.state().get("lastResultState").toString(),
                            event.state().get("lastResultAt").toString(), event.state().getOrDefault("lastErrorCode", "").toString())).toList();
            return new HistoryView(record.id(), text(recipient, "personNameSnapshot"), text(record, "state"), text(record, "reason"),
                    text(record, "requestedBy"), text(record, "organizationId"), text(record, "requestedAt"), text(record, "channelMode"), events);
        }).toList();
        return new DirectoryService.Page<>(views, records.size(), page, size);
    }

    record HistoryView(String id, String name, String state, String reason, String requestedBy, String organizationId,
                       String requestedAt, String channelMode, List<ResultView> results) {}
    record ResultView(long version, String recordedAt, String eventId, String outcome, String occurredAt, String errorCode) {}

    private void closeOverdue(Accounts.Actor worker, ObjectRecord recipient, Transaction tx) {
        for (var link : directory.links(worker, recipient.key(), "RecipientHasOverdueRecord", StorageProvider.Direction.OUTBOUND)) {
            var overdue = require(worker, "OverdueRecord", link.to().id());
            if (!text(overdue, "state").equals("OPEN")) continue;
            contracts.requireTransition(overdue.type(), "state", "OPEN", "CLOSED", "RecordWithdrawalResult");
            tx.updateObject(overdue.type(), overdue.id(), values("state", "CLOSED", "closedReason", "WITHDRAWN", "resolvedAt", clock.instant().toString()), overdue.version());
        }
    }

    private void cancelSendJobs(Accounts.Actor worker, ObjectRecord task, Transaction tx) {
        for (var link : directory.links(worker, task.key(), "SendJobForTask", StorageProvider.Direction.INBOUND)) {
            var job = require(worker, "ReminderSendJob", link.from().id());
            if (Set.of("QUEUED", "RUNNING").contains(text(job, "state"))) {
                tx.updateObject(job.type(), job.id(), values("state", "CANCELLED", "leaseOwner", null, "leaseUntil", null), job.version());
            }
        }
    }

    private String summary(Accounts.Actor worker, ObjectRecord task, String changed, String outcome) {
        var recipients = roster(worker, task).stream().map(id -> require(worker, "RecipientRecord", id)).toList();
        var states = recipients.stream().map(r -> r.id().equals(changed) ? outcome : text(r, "withdrawalState")).toList();
        if (states.contains("REQUESTED")) return "WITHDRAWING";
        long count = states.stream().filter("WITHDRAWN"::equals).count();
        return count == recipients.size() ? "WITHDRAWN" : count > 0 ? "PARTIAL_WITHDRAWN" : "WITHDRAW_FAILED";
    }

    private Set<String> roster(Accounts.Actor actor, ObjectRecord task) {
        return directory.links(actor, new EntityKey("ReminderTaskVersion", text(task, "currentPublishedVersionId")), "VersionTargetsRecipient",
                StorageProvider.Direction.OUTBOUND).stream().map(link -> link.to().id()).collect(java.util.stream.Collectors.toSet());
    }

    private ObjectRecord require(Accounts.Actor actor, String type, String id) {
        var object = storage.getObject(actor.context(), type, id);
        if (object == null || object.isDeleted()) throw new BusinessConflict("撤回记录不存在");
        return object;
    }

    private static Accounts.Actor worker(String tenant) {
        return new Accounts.Actor("__withdrawal_worker__", "撤回作业", tenant, "", "SYSTEM");
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
    }

    private List<String> decodeKeys(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception invalid) { throw new BusinessConflict("撤回目标快照损坏"); }
    }

    record Request(long expectedVersion, List<String> recipientIds, String reason) {}
}
