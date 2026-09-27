package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;
import java.util.List;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

class RevisionPersistenceRestartTest {
    @TempDir Path temporary;

    @Test
    void restartPublishesAnApprovedRevisionOnceWithoutAnotherSendJob() {
        String[] args = {"--server.port=0", "--spring.datasource.url=jdbc:h2:file:" + temporary.resolve("revision"),
                "--mirror.pack=../domain-pack", "--mirror.bootstrap-password=RestartTest-Password-123", "--mirror.demo=true",
                "--mirror.delivery-mode=mock", "--mirror.scheduling-enabled=false", "--logging.level.root=WARN", "--debug=false"};
        var context = RequestContext.system("mirror", "restart-test");
        String taskId;
        String original;
        String pending;
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var accounts = app.getBean(Accounts.class);
            var actor = accounts.actor("unit");
            var reminders = app.getBean(ReminderService.class);
            var saved = reminders.save(actor, null, new ReminderService.Draft(0, "原版", "<p>原文</p>", "履责", "1d", null,
                    new ReminderSelection.Filter(List.of(), List.of(), "ANY", List.of("demo-person-001"), List.of()), List.of(), ""), "restart-draft-command");
            taskId = saved.get("id").toString();
            var confirmed = reminders.confirm(actor, taskId, new ReminderService.Confirmation(((Number) saved.get("version")).longValue(),
                    saved.get("selectionDigest").toString(), 1, true, saved.get("contentDigest").toString(), true, true, List.of()), "restart-confirm-command");
            var submitted = reminders.submit(actor, taskId, ((Number) confirmed.get("version")).longValue(), "restart-submit-command");
            var approved = reminders.decide(accounts.actor("reviewer"), taskId,
                    new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", "核对通过"), "restart-approve-command");
            app.getBean(DeliveryService.class).dispatchMock(actor, taskId, ((Number) approved.get("version")).longValue());
            var storage = app.getBean(StorageProvider.class);
            var task = storage.getObject(context, "ReminderTask", taskId);
            original = text(task, "currentPublishedVersionId");
            var revisions = app.getBean(ReminderRevisionService.class);
            var draft = revisions.save(actor, taskId, new ReminderRevisionService.Draft(task.version(), "修订版", "<p>新正文</p>"), "restart-revision-save");
            var check = revisions.confirm(actor, taskId, new ReminderRevisionService.Confirmation(((Number) draft.get("version")).longValue(),
                    draft.get("contentDigest").toString(), true, List.of()), "restart-revision-confirm");
            var submit = revisions.submit(actor, taskId, ((Number) check.get("version")).longValue(), "restart-revision-submit");
            revisions.decide(accounts.actor("reviewer"), taskId,
                    new ReminderService.Decision(((Number) submit.get("version")).longValue(), "APPROVE", ""), "restart-revision-approve");
            pending = text(storage.getObject(context, "ReminderTask", taskId), "pendingVersionId");
        }
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var storage = app.getBean(StorageProvider.class);
            assertEquals(original, text(storage.getObject(context, "ReminderTask", taskId), "currentPublishedVersionId"));
            assertEquals("APPROVED", ReminderService.revisionState(storage.getObject(context, "ReminderTask", taskId)));
            var revisions = app.getBean(ReminderRevisionService.class);
            assertEquals(1, revisions.publishApproved());
            assertEquals(0, revisions.publishApproved());
            assertEquals(pending, text(storage.getObject(context, "ReminderTask", taskId), "currentPublishedVersionId"));
            assertEquals(1, storage.queryObjects(context, "ReminderSendJob", QueryOptions.defaults()).size());
            assertEquals(1, storage.queryObjects(context, "RecipientRecord", QueryOptions.defaults()).size());
        }
    }
}
