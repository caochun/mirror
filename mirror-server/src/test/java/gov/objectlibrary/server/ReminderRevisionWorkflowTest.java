package gov.objectlibrary.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_revision;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.delivery-mode=mock", "mirror.media-directory=target/revision-media", "mirror.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(DeliveryWorkflowTest.Config.class)
class ReminderRevisionWorkflowTest {
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "revision-test");
    @Autowired ReminderService reminders;
    @Autowired ReminderRevisionService revisions;
    @Autowired DeliveryService delivery;
    @Autowired ReadingService reading;
    @Autowired ReceiverService receiver;
    @Autowired ReceiverSessions sessions;
    @Autowired StorageProvider storage;
    @Autowired Accounts accounts;
    @Autowired DeliveryWorkflowTest.TestClock clock;
    @Autowired DeliveryWorkflowTest.TestChannel channel;
    @Autowired MediaService media;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        clock.now = Instant.parse("2035-01-01T00:00:00Z");
        channel.outcomes.clear();
        channel.crashAfterAccept = false;
    }

    private String key() { return UUID.randomUUID().toString(); }
    private Accounts.Actor unit() { return accounts.actor("unit"); }
    private ObjectRecord task(String id) { return storage.getObject(CONTEXT, "ReminderTask", id); }
    private String recipient(String id, String person) { return "recipient-" + BusinessCommands.hash(id + "/" + person); }

    private String create(boolean dispatch) {
        var saved = reminders.save(unit(), null, new ReminderService.Draft(0, "原始提醒", "<p>原始正文</p>", "履责", "1d", clock.now.plusSeconds(60),
                new ReminderSelection.Filter(List.of(), List.of(), "ANY", List.of("demo-person-001", "demo-person-009"), List.of()), List.of(), ""), key());
        String id = saved.get("id").toString();
        var confirmed = reminders.confirm(unit(), id, new ReminderService.Confirmation(((Number) saved.get("version")).longValue(),
                saved.get("selectionDigest").toString(), 2, false, saved.get("contentDigest").toString(), true, true, List.of()), key());
        var submitted = reminders.submit(unit(), id, ((Number) confirmed.get("version")).longValue(), key());
        reminders.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", "核对通过"), key());
        clock.now = clock.now.plusSeconds(60);
        if (dispatch) delivery.dispatchMock(unit(), id, task(id).version());
        return id;
    }

    private void saveConfirmSubmit(String id, String title, String body) {
        revisions.save(unit(), id, new ReminderRevisionService.Draft(task(id).version(), title, body), key());
        var detail = reminders.detail(unit(), id);
        revisions.confirm(unit(), id, new ReminderRevisionService.Confirmation(task(id).version(), detail.contentDigest(), true,
                detail.images().stream().map(ReminderContentPolicy.ImageReference::confirmationKey).toList()), key());
        revisions.submit(unit(), id, task(id).version(), key());
    }

    private String open(String task, String recipient, MockHttpSession session) {
        String url = receiver.issueMock(unit(), task, recipient, session);
        return sessions.exchange(url.substring(url.indexOf("#ticket=") + 8), session);
    }

    @Test
    void publishesAfterIndependentReviewWithoutChangingRosterDeadlinesOrRepeatingDelivery() {
        String id = create(true);
        String first = text(task(id), "currentPublishedVersionId");
        String r1 = recipient(id, "demo-person-001");
        String r2 = recipient(id, "demo-person-009");
        String deadline = text(storage.getObject(CONTEXT, "RecipientRecord", r1), "deadlineAt");
        var session = new MockHttpSession();
        String grant = open(id, r1, session);
        var original = receiver.content(grant, session);
        receiver.read(grant, session, original.versionId(), original.renderToken(), true);
        int calls = channel.calls.get();
        clock.now = clock.now.plusSeconds(86401);
        reading.evaluateDue();
        assertEquals("OPEN", text(storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(r2, first)), "state"));
        saveConfirmSubmit(id, "修订标题", "<p>修订正文</p>");
        var freshSession = new MockHttpSession();
        String freshGrant = open(id, r1, freshSession);
        var previousContent = receiver.content(freshGrant, freshSession);
        assertEquals(first, previousContent.versionId());
        assertEquals("ALL_SUCCESS", text(task(id), "state"));
        assertEquals(0, revisions.publishApproved());
        revisions.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(task(id).version(), "APPROVE", "修订通过"), key());
        assertEquals(first, text(task(id), "currentPublishedVersionId"), "Approval is durable before the publication worker runs");
        assertEquals(1, revisions.publishApproved());
        assertEquals(0, revisions.publishApproved());
        String latest = text(task(id), "currentPublishedVersionId");
        assertNotEquals(first, latest);
        assertEquals("修订标题", text(task(id), "title"));
        assertEquals("NONE", ReminderService.revisionState(task(id)));
        assertEquals("ALL_SUCCESS", text(task(id), "state"));
        assertEquals(calls, channel.calls.get(), "A content revision must not resend successful notifications");
        assertEquals(deadline, text(storage.getObject(CONTEXT, "RecipientRecord", r1), "deadlineAt"));
        assertEquals(2, delivery.recipients(unit(), id).size());
        assertTrue(delivery.recipients(unit(), id).stream().allMatch(row -> row.readState().equals("UNREAD")));
        assertEquals("READ", text(storage.getObject(CONTEXT, "RecipientVersionState", DeliveryService.readingStateId(r1, first)), "state"));
        assertEquals("CLOSED", text(storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(r2, first)), "state"));
        assertEquals("OPEN", text(storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(r1, latest)), "state"));
        assertThrows(BusinessConflict.class, () -> receiver.read(freshGrant, freshSession, first, previousContent.renderToken(), true));
        var revised = receiver.content(freshGrant, freshSession);
        assertEquals("修订标题", revised.title());
        receiver.read(freshGrant, freshSession, latest, revised.renderToken(), true);
        assertEquals("RESOLVED", text(storage.getObject(CONTEXT, "OverdueRecord", ReadingService.overdueId(r1, latest)), "state"));
    }

    @Test
    void rejectedAndWithdrawnRevisionsPreservePublishedContentAndCreateNewReviewRounds() {
        String id = create(true);
        String published = text(task(id), "currentPublishedVersionId");
        saveConfirmSubmit(id, "退回的修订", "<p>待核实</p>");
        String rejectedVersion = text(task(id), "pendingVersionId");
        revisions.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(task(id).version(), "REJECT", "需修改"), key());
        assertEquals("REJECTED", ReminderService.revisionState(task(id)));
        saveConfirmSubmit(id, "再次修订", "<p>再次核实</p>");
        assertNotEquals(rejectedVersion, text(task(id), "pendingVersionId"));
        revisions.withdrawReview(unit(), id, task(id).version(), key());
        assertEquals("WITHDRAWN", ReminderService.revisionState(task(id)));
        assertEquals(published, text(task(id), "currentPublishedVersionId"));
        assertEquals("原始提醒", reminders.detail(unit(), id).previewTitle());
        assertEquals(3, reminders.detail(unit(), id).rounds().size());
    }

    @Test
    void rejectsSelfReviewChangedRosterAndUneditableFields() throws Exception {
        String id = create(true);
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", "unit", "password", "TestOnlyPassword-123"))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession();
        mvc.perform(post("/api/reminders/" + id + "/revision").session(session).with(csrf()).header("Idempotency-Key", key())
                .contentType("application/json").content(json.writeValueAsString(Map.of("expectedVersion", task(id).version(),
                        "title", "尝试改期限", "bodyHtml", "<p>正文</p>", "readingWindow", "1w"))))
                .andExpect(status().isBadRequest());
        saveConfirmSubmit(id, "待独立审核", "<p>修订</p>");
        jdbc.update("INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN', 'REMINDER_REVIEW')");
        try {
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> revisions.decide(unit(), id, new ReminderService.Decision(task(id).version(), "APPROVE", ""), key()));
        } finally {
            jdbc.update("DELETE FROM mirror_role_permissions WHERE role_name='UNIT_ADMIN' AND permission_name='REMINDER_REVIEW'");
        }
        var pending = storage.getObject(CONTEXT, "ReminderTaskVersion", text(task(id), "pendingVersionId"));
        var member = storage.getLinks(CONTEXT, pending.key(), "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst();
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.deleteLink(member.type(), member.id(), member.version());
            tx.commit();
        }
        assertThrows(BusinessConflict.class, () -> revisions.decide(accounts.actor("reviewer"), id,
                new ReminderService.Decision(task(id).version(), "APPROVE", ""), key()));
    }

    @Test
    void everyRevisionImageMustBeConfirmedAndEditingInvalidatesPreviousConfirmation() throws Exception {
        String id = create(true);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        var asset = media.upload(unit(), new MockMultipartFile("file", "image.png", "image/png", bytes.toByteArray()), key());
        String body = "<p>配图修订</p><img data-media-id=\"" + asset.get("id") + "\">";
        revisions.save(unit(), id, new ReminderRevisionService.Draft(task(id).version(), "配图", body), key());
        var detail = reminders.detail(unit(), id);
        assertThrows(BusinessConflict.class, () -> revisions.confirm(unit(), id,
                new ReminderRevisionService.Confirmation(task(id).version(), detail.contentDigest(), true, List.of()), key()));
        revisions.confirm(unit(), id, new ReminderRevisionService.Confirmation(task(id).version(), detail.contentDigest(), true,
                detail.images().stream().map(ReminderContentPolicy.ImageReference::confirmationKey).toList()), key());
        revisions.save(unit(), id, new ReminderRevisionService.Draft(task(id).version(), "改过的标题", body), key());
        assertThrows(BusinessConflict.class, () -> revisions.submit(unit(), id, task(id).version(), key()));
    }

    @Test
    void aRecoveredOldSendJobCannotRollBackTheNewPublishedVersion() {
        String id = create(false);
        channel.crashAfterAccept = true;
        assertThrows(DeliveryWorkflowTest.SimulatedProcessCrash.class, () -> delivery.dispatchMock(unit(), id, task(id).version()));
        String oldVersion = text(task(id), "currentPublishedVersionId");
        saveConfirmSubmit(id, "发送期间的修订", "<p>新正文</p>");
        revisions.decide(accounts.actor("reviewer"), id, new ReminderService.Decision(task(id).version(), "APPROVE", ""), key());
        revisions.publishApproved();
        String latest = text(task(id), "currentPublishedVersionId");
        assertNotEquals(oldVersion, latest);
        clock.now = clock.now.plusSeconds(61);
        delivery.dispatchMock(unit(), id, task(id).version());
        assertEquals(latest, text(task(id), "currentPublishedVersionId"));
        assertEquals("ALL_SUCCESS", text(task(id), "state"));
        assertTrue(delivery.recipients(unit(), id).stream().allMatch(row -> row.readState().equals("UNREAD")));
    }
}
