package gov.objectlibrary.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_delivery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.delivery-mode=mock", "mirror.scheduling-enabled=false"})
@AutoConfigureMockMvc
class DeliveryWorkflowTest {
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "delivery-test");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StorageProvider storage;
    @Autowired Accounts accounts;
    @Autowired DeliveryService delivery;
    @Autowired TestClock clock;
    @Autowired TestChannel channel;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class Config {
        @Bean @Primary TestClock deliveryClock() { return new TestClock(); }
        @Bean @Primary TestChannel testChannel(TestClock clock) { return new TestChannel(clock); }
    }

    static class TestClock extends Clock {
        Instant now = Instant.parse("2035-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    static class SimulatedProcessCrash extends Error {}

    static class TestChannel implements ReminderChannel {
        final TestClock clock;
        final Map<String, String> outcomes = new ConcurrentHashMap<>();
        final Map<String, Result> accepted = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        boolean crashAfterAccept;

        TestChannel(TestClock clock) { this.clock = clock; }
        @Override public String mode() { return "mock"; }
        @Override public Result send(Request request) {
            calls.incrementAndGet();
            var result = accepted.computeIfAbsent(request.requestKey(), key -> new Result(
                    outcomes.getOrDefault(request.recipientId(), "DELIVERED"), "test-" + key, clock.instant(), ""));
            if (crashAfterAccept) {
                crashAfterAccept = false;
                throw new SimulatedProcessCrash();
            }
            return result;
        }
    }

    @BeforeEach
    void reset() {
        clock.now = Instant.parse("2035-01-01T00:00:00Z");
        channel.outcomes.clear();
        channel.crashAfterAccept = false;
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", username, "password", "TestOnlyPassword-123"))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }

    private JsonNode postJson(MockHttpSession session, String path, Object body) throws Exception {
        String response = mvc.perform(post(path).session(session).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private String approved(List<String> people, Instant plannedAt) throws Exception {
        var unit = login("unit");
        var draft = new HashMap<String, Object>(Map.of("expectedVersion", 0, "title", "发送验证 " + UUID.randomUUID(),
                "bodyHtml", "<p>请依规履职。</p>", "category", "履责", "readingWindow", "1d", "restoreIds", List.of(),
                "filter", Map.of("organizationIds", List.of(), "tagIds", List.of(), "tagOperator", "ANY",
                        "personIds", people, "excludedIds", List.of())));
        if (plannedAt != null) draft.put("plannedAt", plannedAt.toString());
        var saved = postJson(unit, "/api/reminders", draft);
        String task = saved.path("id").asText();
        var confirmed = postJson(unit, "/api/reminders/" + task + "/confirm", Map.of(
                "expectedVersion", saved.path("version").asLong(), "selectionDigest", saved.path("selectionDigest").asText(),
                "recipientCount", people.size(), "singleRecipientAcknowledged", true,
                "contentDigest", saved.path("contentDigest").asText(), "contentAcknowledged", true, "duplicateAcknowledged", true));
        var submitted = postJson(unit, "/api/reminders/" + task + "/submit", Map.of("expectedVersion", confirmed.path("version").asLong()));
        postJson(login("reviewer"), "/api/reminders/" + task + "/review", Map.of(
                "expectedVersion", submitted.path("version").asLong(), "decision", "APPROVE", "comment", "已核对"));
        return task;
    }

    private String recipient(String task, String person) {
        return "recipient-" + BusinessCommands.hash(task + "/" + person);
    }

    private int dispatch(String task) {
        var record = storage.getObject(CONTEXT, "ReminderTask", task);
        return ((Number) delivery.dispatchMock(accounts.actor("unit"), task, record.version()).get("attempts")).intValue();
    }

    private String state(String type, String id, String field) {
        return text(storage.getObject(CONTEXT, type, id), field);
    }

    private String attempt(String recipient) {
        return state("RecipientRecord", recipient, "latestAttemptId");
    }

    @Test
    void waitsForDueTimeAndRetriesOnlyFailedAndUnknownWithoutResettingDeadlines() throws Exception {
        String task = approved(List.of("demo-person-001", "demo-person-002", "demo-person-009"), clock.now.plusSeconds(3600));
        String r1 = recipient(task, "demo-person-001");
        String r2 = recipient(task, "demo-person-002");
        String r3 = recipient(task, "demo-person-009");
        channel.outcomes.put(r2, "FAILED");
        channel.outcomes.put(r3, "UNKNOWN");
        assertEquals(0, dispatch(task));
        assertEquals("PENDING", state("RecipientRecord", r1, "deliveryState"));
        clock.now = clock.now.plusSeconds(3600);
        assertEquals(3, dispatch(task));
        assertEquals("PARTIAL_FAILED", state("ReminderTask", task, "state"));
        String firstDeadline = state("RecipientRecord", r1, "deadlineAt");
        assertEquals(clock.now.plusSeconds(86400).toString(), firstDeadline);
        assertEquals("", state("RecipientRecord", r2, "deadlineAt"));
        var record = storage.getObject(CONTEXT, "ReminderTask", task);
        String key = UUID.randomUUID().toString();
        var retry = delivery.retry(accounts.actor("unit"), task, record.version(), key);
        assertEquals(2, retry.get("retryCount"));
        assertEquals(retry, delivery.retry(accounts.actor("unit"), task, record.version(), key));
        channel.outcomes.clear();
        clock.now = clock.now.plusSeconds(7200);
        assertEquals(2, dispatch(task));
        assertEquals(0, dispatch(task));
        assertEquals("ALL_SUCCESS", state("ReminderTask", task, "state"));
        assertEquals(firstDeadline, state("RecipientRecord", r1, "deadlineAt"));
        assertEquals(clock.now.plusSeconds(86400).toString(), state("RecipientRecord", r2, "deadlineAt"));
        assertEquals(1, storage.getLinks(CONTEXT, new EntityKey("RecipientRecord", r1), "RecipientHasDeliveryAttempt",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
        var view = delivery.recipients(accounts.actor("unit"), task);
        assertTrue(view.stream().allMatch(r -> r.readState().equals("UNREAD") && r.channelMode().equals("mock")));
    }

    @Test
    void recoversAnExpiredWorkerLeaseUsingTheSameChannelRequestAndOriginalSuccessTime() throws Exception {
        String task = approved(List.of("demo-person-001"), null);
        String recipient = recipient(task, "demo-person-001");
        channel.crashAfterAccept = true;
        int before = channel.calls.get();
        assertThrows(SimulatedProcessCrash.class, () -> dispatch(task));
        String originalAttempt = attempt(recipient);
        assertEquals("SUBMITTED", state("RecipientRecord", recipient, "deliveryState"));
        assertEquals(0, dispatch(task), "A live lease cannot be stolen");
        Instant actualSuccess = clock.now;
        clock.now = clock.now.plusSeconds(61);
        assertEquals(1, dispatch(task));
        assertEquals(before + 2, channel.calls.get(), "Recovery replays the same idempotent external request");
        assertEquals(originalAttempt, attempt(recipient));
        assertEquals(actualSuccess.toString(), state("RecipientRecord", recipient, "firstDeliveredAt"));
        assertEquals(actualSuccess.plusSeconds(86400).toString(), state("RecipientRecord", recipient, "deadlineAt"));
        assertEquals("ALL_SUCCESS", state("ReminderTask", task, "state"));
    }

    @Test
    void lateSuccessCorrectsTotalsButFailuresCannotEraseSuccessOrExtendTheDeadline() throws Exception {
        String task = approved(List.of("demo-person-001", "demo-person-002"), null);
        String r1 = recipient(task, "demo-person-001");
        String r2 = recipient(task, "demo-person-002");
        channel.outcomes.put(r1, "UNKNOWN");
        channel.outcomes.put(r2, "FAILED");
        assertEquals(2, dispatch(task));
        assertEquals("ALL_FAILED", state("ReminderTask", task, "state"));
        var receipt = new ReminderChannel.Result("DELIVERED", "late-" + UUID.randomUUID(), clock.now, "");
        delivery.recordResult("mirror", r1, attempt(r1), receipt);
        delivery.recordResult("mirror", r1, attempt(r1), receipt);
        assertEquals("PARTIAL_FAILED", state("ReminderTask", task, "state"));
        String deadline = state("RecipientRecord", r1, "deadlineAt");
        clock.now = clock.now.plusSeconds(3600);
        delivery.recordResult("mirror", r1, attempt(r1), new ReminderChannel.Result("FAILED", "failure-" + UUID.randomUUID(), clock.now, "LATE"));
        delivery.recordResult("mirror", r1, attempt(r1), new ReminderChannel.Result("DELIVERED", "duplicate-" + UUID.randomUUID(), clock.now, ""));
        assertEquals("DELIVERED", state("RecipientRecord", r1, "deliveryState"));
        assertEquals(deadline, state("RecipientRecord", r1, "deadlineAt"));
        delivery.recordResult("mirror", r2, attempt(r2), new ReminderChannel.Result("DELIVERED", "late-" + UUID.randomUUID(), clock.now, ""));
        assertEquals("ALL_SUCCESS", state("ReminderTask", task, "state"));
        assertThrows(BusinessConflict.class, () -> delivery.recordResult("mirror", r2, attempt(r1), receipt));
        assertThrows(BusinessConflict.class, () -> delivery.recordResult("other-tenant", r1, attempt(r1), receipt));
    }

    @Test
    void anOldAttemptFailureCannotOverwriteANewerUnknownAttempt() throws Exception {
        String task = approved(List.of("demo-person-001"), null);
        String recipient = recipient(task, "demo-person-001");
        channel.outcomes.put(recipient, "UNKNOWN");
        dispatch(task);
        String oldAttempt = attempt(recipient);
        var record = storage.getObject(CONTEXT, "ReminderTask", task);
        delivery.retry(accounts.actor("unit"), task, record.version(), UUID.randomUUID().toString());
        clock.now = clock.now.plusSeconds(10);
        dispatch(task);
        assertNotEquals(oldAttempt, attempt(recipient));
        delivery.recordResult("mirror", recipient, oldAttempt,
                new ReminderChannel.Result("FAILED", "old-" + UUID.randomUUID(), clock.now, "OLD_ATTEMPT"));
        assertEquals("UNKNOWN", state("RecipientRecord", recipient, "deliveryState"));
    }

    @Test
    void endpointsRecheckPermissionsAndDisabledAccountsCannotReplayRetry() throws Exception {
        String task = approved(List.of("demo-person-001"), null);
        String recipient = recipient(task, "demo-person-001");
        channel.outcomes.put(recipient, "FAILED");
        var reviewer = login("reviewer");
        mvc.perform(get("/api/reminders/" + task + "/delivery").session(reviewer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/reminders/" + task + "/mock-dispatch").session(reviewer).with(csrf())
                .contentType("application/json").content("{\"expectedVersion\":1}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/reminders/" + task + "/delivery")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reminders/missing-task/delivery").session(login("unit"))).andExpect(status().isForbidden());
        dispatch(task);
        var record = storage.getObject(CONTEXT, "ReminderTask", task);
        var actor = accounts.actor("unit");
        String key = UUID.randomUUID().toString();
        delivery.retry(actor, task, record.version(), key);
        jdbc.update("UPDATE mirror_accounts SET enabled=FALSE WHERE username='unit'");
        try {
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> delivery.retry(actor, task, record.version(), key));
        } finally {
            jdbc.update("UPDATE mirror_accounts SET enabled=TRUE WHERE username='unit'");
        }
        dispatch(task);
    }

    @Test
    void ignoresHistoricalRecipientsOutsideTheApprovedVersionAndRejectsATamperedReview() throws Exception {
        String task = approved(List.of("demo-person-001"), null);
        String historical = recipient(task, "demo-person-002");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var old = tx.createObject("RecipientRecord", historical, Map.of("taskId", task, "personId", "demo-person-002",
                    "deliveryState", "PENDING", "withdrawalState", "NONE", "readingWindow", "1d"));
            tx.createLink("TaskHasRecipient", "task-" + historical, new EntityKey("ReminderTask", task), old.key(), Map.of());
            tx.commit();
        }
        assertEquals(1, dispatch(task));
        assertEquals("PENDING", state("RecipientRecord", historical, "deliveryState"));
        assertEquals("", attempt(historical));
        assertEquals(1, delivery.recipients(accounts.actor("unit"), task).size());

        String tampered = approved(List.of("demo-person-001"), null);
        String roundId = state("ReminderTask", tampered, "activeReviewId");
        var round = storage.getObject(CONTEXT, "ReviewRound", roundId);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(round.type(), round.id(), Map.of("snapshotDigest", "does-not-match"), round.version());
            tx.commit();
        }
        assertEquals(0, dispatch(tampered));
        assertEquals("PENDING", state("RecipientRecord", recipient(tampered, "demo-person-001"), "deliveryState"));
    }

    @Test
    void mockAdapterPersistsOutcomeAcrossInstancesAndRejectsRequestKeyReuse() {
        var configuration = new ChannelConfiguration();
        var adapter = configuration.reminderChannel(jdbc, clock, "mock");
        String requestKey = UUID.randomUUID().toString();
        var request = new ReminderChannel.Request("adapter-test", requestKey, "recipient", "mock:person", "version", "标题");
        var result = adapter.send(request);
        clock.now = clock.now.plusSeconds(120);
        var reopened = configuration.reminderChannel(jdbc, clock, "mock");
        assertEquals(result, reopened.send(request));
        assertThrows(BusinessConflict.class, () -> reopened.send(new ReminderChannel.Request("adapter-test", requestKey,
                "another-recipient", "mock:person", "version", "标题")));
        var realIdentity = reopened.send(new ReminderChannel.Request("adapter-test", UUID.randomUUID().toString(),
                "recipient", "identity:protected-reference", "version", "标题"));
        assertEquals("FAILED", realIdentity.state());
        assertEquals("MOCK_IDENTITY_REQUIRED", realIdentity.errorCode());
        assertThrows(IllegalStateException.class, () -> configuration.reminderChannel(jdbc, clock, "disabled").send(request));
        assertThrows(IllegalArgumentException.class, () -> configuration.reminderChannel(jdbc, clock, "production"));
    }
}
