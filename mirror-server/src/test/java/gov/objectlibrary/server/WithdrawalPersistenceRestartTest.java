package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

class WithdrawalPersistenceRestartTest {
    @TempDir Path temporary;

    @Test
    void restartsAfterChannelSuccessBeforeLocalReceiptAndKeepsTheOriginalResult() {
        String[] args = {"--server.port=0", "--spring.datasource.url=jdbc:h2:file:" + temporary.resolve("withdrawal"),
                "--mirror.pack=../domain-pack", "--mirror.bootstrap-password=RestartTest-Password-123", "--mirror.demo=true",
                "--mirror.delivery-mode=mock", "--mirror.scheduling-enabled=false", "--logging.level.root=WARN", "--debug=false"};
        var context = RequestContext.system("mirror", "restart-test");
        String taskId;
        String recipientId;
        String withdrawalId;
        String deadline;
        Instant actualWithdrawal;
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var accounts = app.getBean(Accounts.class);
            var actor = accounts.actor("unit");
            var reminders = app.getBean(ReminderService.class);
            var saved = reminders.save(actor, null, new ReminderService.Draft(0, "重启撤回", "<p>正文</p>", "履责", "1d", null,
                    new ReminderSelection.Filter(List.of(), List.of(), "ANY", List.of("demo-person-001"), List.of()), List.of(), ""), "restart-draft-command");
            taskId = saved.get("id").toString();
            var confirmed = reminders.confirm(actor, taskId, new ReminderService.Confirmation(((Number) saved.get("version")).longValue(),
                    saved.get("selectionDigest").toString(), 1, true, saved.get("contentDigest").toString(), true, true, List.of()), "restart-confirm-command");
            var submitted = reminders.submit(actor, taskId, ((Number) confirmed.get("version")).longValue(), "restart-submit-command");
            var approved = reminders.decide(accounts.actor("reviewer"), taskId,
                    new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", ""), "restart-approve-command");
            app.getBean(DeliveryService.class).dispatchMock(actor, taskId, ((Number) approved.get("version")).longValue());
            var storage = app.getBean(StorageProvider.class);
            recipientId = "recipient-" + BusinessCommands.hash(taskId + "/demo-person-001");
            var recipient = storage.getObject(context, "RecipientRecord", recipientId);
            deadline = text(recipient, "deadlineAt");
            app.getBean(WithdrawalService.class).request(actor, taskId,
                    new WithdrawalService.Request(storage.getObject(context, "ReminderTask", taskId).version(), List.of(recipientId), "重启验证"), "restart-withdraw-command", false);
            withdrawalId = text(storage.getObject(context, "RecipientRecord", recipientId), "latestWithdrawalId");
            var withdrawal = storage.getObject(context, "WithdrawalRecord", withdrawalId);
            // Persist the actual crash boundary: the worker owns the intent, the provider succeeds, no local result is recorded.
            try (var tx = storage.beginTransaction(context)) {
                tx.updateObject(withdrawal.type(), withdrawalId, Map.of("state", "RUNNING", "leaseOwner", "interrupted-worker",
                        "leaseUntil", Instant.now().minusSeconds(1).toString()), withdrawal.version());
                tx.commit();
            }
            actualWithdrawal = app.getBean(ReminderChannel.class).withdraw(new ReminderChannel.WithdrawalRequest("mirror", withdrawalId,
                    recipientId, List.of(text(recipient, "latestAttemptId")), "重启验证")).occurredAt();
        }
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var storage = app.getBean(StorageProvider.class);
            assertEquals("REQUESTED", text(storage.getObject(context, "RecipientRecord", recipientId), "withdrawalState"));
            assertEquals(1, app.getBean(WithdrawalService.class).dispatchTenant("mirror", taskId));
            assertEquals(0, app.getBean(WithdrawalService.class).dispatchTenant("mirror", taskId));
            assertEquals("WITHDRAWN", text(storage.getObject(context, "ReminderTask", taskId), "state"));
            assertEquals(actualWithdrawal.toString(), text(storage.getObject(context, "WithdrawalRecord", withdrawalId), "completedAt"));
            assertEquals(deadline, text(storage.getObject(context, "RecipientRecord", recipientId), "deadlineAt"));
            assertEquals("DELIVERED", text(storage.getObject(context, "RecipientRecord", recipientId), "deliveryState"));
            assertEquals(1, app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM mirror_mock_withdrawals WHERE request_key=?", Integer.class, withdrawalId));
        }
    }
}
