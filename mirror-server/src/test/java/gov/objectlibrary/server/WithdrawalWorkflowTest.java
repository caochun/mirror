package gov.objectlibrary.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_withdrawal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.delivery-mode=mock", "mirror.scheduling-enabled=false"})
class WithdrawalWorkflowTest {
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "withdrawal-test");
    @Autowired Accounts accounts;
    @Autowired ReminderService reminders;
    @Autowired ReminderRevisionService revisions;
    @Autowired DeliveryService delivery;
    @Autowired WithdrawalService withdrawals;
    @Autowired ReadingService reading;
    @Autowired ReceiverService receiver;
    @Autowired ReceiverSessions sessions;
    @Autowired StorageProvider storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ControlledChannel channel;
    @Autowired DeliveryWorkflowTest.TestClock clock;

    @TestConfiguration
    static class Config {
        @Bean @Primary DeliveryWorkflowTest.TestClock withdrawalClock() { return new DeliveryWorkflowTest.TestClock(); }
        @Bean @Primary ControlledChannel controlledChannel(JdbcTemplate jdbc, DeliveryWorkflowTest.TestClock clock) {
            return new ControlledChannel(new ChannelConfiguration().reminderChannel(jdbc, clock, "mock"), clock);
        }
    }

    static class ControlledChannel implements ReminderChannel {
        final ReminderChannel delegate;
        final DeliveryWorkflowTest.TestClock clock;
        final Map<String, String> faults = new ConcurrentHashMap<>();
        final AtomicInteger withdrawalCalls = new AtomicInteger();
        final AtomicReference<Request> blockedRequest = new AtomicReference<>();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        boolean crashAfterWithdrawal;

        ControlledChannel(ReminderChannel delegate, DeliveryWorkflowTest.TestClock clock) {
            this.delegate = delegate;
            this.clock = clock;
        }
        @Override public String mode() { return "mock"; }
        @Override public Result send(Request request) {
            if (entered != null && blockedRequest.compareAndSet(null, request)) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test send was not released");
                } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            }
            return delegate.send(request);
        }
        @Override public WithdrawalResult withdraw(WithdrawalRequest request) {
            withdrawalCalls.incrementAndGet();
            String fault = faults.get(request.recipientId());
            if ("UNSUPPORTED".equals(fault)) throw new UnsupportedOperationException("Injected unsupported operation");
            var result = fault == null ? delegate.withdraw(request)
                    : new WithdrawalResult(fault, "fault-" + request.requestKey(), clock.instant(), "INJECTED_" + fault);
            if (crashAfterWithdrawal) {
                crashAfterWithdrawal = false;
                throw new DeliveryWorkflowTest.SimulatedProcessCrash();
            }
            return result;
        }
    }

    @BeforeEach
    void reset() {
        clock.now = Instant.parse("2035-01-01T00:00:00Z");
        channel.faults.clear();
        channel.crashAfterWithdrawal = false;
        channel.entered = null;
        channel.release = null;
        channel.blockedRequest.set(null);
    }

    private Accounts.Actor unit() { return accounts.actor("unit"); }
    private String key() { return UUID.randomUUID().toString(); }
    private ObjectRecord task(String id) { return storage.getObject(CONTEXT, "ReminderTask", id); }
    private ObjectRecord personResult(String id) { return storage.getObject(CONTEXT, "RecipientRecord", id); }
    private String recipient(String task, String person) { return "recipient-" + BusinessCommands.hash(task + "/" + person); }

    private String create(int size, boolean dispatch) {
        var people = size == 1 ? List.of("demo-person-001") : List.of("demo-person-001", "demo-person-009");
        var saved = reminders.save(unit(), null, new ReminderService.Draft(0, "撤回验证", "<p>原始正文</p>", "履责", "1d", null,
                new ReminderSelection.Filter(List.of(), List.of(), "ANY", people, List.of()), List.of(), ""), key());
        String id = saved.get("id").toString();
        var confirmed = reminders.confirm(unit(), id, new ReminderService.Confirmation(((Number) saved.get("version")).longValue(),
                saved.get("selectionDigest").toString(), size, true, saved.get("contentDigest").toString(), true, true, List.of()), key());
        var submitted = reminders.submit(unit(), id, ((Number) confirmed.get("version")).longValue(), key());
        reminders.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", ""), key());
        if (dispatch) delivery.dispatchMock(unit(), id, task(id).version());
        return id;
    }

    private void request(String id, List<String> recipients, boolean retry) {
        withdrawals.request(unit(), id, new WithdrawalService.Request(task(id).version(), recipients, "内容需撤回"), key(), retry);
    }

    private String grant(String task, String recipient, MockHttpSession session) {
        String url = receiver.issueMock(unit(), task, recipient, session);
        return sessions.exchange(url.substring(url.indexOf("#ticket=") + 8), session);
    }

    @Test
    void successfulWithdrawalClosesAccessAndOverdueButRetainsReadDeliveryAndContentHistory() {
        String id = create(2, true);
        String r1 = recipient(id, "demo-person-001");
        String r2 = recipient(id, "demo-person-009");
        clock.now = clock.now.plusSeconds(86401);
        reading.evaluateDue();
        var session = new MockHttpSession();
        String grant = grant(id, r1, session);
        var content = receiver.content(grant, session);
        receiver.read(grant, session, content.versionId(), content.renderToken(), true);
        String originalDeadline = text(personResult(r1), "deadlineAt");
        var input = new WithdrawalService.Request(task(id).version(), List.of(r1, r2), "核对后撤回");
        String command = key();
        var result = withdrawals.request(unit(), id, input, command, false);
        assertEquals(result, withdrawals.request(unit(), id, input, command, false));
        assertEquals("REQUESTED", text(personResult(r1), "withdrawalState"));
        assertEquals("WITHDRAWING", text(task(id), "state"));
        assertNotNull(receiver.content(grant, session), "A request alone is not a confirmed channel withdrawal");
        assertEquals(2, withdrawals.dispatchTenant("mirror", id));
        assertEquals("WITHDRAWN", text(task(id), "state"));
        assertThrows(BusinessConflict.class, () -> receiver.content(grant, session));
        assertThrows(BusinessConflict.class, () -> receiver.read(grant, session, content.versionId(), content.renderToken(), true));
        assertEquals("DELIVERED", text(personResult(r1), "deliveryState"));
        assertEquals(originalDeadline, text(personResult(r1), "deadlineAt"));
        assertEquals("READ", text(storage.getObject(CONTEXT, "RecipientVersionState", DeliveryService.readingStateId(r1, content.versionId())), "state"));
        var overdue = storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(r2, content.versionId()));
        assertEquals("CLOSED", text(overdue, "state"));
        assertEquals("WITHDRAWN", text(overdue, "closedReason"));
        assertEquals("PUBLISHED", text(storage.getObject(CONTEXT, "ReminderTaskVersion", content.versionId()), "state"));
        assertEquals(0, withdrawals.dispatchTenant("mirror", id));
    }

    @Test
    void partialResultsRetryOnlyFailuresAndLateResultsCannotUndoSuccess() {
        String id = create(2, true);
        String r1 = recipient(id, "demo-person-001");
        String r2 = recipient(id, "demo-person-009");
        channel.faults.put(r2, "UNKNOWN");
        request(id, List.of(r1, r2), false);
        withdrawals.dispatchTenant("mirror", id);
        assertEquals("PARTIAL_WITHDRAWN", text(task(id), "state"));
        String old = text(personResult(r2), "latestWithdrawalId");
        assertThrows(BusinessConflict.class, () -> request(id, List.of(r1), true));
        clock.now = clock.now.plusSeconds(86401);
        reading.evaluateDue();
        assertEquals("OPEN", text(storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(r2,
                text(task(id), "currentPublishedVersionId"))), "state"), "Unknown withdrawal is still an active reminder");
        channel.faults.clear();
        request(id, List.of(r2), true);
        int before = channel.withdrawalCalls.get();
        withdrawals.dispatchTenant("mirror", id);
        assertEquals(before + 1, channel.withdrawalCalls.get());
        assertEquals("WITHDRAWN", text(task(id), "state"));
        withdrawals.recordResult("mirror", old, new ReminderChannel.WithdrawalResult("FAILED", "late-fail-" + key(), clock.now, "LATE"));
        String successful = text(personResult(r2), "latestWithdrawalId");
        withdrawals.recordResult("mirror", successful, new ReminderChannel.WithdrawalResult("FAILED", "late-success-fail-" + key(), clock.now, "IGNORED_LATE_FAILURE"));
        var history = withdrawals.history(unit(), id, 0, 20).items().stream().filter(row -> row.id().equals(successful)).findFirst().orElseThrow();
        assertEquals("WITHDRAWN", history.state());
        assertTrue(history.results().stream().anyMatch(event -> event.outcome().equals("FAILED")), "Ignored callbacks must still remain in history");
        assertEquals("WITHDRAWN", text(personResult(r2), "withdrawalState"));
        assertEquals("WITHDRAWN", text(task(id), "state"));
    }

    @Test
    void withdrawingOnlyOnePersonIsPartialAndLaterRequestsMayWithdrawTheRemainingPeople() {
        String id = create(2, true);
        String r1 = recipient(id, "demo-person-001");
        String r2 = recipient(id, "demo-person-009");
        request(id, List.of(r1), false);
        withdrawals.dispatchTenant("mirror", id);
        assertEquals("PARTIAL_WITHDRAWN", text(task(id), "state"));
        assertEquals("NONE", text(personResult(r2), "withdrawalState"));
        request(id, List.of(r2), false);
        withdrawals.dispatchTenant("mirror", id);
        assertEquals("WITHDRAWN", text(task(id), "state"));
    }

    @Test
    void expiryRecoveryReusesAPersistedChannelWithdrawalRequest() {
        String id = create(1, true);
        String recipient = recipient(id, "demo-person-001");
        request(id, List.of(recipient), false);
        String withdrawalId = text(personResult(recipient), "latestWithdrawalId");
        channel.crashAfterWithdrawal = true;
        Instant confirmedAt = clock.now;
        assertThrows(DeliveryWorkflowTest.SimulatedProcessCrash.class, () -> withdrawals.dispatchTenant("mirror", id));
        assertEquals("REQUESTED", text(personResult(recipient), "withdrawalState"));
        assertEquals(0, withdrawals.dispatchTenant("mirror", id));
        clock.now = clock.now.plusSeconds(61);
        assertEquals(1, withdrawals.dispatchTenant("mirror", id));
        var record = storage.getObject(CONTEXT, "WithdrawalRecord", withdrawalId);
        assertEquals("WITHDRAWN", text(record, "state"));
        assertEquals(confirmedAt.toString(), text(record, "completedAt"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mirror_mock_withdrawals WHERE request_key=?", Integer.class, withdrawalId));
    }

    @Test
    void cancellationBarrierPreventsAnInFlightSendFromAppearingAfterSuccessfulWithdrawal() throws Exception {
        String id = create(2, false);
        channel.entered = new CountDownLatch(1);
        channel.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(() -> delivery.dispatchMock(unit(), id, task(id).version()));
            try {
                assertTrue(channel.entered.await(10, TimeUnit.SECONDS));
                var inFlight = channel.blockedRequest.get();
                request(id, List.of(inFlight.recipientId()), false);
                withdrawals.dispatchTenant("mirror", id);
                assertEquals("WITHDRAWN", text(personResult(inFlight.recipientId()), "withdrawalState"));
                channel.release.countDown();
                sending.get(10, TimeUnit.SECONDS);
                assertEquals("PARTIAL_WITHDRAWN", text(task(id), "state"));
                assertEquals("FAILED", text(personResult(inFlight.recipientId()), "deliveryState"));
                assertEquals("MOCK_WITHDRAWN", jdbc.queryForObject("SELECT error_code FROM mirror_mock_deliveries WHERE request_key=?", String.class, inFlight.requestKey()));
                assertEquals(1, delivery.recipients(unit(), id).stream().filter(row -> row.deliveryState().equals("DELIVERED")).count());
            } finally { channel.release.countDown(); }
        }
    }

    @Test
    void aPersonNotYetSubmittedIsCancelledLocallyAndOtherPeopleContinueSending() throws Exception {
        String id = create(2, false);
        channel.entered = new CountDownLatch(1);
        channel.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(() -> delivery.dispatchMock(unit(), id, task(id).version()));
            try {
                assertTrue(channel.entered.await(10, TimeUnit.SECONDS));
                String first = channel.blockedRequest.get().recipientId();
                String second = delivery.recipients(unit(), id).stream().map(DeliveryService.RecipientView::id).filter(r -> !r.equals(first)).findFirst().orElseThrow();
                int calls = channel.withdrawalCalls.get();
                request(id, List.of(second), false);
                withdrawals.dispatchTenant("mirror", id);
                assertEquals(calls, channel.withdrawalCalls.get());
                assertEquals("PENDING", text(personResult(second), "deliveryState"));
                assertEquals("WITHDRAWN", text(personResult(second), "withdrawalState"));
                channel.release.countDown();
                sending.get(10, TimeUnit.SECONDS);
                assertEquals("DELIVERED", text(personResult(first), "deliveryState"));
                assertEquals("", text(personResult(second), "latestAttemptId"));
            } finally { channel.release.countDown(); }
        }
    }

    @Test
    void unsupportedChannelWithdrawalIsAnExplicitFailureInsteadOfInventedSuccess() {
        String id = create(1, true);
        String recipient = recipient(id, "demo-person-001");
        channel.faults.put(recipient, "UNSUPPORTED");
        request(id, List.of(recipient), false);
        withdrawals.dispatchTenant("mirror", id);
        assertEquals("WITHDRAW_FAILED", text(task(id), "state"));
        assertEquals("FAILED", text(personResult(recipient), "withdrawalState"));
        assertEquals("WITHDRAWAL_UNSUPPORTED", text(storage.getObject(CONTEXT, "WithdrawalRecord",
                text(personResult(recipient), "latestWithdrawalId")), "errorCode"));
    }

    @Test
    void aLateSuccessfulWithdrawalMakesAQueuedRetryUnnecessary() {
        String id = create(1, true);
        String recipient = recipient(id, "demo-person-001");
        channel.faults.put(recipient, "UNKNOWN");
        request(id, List.of(recipient), false);
        withdrawals.dispatchTenant("mirror", id);
        String old = text(personResult(recipient), "latestWithdrawalId");
        request(id, List.of(recipient), true);
        var result = channel.delegate.withdraw(new ReminderChannel.WithdrawalRequest("mirror", old, recipient,
                List.of(text(personResult(recipient), "latestAttemptId")), "内容需撤回"));
        withdrawals.recordResult("mirror", old, result);
        int calls = channel.withdrawalCalls.get();
        withdrawals.dispatchTenant("mirror", id);
        assertEquals(calls, channel.withdrawalCalls.get(), "Already confirmed withdrawal must not be repeated remotely");
        assertEquals("WITHDRAWN", text(task(id), "state"));
    }

    @Test
    void withdrawalCancelsAnApprovedButUnpublishedRevision() {
        String id = create(1, true);
        String original = text(task(id), "currentPublishedVersionId");
        var draft = revisions.save(unit(), id, new ReminderRevisionService.Draft(task(id).version(), "未发布修订", "<p>新正文</p>"), key());
        revisions.confirm(unit(), id, new ReminderRevisionService.Confirmation(task(id).version(), draft.get("contentDigest").toString(), true, List.of()), key());
        revisions.submit(unit(), id, task(id).version(), key());
        revisions.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(task(id).version(), "APPROVE", ""), key());
        request(id, List.of(recipient(id, "demo-person-001")), false);
        assertEquals(0, revisions.publishApproved());
        assertEquals(original, text(task(id), "currentPublishedVersionId"));
        assertEquals("WITHDRAWN", ReminderService.revisionState(task(id)));
        withdrawals.dispatchTenant("mirror", id);
    }

    @Test
    void lateFullWithdrawalAlsoCancelsARevisionCreatedAfterTheUnknownResult() {
        String id = create(1, true);
        String recipient = recipient(id, "demo-person-001");
        channel.faults.put(recipient, "UNKNOWN");
        request(id, List.of(recipient), false);
        withdrawals.dispatchTenant("mirror", id);
        String withdrawal = text(personResult(recipient), "latestWithdrawalId");
        var draft = revisions.save(unit(), id, new ReminderRevisionService.Draft(task(id).version(), "后来的修订", "<p>新内容</p>"), key());
        revisions.confirm(unit(), id, new ReminderRevisionService.Confirmation(task(id).version(), draft.get("contentDigest").toString(), true, List.of()), key());
        revisions.submit(unit(), id, task(id).version(), key());
        revisions.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(task(id).version(), "APPROVE", ""), key());
        var result = channel.delegate.withdraw(new ReminderChannel.WithdrawalRequest("mirror", withdrawal, recipient,
                List.of(text(personResult(recipient), "latestAttemptId")), "内容需撤回"));
        withdrawals.recordResult("mirror", withdrawal, result);
        assertEquals("WITHDRAWN", text(task(id), "state"));
        assertEquals("WITHDRAWN", ReminderService.revisionState(task(id)));
        assertEquals(0, revisions.publishApproved());
    }

    @Test
    void withdrawalStopsAPendingRevisionAndRejectsCrossTaskAndUnauthorizedRequests() {
        String id = create(1, true);
        String other = create(1, true);
        String recipient = recipient(id, "demo-person-001");
        assertThrows(BusinessConflict.class, () -> request(id, List.of(recipient, recipient(other, "demo-person-001")), false));
        assertEquals("NONE", text(personResult(recipient), "withdrawalState"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> withdrawals.request(accounts.actor("reviewer"), id,
                new WithdrawalService.Request(task(id).version(), List.of(recipient), "原因"), key(), false));
        assertThrows(IllegalArgumentException.class, () -> withdrawals.request(unit(), id,
                new WithdrawalService.Request(task(id).version(), List.of(recipient), ""), key(), false));
        var draft = revisions.save(unit(), id, new ReminderRevisionService.Draft(task(id).version(), "修订", "<p>修订正文</p>"), key());
        revisions.confirm(unit(), id, new ReminderRevisionService.Confirmation(task(id).version(), draft.get("contentDigest").toString(), true, List.of()), key());
        revisions.submit(unit(), id, task(id).version(), key());
        String roundId = text(task(id), "activeReviewId");
        request(id, List.of(recipient), false);
        assertEquals("WITHDRAWN", ReminderService.revisionState(task(id)));
        assertEquals("WITHDRAWN", text(storage.getObject(CONTEXT, "ReviewRound", roundId), "state"));
        withdrawals.dispatchTenant("mirror", id);
        assertEquals(0, revisions.publishApproved());
    }
}
