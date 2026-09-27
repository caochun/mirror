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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_receiver;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.media-directory=target/receiver-media", "mirror.delivery-mode=mock", "mirror.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(DeliveryWorkflowTest.Config.class)
class ReceiverWorkflowTest {
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "receiver-test");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StorageProvider storage;
    @Autowired Accounts accounts;
    @Autowired MediaService media;
    @Autowired DeliveryService delivery;
    @Autowired ReadingService reading;
    @Autowired ReceiverService receiver;
    @Autowired DeliveryWorkflowTest.TestClock clock;
    @Autowired DeliveryWorkflowTest.TestChannel channel;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        clock.now = Instant.parse("2035-01-01T00:00:00Z");
        channel.outcomes.clear();
        channel.crashAfterAccept = false;
    }

    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", name, "password", "TestOnlyPassword-123"))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
    }

    private ResultActions request(MockHttpSession session, String path, Object body) throws Exception {
        return mvc.perform(post(path).session(session).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private JsonNode node(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Setup published(boolean image, String outcome) throws Exception {
        var session = login("unit");
        String body = "<p>本人专属提醒，请依规履职。</p>";
        String asset = "";
        List<String> confirmations = new ArrayList<>();
        if (image) {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
            var result = media.upload(accounts.actor("unit"), new MockMultipartFile("file", "example.png", "image/png", bytes.toByteArray()), UUID.randomUUID().toString());
            asset = result.get("id").toString();
            body += "<img data-media-id=\"" + asset + "\" alt=\"提醒配图\">";
            confirmations.add(asset + ":0:" + result.get("digest"));
        }
        var saved = node(request(session, "/api/reminders", Map.of("expectedVersion", 0, "title", "本人阅读验证 " + UUID.randomUUID(),
                "bodyHtml", body, "category", "履责", "readingWindow", "1d", "restoreIds", List.of(),
                "filter", Map.of("organizationIds", List.of(), "tagIds", List.of(), "tagOperator", "ANY",
                        "personIds", List.of("demo-person-001"), "excludedIds", List.of()))));
        String task = saved.path("id").asText();
        var confirmed = node(request(session, "/api/reminders/" + task + "/confirm", Map.of("expectedVersion", saved.path("version").asLong(),
                "selectionDigest", saved.path("selectionDigest").asText(), "recipientCount", 1, "singleRecipientAcknowledged", true,
                "contentDigest", saved.path("contentDigest").asText(), "contentAcknowledged", true,
                "duplicateAcknowledged", true, "confirmedMediaDigests", confirmations)));
        var submitted = node(request(session, "/api/reminders/" + task + "/submit", Map.of("expectedVersion", confirmed.path("version").asLong())));
        node(request(login("reviewer"), "/api/reminders/" + task + "/review", Map.of("expectedVersion", submitted.path("version").asLong(),
                "decision", "APPROVE", "comment", "核对通过")));
        String recipient = "recipient-" + BusinessCommands.hash(task + "/demo-person-001");
        channel.outcomes.put(recipient, outcome);
        var record = storage.getObject(CONTEXT, "ReminderTask", task);
        delivery.dispatchMock(accounts.actor("unit"), task, record.version());
        return new Setup(session, task, recipient, text(storage.getObject(CONTEXT, "ReminderTask", task), "currentPublishedVersionId"), asset);
    }

    private String ticket(Setup setup) throws Exception {
        String url = node(request(setup.session(), "/api/reminders/" + setup.task() + "/recipients/" + setup.recipient() + "/mock-entry", Map.of()))
                .path("url").asText();
        assertFalse(url.contains(setup.recipient()));
        return url.substring(url.indexOf("#ticket=") + 8);
    }

    private String grant(Setup setup) throws Exception {
        return node(request(setup.session(), "/api/receiver/exchange", Map.of("ticket", ticket(setup)))).path("grantId").asText();
    }

    private JsonNode content(Setup setup, String grant) throws Exception {
        return node(mvc.perform(get("/api/receiver/" + grant + "/content").session(setup.session()))
                .andExpect(header().string("Cache-Control", "no-store")));
    }

    private JsonNode read(Setup setup, String grant, JsonNode content) throws Exception {
        return node(request(setup.session(), "/api/receiver/" + grant + "/read", Map.of("versionId", content.path("versionId").asText(),
                "renderToken", content.path("renderToken").asText(), "bodyRendered", true)));
    }

    private List<org.openfoundry.foundation.spi.LinkRecord> receipts(Setup setup) {
        return storage.getLinks(CONTEXT, new EntityKey("RecipientRecord", setup.recipient()), "RecipientHasReadReceipt",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults());
    }

    @Test
    void ticketIsOneUseSessionBoundAndContentDoesNotExposeAdministrativeData() throws Exception {
        var setup = published(false, "DELIVERED");
        String ticket = ticket(setup);
        request(new MockHttpSession(), "/api/receiver/exchange", Map.of("ticket", ticket)).andExpect(status().isForbidden());
        String grant = node(request(setup.session(), "/api/receiver/exchange", Map.of("ticket", ticket))).path("grantId").asText();
        request(setup.session(), "/api/receiver/exchange", Map.of("ticket", ticket)).andExpect(status().isForbidden());
        mvc.perform(get("/api/receiver/" + grant + "/content")).andExpect(status().isForbidden());
        mvc.perform(get("/api/receiver/" + grant + "/content").session(new MockHttpSession())).andExpect(status().isForbidden());
        var content = content(setup, grant);
        assertEquals(setup.version(), content.path("versionId").asText());
        assertTrue(content.path("bodyHtml").asText().contains("本人专属提醒"));
        for (String field : List.of("personId", "recipientId", "tenantId", "entries", "tags", "rounds", "reviewedBy", "identityReference")) {
            assertFalse(content.has(field), field);
        }
        assertTrue(receipts(setup).isEmpty(), "Fetching content alone is not a rendered read");
        mvc.perform(post("/api/receiver/" + grant + "/read").session(setup.session()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("versionId", setup.version(), "renderToken", content.path("renderToken").asText(), "bodyRendered", true))))
                .andExpect(status().isForbidden());
        request(setup.session(), "/api/receiver/" + grant + "/read", Map.of("versionId", setup.version(),
                "renderToken", "forged", "bodyRendered", true)).andExpect(status().isForbidden());
        request(setup.session(), "/api/receiver/" + grant + "/read", Map.of("versionId", setup.version(),
                "renderToken", content.path("renderToken").asText(), "bodyRendered", false)).andExpect(status().isConflict());
        var first = read(setup, grant, content);
        clock.now = clock.now.plusSeconds(60);
        assertEquals(first, read(setup, grant, content));
        assertEquals(1, receipts(setup).size());
    }

    @Test
    void differentTabsHaveIndependentGrantsAndMediaAccessIsVersionScoped() throws Exception {
        var one = published(true, "DELIVERED");
        var two = published(true, "DELIVERED");
        // Two reminders in one browser session must never overwrite a global current recipient.
        two = new Setup(one.session(), two.task(), two.recipient(), two.version(), two.media());
        String firstGrant = grant(one);
        String secondGrant = grant(two);
        var first = content(one, firstGrant);
        var second = content(two, secondGrant);
        assertEquals(one.version(), first.path("versionId").asText());
        assertEquals(two.version(), second.path("versionId").asText());
        mvc.perform(get("/api/receiver/" + firstGrant + "/media/" + one.version() + "/" + one.media()).session(one.session()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"));
        mvc.perform(get("/api/receiver/" + firstGrant + "/media/" + one.version() + "/" + two.media()).session(one.session()))
                .andExpect(status().isForbidden());
        request(one.session(), "/api/receiver/" + secondGrant + "/read", Map.of("versionId", two.version(),
                "renderToken", first.path("renderToken").asText(), "bodyRendered", true)).andExpect(status().isConflict());
        read(one, firstGrant, first);
        assertTrue(receipts(two).isEmpty());
        read(two, secondGrant, second);
        assertEquals(1, receipts(one).size());
        assertEquals(1, receipts(two).size());
    }

    @Test
    void imageFailureIsADiagnosticAndDoesNotBlockFirstRead() throws Exception {
        var setup = published(true, "DELIVERED");
        String grant = grant(setup);
        var content = content(setup, grant);
        var report = Map.of("versionId", setup.version(), "category", "IMAGE_FAILURE", "mediaId", setup.media());
        node(request(setup.session(), "/api/receiver/" + grant + "/issues", report));
        node(request(setup.session(), "/api/receiver/" + grant + "/issues", report));
        read(setup, grant, content);
        var issues = storage.getLinks(CONTEXT, new EntityKey("RecipientRecord", setup.recipient()), "IntegrationIssueForRecipient",
                StorageProvider.Direction.INBOUND, QueryOptions.defaults());
        assertEquals(1, issues.size());
        assertEquals("IMAGE_FAILURE", text(storage.getObject(CONTEXT, "IntegrationIssue", issues.getFirst().from().id()), "category"));
        assertEquals(1, receipts(setup).size());
        request(setup.session(), "/api/receiver/" + grant + "/issues", Map.of("versionId", setup.version(), "category", "SCROLL_DEPTH"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentImageDiagnosticAndRepeatedReadCommitOnceWithoutBlockingReading() throws Exception {
        var setup = published(true, "DELIVERED");
        String grant = grant(setup);
        var content = content(setup, grant);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            java.util.concurrent.Callable<Map<String, Object>> render = () -> {
                start.await();
                return receiver.read(grant, setup.session(), setup.version(), content.path("renderToken").asText(), true);
            };
            var first = executor.submit(render);
            var second = executor.submit(render);
            var diagnostic = executor.submit(() -> {
                start.await();
                return receiver.report(grant, setup.session(), setup.version(), "IMAGE_FAILURE", setup.media());
            });
            start.countDown();
            assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(true, diagnostic.get(10, java.util.concurrent.TimeUnit.SECONDS).get("recorded"));
        }
        assertEquals(1, receipts(setup).size());
        assertEquals("READ", delivery.recipients(accounts.actor("unit"), setup.task()).getFirst().readState());
    }

    @Test
    void readingMayPrecedeDeliveryAndTheLateReceiptResolvesTheMismatch() throws Exception {
        var setup = published(false, "UNKNOWN");
        String grant = grant(setup);
        read(setup, grant, content(setup, grant));
        var recipient = storage.getObject(CONTEXT, "RecipientRecord", setup.recipient());
        assertEquals("", text(recipient, "deadlineAt"));
        reading.evaluateDue();
        assertNull(storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(setup.recipient(), setup.version())));
        var issues = storage.getLinks(CONTEXT, recipient.key(), "IntegrationIssueForRecipient", StorageProvider.Direction.INBOUND, QueryOptions.defaults());
        assertEquals(1, issues.size());
        String issueId = issues.getFirst().from().id();
        assertEquals("OPEN", text(storage.getObject(CONTEXT, "IntegrationIssue", issueId), "state"));
        delivery.recordResult("mirror", setup.recipient(), text(recipient, "latestAttemptId"),
                new ReminderChannel.Result("DELIVERED", "late-read-" + UUID.randomUUID(), clock.now, ""));
        assertEquals("RESOLVED", text(storage.getObject(CONTEXT, "IntegrationIssue", issueId), "state"));
        assertEquals(1, receipts(setup).size());
    }

    @Test
    void overdueUsesCurrentSupervisorAndOnlyRecipientReadingResolvesIt() throws Exception {
        var setup = published(false, "DELIVERED");
        clock.now = clock.now.plusSeconds(86401);
        reading.evaluateDue();
        String id = ReadingService.overdueId(setup.recipient(), setup.version());
        var warning = storage.getObject(CONTEXT, "OverdueRecord", id);
        assertEquals("OPEN", text(warning, "state"));
        assertEquals("demo-a", text(warning, "supervisorOrganizationId"));
        reading.evaluateDue();
        assertEquals(1, storage.getEntityHistory(CONTEXT, warning.key()).size());
        var original = storage.getLink(CONTEXT, "PersonBelongsToOrganization", "org-demo-person-001");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.deleteLink(original.type(), original.id(), original.version());
            tx.createLink(original.type(), "transferred-" + UUID.randomUUID(), original.from(), new EntityKey("Organization", "demo-b"), Map.of());
            tx.commit();
        }
        try {
            var unitRows = node(mvc.perform(get("/api/reading?state=OVERDUE").session(setup.session())));
            assertFalse(unitRows.path("items").toString().contains(setup.recipient()));
            var cityRows = node(mvc.perform(get("/api/reading?state=OVERDUE&size=100").session(login("admin"))));
            assertTrue(cityRows.path("items").toString().contains(setup.recipient()));
            assertEquals("demo-a", text(storage.getObject(CONTEXT, "OverdueRecord", id), "supervisorOrganizationId"));
            String grant = grant(setup);
            read(setup, grant, content(setup, grant));
            assertEquals("RESOLVED", text(storage.getObject(CONTEXT, "OverdueRecord", id), "state"));
            assertEquals(2, storage.getEntityHistory(CONTEXT, warning.key()).size());
        } finally {
            var current = storage.getLinks(CONTEXT, original.from(), "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst();
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.deleteLink(current.type(), current.id(), current.version());
                tx.createLink(original.type(), "restored-" + UUID.randomUUID(), original.from(), original.to(), Map.of());
                tx.commit();
            }
        }
    }

    @Test
    void expiredRotatedAndWithdrawnSessionsNeverCreateReadReceipts() throws Exception {
        var setup = published(false, "DELIVERED");
        String ticket = ticket(setup);
        clock.now = clock.now.plusSeconds(121);
        request(setup.session(), "/api/receiver/exchange", Map.of("ticket", ticket)).andExpect(status().isForbidden());
        String grant = grant(setup);
        var content = content(setup, grant);
        setup.session().changeSessionId();
        request(setup.session(), "/api/receiver/" + grant + "/read", Map.of("versionId", setup.version(),
                "renderToken", content.path("renderToken").asText(), "bodyRendered", true)).andExpect(status().isForbidden());
        String newGrant = grant(setup);
        var latest = content(setup, newGrant);
        var recipient = storage.getObject(CONTEXT, "RecipientRecord", setup.recipient());
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(recipient.type(), recipient.id(), Map.of("withdrawalState", "WITHDRAWN"), recipient.version());
            tx.commit();
        }
        mvc.perform(get("/api/receiver/" + newGrant + "/content").session(setup.session())).andExpect(status().isConflict());
        request(setup.session(), "/api/receiver/" + newGrant + "/read", Map.of("versionId", setup.version(),
                "renderToken", latest.path("renderToken").asText(), "bodyRendered", true)).andExpect(status().isConflict());
        assertTrue(receipts(setup).isEmpty());
    }

    @Test
    void previouslyRenderedVersionCannotRecordAReadAfterThePublishedVersionChanges() throws Exception {
        var setup = published(false, "DELIVERED");
        String grant = grant(setup);
        var original = content(setup, grant);
        var oldVersion = storage.getObject(CONTEXT, "ReminderTaskVersion", setup.version());
        var task = storage.getObject(CONTEXT, "ReminderTask", setup.task());
        String nextId = "fixture-revised-" + UUID.randomUUID();
        var properties = new java.util.HashMap<>(oldVersion.properties());
        properties.put("contentDigest", "revised-digest");
        properties.put("bodySnapshot", "<p>修订后的正文</p>");
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var next = tx.createObject("ReminderTaskVersion", nextId, properties);
            tx.createLink("VersionTargetsRecipient", nextId, next.key(), new EntityKey("RecipientRecord", setup.recipient()), Map.of());
            tx.updateObject(oldVersion.type(), oldVersion.id(), Map.of("state", "SUPERSEDED"), oldVersion.version());
            tx.updateObject(task.type(), task.id(), Map.of("currentPublishedVersionId", nextId), task.version());
            tx.commit();
        }
        request(setup.session(), "/api/receiver/" + grant + "/read", Map.of("versionId", setup.version(),
                "renderToken", original.path("renderToken").asText(), "bodyRendered", true)).andExpect(status().isConflict());
        assertTrue(receipts(setup).isEmpty());
        var latest = content(setup, grant);
        assertTrue(latest.path("bodyHtml").asText().contains("修订后的正文"));
        assertEquals(nextId, latest.path("versionId").asText());
        read(setup, grant, latest);
        assertEquals(1, receipts(setup).size());
        assertEquals(nextId, storage.getObject(CONTEXT, "ReadReceipt", receipts(setup).getFirst().to().id()).properties().get("taskVersionId"));
    }

    @Test
    void reviewerCannotIssueMockIdentityAndGrantCannotBeUsedAfterIssuerRevocation() throws Exception {
        var setup = published(false, "DELIVERED");
        var reviewer = login("reviewer");
        request(reviewer, "/api/reminders/" + setup.task() + "/recipients/" + setup.recipient() + "/mock-entry", Map.of())
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reading").session(reviewer)).andExpect(status().isForbidden());
        String grant = grant(setup);
        jdbc.update("UPDATE mirror_accounts SET enabled=FALSE WHERE username='unit'");
        try {
            mvc.perform(get("/api/receiver/" + grant + "/content").session(setup.session())).andExpect(status().isForbidden());
        } finally {
            jdbc.update("UPDATE mirror_accounts SET enabled=TRUE WHERE username='unit'");
        }
        assertTrue(receipts(setup).isEmpty());
    }

    private record Setup(MockHttpSession session, String task, String recipient, String version, String media) {}
}
