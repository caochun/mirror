package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

class ReceiverPersistenceRestartTest {
    @TempDir Path temporary;

    @Test
    void deliveryAndFirstReadSurviveRestartAndNewReceiverEntryCannotDuplicateTheReceipt() {
        String[] args = {"--server.port=0", "--spring.datasource.url=jdbc:h2:file:" + temporary.resolve("receiver"),
                "--mirror.pack=../domain-pack", "--mirror.bootstrap-password=RestartTest-Password-123", "--mirror.demo=true",
                "--mirror.delivery-mode=mock", "--mirror.scheduling-enabled=false", "--logging.level.root=WARN", "--debug=false"};
        String taskId;
        String recipientId;
        String firstReadAt;
        String deadline;
        var context = RequestContext.system("mirror", "restart-test");
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var accounts = app.getBean(Accounts.class);
            var reminders = app.getBean(ReminderService.class);
            var actor = accounts.actor("unit");
            var saved = reminders.save(actor, null, new ReminderService.Draft(0, "持久阅读测试", "<p>请依规履职。</p>", "履责", "1d", null,
                    new ReminderSelection.Filter(List.of(), List.of(), "ANY", List.of("demo-person-001"), List.of()), List.of(), ""), "restart-read-draft");
            taskId = saved.get("id").toString();
            var confirmed = reminders.confirm(actor, taskId, new ReminderService.Confirmation(((Number) saved.get("version")).longValue(),
                    saved.get("selectionDigest").toString(), 1, true, saved.get("contentDigest").toString(), true, true, List.of()), "restart-read-confirm");
            var submitted = reminders.submit(actor, taskId, ((Number) confirmed.get("version")).longValue(), "restart-read-submit");
            var approved = reminders.decide(accounts.actor("reviewer"), taskId,
                    new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", "已核对"), "restart-read-approve");
            app.getBean(DeliveryService.class).dispatchMock(actor, taskId, ((Number) approved.get("version")).longValue());
            recipientId = "recipient-" + BusinessCommands.hash(taskId + "/demo-person-001");
            var receiver = app.getBean(ReceiverService.class);
            var session = new MockHttpSession();
            String url = receiver.issueMock(actor, taskId, recipientId, session);
            String grant = app.getBean(ReceiverSessions.class).exchange(url.substring(url.indexOf("#ticket=") + 8), session);
            var content = receiver.content(grant, session);
            firstReadAt = receiver.read(grant, session, content.versionId(), content.renderToken(), true).get("firstReadAt").toString();
            deadline = text(app.getBean(StorageProvider.class).getObject(context, "RecipientRecord", recipientId), "deadlineAt");
        }
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var actor = app.getBean(Accounts.class).actor("unit");
            var receiver = app.getBean(ReceiverService.class);
            var session = new MockHttpSession();
            String url = receiver.issueMock(actor, taskId, recipientId, session);
            String grant = app.getBean(ReceiverSessions.class).exchange(url.substring(url.indexOf("#ticket=") + 8), session);
            var content = receiver.content(grant, session);
            assertEquals(firstReadAt, receiver.read(grant, session, content.versionId(), content.renderToken(), true).get("firstReadAt"));
            var storage = app.getBean(StorageProvider.class);
            assertEquals(deadline, text(storage.getObject(context, "RecipientRecord", recipientId), "deadlineAt"));
            assertEquals(1, storage.getLinks(context, new EntityKey("RecipientRecord", recipientId), "RecipientHasReadReceipt",
                    StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
            assertEquals("READ", app.getBean(DeliveryService.class).recipients(actor, taskId).getFirst().readState());
        }
    }
}
