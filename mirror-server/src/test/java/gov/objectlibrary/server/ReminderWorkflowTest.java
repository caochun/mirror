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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_reminders;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ReminderWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StorageProvider storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReminderService reminders;
    @Autowired MutableClock clock;
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "test");

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach void resetTime() { clock.now = Instant.parse("2030-01-01T00:00:00Z"); }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) request(post("/api/auth/login"), null,
                Map.of("username", username, "password", "TestOnlyPassword-123"), key())
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }

    private String key() { return UUID.randomUUID().toString(); }
    private ResultActions request(MockHttpServletRequestBuilder request, MockHttpSession session, Object body, String key) throws Exception {
        if (session != null) request.session(session);
        return mvc.perform(request.with(csrf()).header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(body)));
    }
    private JsonNode node(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private Map<String, Object> filter(List<String> roots, List<String> people, List<String> excluded) {
        return Map.of("organizationIds", roots, "tagIds", List.of(), "tagOperator", "ANY", "personIds", people, "excludedIds", excluded);
    }
    private Map<String, Object> draft() {
        return new HashMap<>(Map.of("expectedVersion", 0, "title", "岗位廉洁提醒", "bodyHtml", "<p>请严格依规履职。</p>",
                "category", "履责", "readingWindow", "1d", "restoreIds", List.of(),
                "filter", filter(List.of(), List.of("demo-person-001", "demo-person-009"), List.of())));
    }
    private JsonNode create(MockHttpSession session, Map<String, Object> draft) throws Exception {
        return node(request(post("/api/reminders"), session, draft, key()));
    }
    private JsonNode confirm(MockHttpSession session, JsonNode saved) throws Exception {
        return node(request(post("/api/reminders/" + saved.path("id").asText() + "/confirm"), session,
                Map.of("expectedVersion", saved.path("version").asLong(), "selectionDigest", saved.path("selectionDigest").asText(),
                        "recipientCount", saved.path("recipientCount").asInt(), "singleRecipientAcknowledged", true,
                        "contentDigest", saved.path("contentDigest").asText(), "contentAcknowledged", true,
                        "duplicateAcknowledged", true), key()));
    }
    private JsonNode submit(MockHttpSession session, JsonNode saved) throws Exception {
        JsonNode confirmed = confirm(session, saved);
        return node(request(post("/api/reminders/" + saved.path("id").asText() + "/submit"), session,
                Map.of("expectedVersion", confirmed.path("version").asLong()), key()));
    }

    @Test
    void freezesReviewedRosterAndQueuesExactlyOneJob() throws Exception {
        var unit = login("unit");
        var reviewer = login("reviewer");
        JsonNode saved = create(unit, draft());
        String id = saved.path("id").asText();
        JsonNode submitted = submit(unit, saved);
        assertEquals(2, submitted.path("recipientCount").asInt());
        // Current personnel changes must not drift the frozen roster shown to the reviewer.
        var person = storage.getObject(CONTEXT, "Person", "demo-person-009");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(person.type(), person.id(), Map.of("name", "后来更名"), person.version());
            tx.commit();
        }
        var detail = node(mvc.perform(get("/api/reminders/" + id).session(reviewer)));
        assertEquals("演示人员09", detail.path("entries").get(1).path("name").asText());
        String commandId = key();
        var decision = Map.of("expectedVersion", submitted.path("version").asLong(), "decision", "APPROVE", "comment", "已核对");
        var approved = node(request(post("/api/reminders/" + id + "/review"), reviewer, decision, commandId));
        assertEquals("APPROVED_WAITING", approved.path("state").asText());
        assertEquals(approved, node(request(post("/api/reminders/" + id + "/review"), reviewer, decision, commandId)));
        String versionId = storage.getObject(CONTEXT, "ReminderTask", id).properties().get("pendingVersionId").toString();
        assertEquals("QUEUED", storage.getObject(CONTEXT, "ReminderSendJob", "send-" + versionId).properties().get("state"));
        assertEquals(2, storage.getLinks(CONTEXT, new EntityKey("ReminderTask", id), "TaskHasRecipient",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
        request(put("/api/reminders/" + id), unit, draft(), key()).andExpect(status().isConflict());
    }

    @Test
    void selectionRetainsExclusionsAndDetectsStaleConfirmation() throws Exception {
        var unit = login("unit");
        var draft = draft();
        draft.put("filter", filter(List.of("demo-a"), List.of("demo-person-001"), List.of("demo-person-002")));
        var saved = create(unit, draft);
        assertEquals(15, saved.path("recipientCount").asInt());
        draft.put("expectedVersion", saved.path("version").asLong());
        draft.put("filter", filter(List.of("demo-a"), List.of(), List.of()));
        saved = node(request(put("/api/reminders/" + saved.path("id").asText()), unit, draft, key()));
        assertEquals(15, saved.path("recipientCount").asInt(), "Recomputation cannot restore explicit exclusion");
        var confirmed = confirm(unit, saved);
        var person = storage.getObject(CONTEXT, "Person", "demo-person-003");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(person.type(), person.id(), Map.of("name", "资料已变更"), person.version());
            tx.commit();
        }
        request(post("/api/reminders/" + saved.path("id").asText() + "/submit"), unit,
                Map.of("expectedVersion", confirmed.path("version").asLong()), key()).andExpect(status().isConflict());
    }

    @Test
    void noReviewerSelfReviewAndCrossUnitReviewAreRejected() throws Exception {
        var unit = login("unit");
        var reviewer = login("reviewer");
        var saved = create(unit, draft());
        var confirmed = confirm(unit, saved);
        jdbc.update("UPDATE mirror_accounts SET enabled=FALSE WHERE username='reviewer'");
        try {
            request(post("/api/reminders/" + saved.path("id").asText() + "/submit"), unit,
                    Map.of("expectedVersion", confirmed.path("version").asLong()), key()).andExpect(status().isConflict());
        } finally { jdbc.update("UPDATE mirror_accounts SET enabled=TRUE WHERE username='reviewer'"); }
        var submitted = node(request(post("/api/reminders/" + saved.path("id").asText() + "/submit"), unit,
                Map.of("expectedVersion", confirmed.path("version").asLong()), key()));
        String path = "/api/reminders/" + saved.path("id").asText() + "/review";
        var approval = Map.of("expectedVersion", submitted.path("version").asLong(), "decision", "APPROVE", "comment", "");
        jdbc.update("INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN','REMINDER_REVIEW')");
        try { request(post(path), unit, approval, key()).andExpect(status().isForbidden()); }
        finally { jdbc.update("DELETE FROM mirror_role_permissions WHERE role_name='UNIT_ADMIN' AND permission_name='REMINDER_REVIEW'"); }
        jdbc.update("UPDATE mirror_accounts SET organization_id='city' WHERE username='reviewer'");
        try { request(post(path), reviewer, approval, key()).andExpect(status().isForbidden()); }
        finally { jdbc.update("UPDATE mirror_accounts SET organization_id='demo-a' WHERE username='reviewer'"); }
        request(post(path), reviewer, Map.of("expectedVersion", submitted.path("version").asLong(),
                "decision", "REJECT", "comment", ""), key()).andExpect(status().isBadRequest());
    }

    @Test
    void expiryCannotBeApprovedAndScheduledJobCanBeCancelledBeforeDispatch() throws Exception {
        var unit = login("unit");
        var reviewer = login("reviewer");
        var payload = draft();
        payload.put("plannedAt", clock.instant().plusSeconds(300).toString());
        var saved = create(unit, payload);
        var submitted = submit(unit, saved);
        clock.now = clock.now.plusSeconds(301);
        assertTrue(reminders.expireDueReviews() >= 1);
        assertEquals("REVIEW_EXPIRED", storage.getObject(CONTEXT, "ReminderTask", saved.path("id").asText()).properties().get("state"));
        request(post("/api/reminders/" + saved.path("id").asText() + "/review"), reviewer,
                Map.of("expectedVersion", submitted.path("version").asLong(), "decision", "APPROVE", "comment", ""), key())
                .andExpect(status().isConflict());
        payload.put("plannedAt", clock.instant().plusSeconds(300).toString());
        saved = create(unit, payload);
        submitted = submit(unit, saved);
        var approved = node(request(post("/api/reminders/" + saved.path("id").asText() + "/review"), reviewer,
                Map.of("expectedVersion", submitted.path("version").asLong(), "decision", "APPROVE", "comment", ""), key()));
        node(request(post("/api/reminders/" + saved.path("id").asText() + "/cancel"), unit,
                Map.of("expectedVersion", approved.path("version").asLong(), "reason", "调整安排"), key()));
        assertEquals("CANCELLED", storage.getObject(CONTEXT, "ReminderSendJob", "send-" + saved.path("id").asText() + "-v1").properties().get("state"));
    }

    @Test
    void withdrawAndResubmitPreservePriorRoundAndStableRecipientIds() throws Exception {
        var unit = login("unit");
        var saved = create(unit, draft());
        String id = saved.path("id").asText();
        var submitted = submit(unit, saved);
        var withdrawn = node(request(post("/api/reminders/" + id + "/withdraw-review"), unit,
                Map.of("expectedVersion", submitted.path("version").asLong()), key()));
        var changed = draft();
        changed.put("expectedVersion", withdrawn.path("version").asLong());
        changed.put("title", "调整后的提醒");
        saved = node(request(put("/api/reminders/" + id), unit, changed, key()));
        submit(unit, saved);
        var rounds = node(mvc.perform(get("/api/reminders/" + id).session(unit))).path("rounds");
        assertEquals(2, rounds.size());
        assertEquals("WITHDRAWN", rounds.get(0).path("state").asText());
        assertEquals("PENDING", rounds.get(1).path("state").asText());
        assertEquals(2, storage.getLinks(CONTEXT, new EntityKey("ReminderTask", id), "TaskHasRecipient",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
    }

    @Test
    void draftSanitizationScopeAndIdempotencyAreEnforced() throws Exception {
        var unit = login("unit");
        var payload = draft();
        payload.put("bodyHtml", "<p onclick='bad()'>安全正文</p><script>alert(1)</script>");
        String commandId = key();
        var saved = node(request(post("/api/reminders"), unit, payload, commandId));
        assertEquals(saved, node(request(post("/api/reminders"), unit, payload, commandId)));
        var detail = node(mvc.perform(get("/api/reminders/" + saved.path("id").asText()).session(unit)));
        assertEquals("<p>安全正文</p>", detail.path("bodyHtml").asText());
        payload.put("filter", filter(List.of(), List.of("demo-person-017"), List.of()));
        request(post("/api/reminders"), unit, payload, key()).andExpect(status().isForbidden());
        payload = draft();
        payload.put("bodyHtml", "<a href='javascript:alert(1)'>危险链接</a>");
        request(post("/api/reminders"), unit, payload, key()).andExpect(status().isConflict());
        payload.put("bodyHtml", "<p>联系电话13800138000</p>");
        request(post("/api/reminders"), unit, payload, key()).andExpect(status().isConflict());
    }
    @Test
    void tagAnyAllAndParentCategoriesSelectOnlyActiveTags() throws Exception {
        var unit = login("unit");
        String prefix = UUID.randomUUID().toString();
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("TagDefinition", prefix + "-parent", Map.of("code", prefix + "-parent", "name", "筛选测试parent", "dimension", "PERSON", "scope", "LONG_TERM", "level", 1, "status", "ACTIVE", "parentId", ""));
            tx.createObject("TagDefinition", prefix + "-a", Map.of("code", prefix + "-a", "name", "筛选测试a", "dimension", "PERSON", "scope", "LONG_TERM", "level", 2, "status", "ACTIVE", "parentId", prefix + "-parent"));
            tx.createObject("TagDefinition", prefix + "-b", Map.of("code", prefix + "-b", "name", "筛选测试b", "dimension", "PERSON", "scope", "LONG_TERM", "level", 1, "status", "ACTIVE", "parentId", ""));
            tx.createObject("PersonTagAssignment", prefix + "-a1", Map.of("personId", "demo-person-001",
                    "tagDefinitionId", prefix + "-a", "state", "ACTIVE", "manualSuppressed", false, "source", "MANUAL", "tagVersion", "fixture-v1", "effectiveFrom", "2026-01-01T00:00:00Z"));
            tx.createObject("PersonTagAssignment", prefix + "-b1", Map.of("personId", "demo-person-001",
                    "tagDefinitionId", prefix + "-b", "state", "ACTIVE", "manualSuppressed", false, "source", "MANUAL", "tagVersion", "fixture-v1", "effectiveFrom", "2026-01-01T00:00:00Z"));
            tx.createObject("PersonTagAssignment", prefix + "-a2", Map.of("personId", "demo-person-002",
                    "tagDefinitionId", prefix + "-a", "state", "SUPPRESSED", "manualSuppressed", true, "source", "MANUAL", "tagVersion", "fixture-v1", "effectiveFrom", "2026-01-01T00:00:00Z"));
            tx.createObject("PersonTagAssignment", prefix + "-b3", Map.of("personId", "demo-person-003",
                    "tagDefinitionId", prefix + "-b", "state", "ACTIVE", "manualSuppressed", false, "source", "MANUAL", "tagVersion", "fixture-v1", "effectiveFrom", "2026-01-01T00:00:00Z"));
            tx.commit();
        }
        var any = new HashMap<String, Object>(filter(List.of("demo-a"), List.of(), List.of()));
        any.put("tagIds", List.of(prefix + "-parent", prefix + "-b"));
        var result = node(request(post("/api/reminders/preview"), unit, any, key()));
        assertEquals(2, result.path("entries").size());
        any.put("tagOperator", "ALL");
        result = node(request(post("/api/reminders/preview"), unit, any, key()));
        assertEquals(1, result.path("entries").size());
        assertEquals("demo-person-001", result.path("entries").get(0).path("personId").asText());
    }

    @Test
    void missingIdentityIsExplainedAndSingleRecipientNeedsExplicitAcknowledgement() throws Exception {
        var unit = login("unit");
        var person = storage.getObject(CONTEXT, "Person", "demo-person-007");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(person.type(), person.id(), Map.of("identityReference", ""), person.version());
            tx.commit();
        }
        var payload = draft();
        payload.put("filter", filter(List.of(), List.of("demo-person-006", "demo-person-007"), List.of()));
        try {
            var saved = create(unit, payload);
            assertEquals(1, saved.path("recipientCount").asInt());
            var detail = node(mvc.perform(get("/api/reminders/" + saved.path("id").asText()).session(unit)));
            assertEquals("接收身份未唯一核实", detail.path("entries").get(1).path("ineligibleReason").asText());
            request(post("/api/reminders/" + saved.path("id").asText() + "/confirm"), unit,
                    Map.of("expectedVersion", saved.path("version").asLong(), "selectionDigest", saved.path("selectionDigest").asText(),
                            "recipientCount", 1, "singleRecipientAcknowledged", false, "contentDigest", saved.path("contentDigest").asText(),
                            "contentAcknowledged", true, "duplicateAcknowledged", true), key()).andExpect(status().isConflict());
            confirm(unit, saved);
        } finally {
            var current = storage.getObject(CONTEXT, "Person", person.id());
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.updateObject(person.type(), person.id(), Map.of("identityReference", person.properties().get("identityReference")), current.version());
                tx.commit();
            }
        }
    }

    @Test
    void inactiveCreatingOrganizationCannotContinueWritingTasks() throws Exception {
        var unit = login("unit");
        var organization = storage.getObject(CONTEXT, "Organization", "demo-a");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(organization.type(), organization.id(), Map.of("status", "INACTIVE"), organization.version());
            tx.commit();
        }
        try {
            request(post("/api/reminders"), unit, draft(), key()).andExpect(status().isConflict());
        } finally {
            var current = storage.getObject(CONTEXT, organization.type(), organization.id());
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.updateObject(current.type(), current.id(), Map.of("status", "ACTIVE"), current.version());
                tx.commit();
            }
        }
    }
}
