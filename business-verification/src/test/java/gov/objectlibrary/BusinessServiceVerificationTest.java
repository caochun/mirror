package gov.objectlibrary;

import gov.objectlibrary.core.ObjectLibraryService;
import gov.objectlibrary.core.AiAssistantService;
import gov.objectlibrary.core.BusinessAuthorization;
import gov.objectlibrary.core.MockLulutongConnector;
import gov.objectlibrary.core.ReminderDeliveryService;
import gov.objectlibrary.core.ReminderStatisticsService;
import gov.objectlibrary.core.TagRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.pack.LoadedDomainPack;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessServiceVerificationTest {
    private RequestContext context;
    private InMemoryStorageProvider storage;
    private ObjectLibraryService service;

    @BeforeEach
    void setUp() {
        Path path = Path.of("..", "domain-pack").toAbsolutePath().normalize();
        if (!Files.exists(path)) path = Path.of("domain-pack").toAbsolutePath().normalize();
        LoadedDomainPack pack = new DomainPackLoader().load(path);
        context = RequestContext.system("tenant", "operator");
        storage = new InMemoryStorageProvider();
        storage.applySchema(context, pack.ontology().schema());
        service = new ObjectLibraryService(storage);
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Organization", "org-a", Map.of("name", "A", "nature", "CITY", "status", "ACTIVE"));
            tx.createObject("Organization", "org-b", Map.of("name", "B", "nature", "CITY", "status", "ACTIVE"));
            tx.createObject("Assignment", "assignment-1", Map.of("title", "Engineer", "status", "ACTIVE"));
            tx.createObject("TagDefinition", "tag-risk", Map.of("code", "RISK", "name", "Risk", "scope", "PERSON", "status", "ACTIVE", "level", 1));
            tx.commit();
        }
    }

    @Test
    void personOrganizationAndNonObjectAccountKeepHistory() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.assignToOrganization(context, "person-1", "org-a", "assignment-1");
        service.assignToOrganization(context, "person-1", "org-b", "assignment-1");

        assertEquals("org-b", storage.getLinks(context, new EntityKey("Person", "person-1"),
                "PersonBelongsToOrganization", org.openfoundry.foundation.spi.StorageProvider.Direction.OUTBOUND,
                QueryOptions.defaults()).getFirst().to().id());
        var allLinks = storage.getLinks(context, new EntityKey("Person", "person-1"),
                "PersonBelongsToOrganization", org.openfoundry.foundation.spi.StorageProvider.Direction.OUTBOUND,
                new QueryOptions(100, 0, null, null, true));
        assertEquals(2, allLinks.size());
        assertTrue(allLinks.stream().anyMatch(link -> link.isDeleted()));
        assertEquals(1, storage.queryObjects(context, "Person", QueryOptions.defaults()).size());

        ObjectRecord account = service.markNonObjectAccount(context, "person-1", "account-1", "alice.bot", "service account");
        assertEquals("NON_OBJECT", account.properties().get("state"));
        assertEquals("NON_OBJECT_ACCOUNT", storage.getObject(context, "ObjectEligibility", "eligibility-person-1").properties().get("state"));
    }

    @Test
    void tagManualSuppressionAndAiReviewAreSeparateHistories() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.assignTag(context, "tag-assignment-1", "person-1", "tag-risk", "1", "RULE");
        service.removeTag(context, "tag-assignment-1", "manual review");
        assertEquals(true, storage.getObject(context, "PersonTagAssignment", "tag-assignment-1").properties().get("manualSuppressed"));
        service.restoreTag(context, "tag-assignment-1");
        assertEquals("ACTIVE", storage.getObject(context, "PersonTagAssignment", "tag-assignment-1").properties().get("state"));
        assertEquals(3, storage.getEntityHistory(context, new EntityKey("PersonTagAssignment", "tag-assignment-1")).size());
    }

    @Test
    void externalIdentityAndRuleReconciliationDoNotEraseManualTag() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.registerExternalIdentity(context, "identity-1", "hr", "HR-1", "person-1");
        service.openAssociationIssue(context, "issue-1", "DUPLICATE", "two HR records", "person-1");
        service.resolveAssociationIssue(context, "issue-1", "merged records");
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("TagRule", "rule-1", Map.of("tagDefinitionId", "tag-risk", "ruleType", "CEL",
                    "expression", "true", "status", "ACTIVE", "version", "1"));
            tx.commit();
        }
        TagRuleService rules = new TagRuleService(storage);
        rules.reconcile(context, "rule-1", "person-1", "rule-assignment-1", "tag-risk", "1", true);
        service.assignTag(context, "manual-assignment-1", "person-1", "tag-risk", "1", "MANUAL");
        service.removeTag(context, "manual-assignment-1", "manual removal");
        rules.reconcile(context, "rule-1", "person-1", "rule-assignment-1", "tag-risk", "1", false);
        assertEquals("EXPIRED", storage.getObject(context, "PersonTagAssignment", "rule-assignment-1").properties().get("state"));
        assertEquals("REMOVED", storage.getObject(context, "PersonTagAssignment", "manual-assignment-1").properties().get("state"));
        assertEquals("RESOLVED", storage.getObject(context, "DataAssociationIssue", "issue-1").properties().get("status"));
    }

    @Test
    void reminderDeliveryStartsReadingWindowAndRejectsReadBeforeDelivery() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.createReminderTask(context, "task-1", "task-version-1", "Title", "Body",
                List.of("person-1"), "1d", Instant.now());
        assertThrows(IllegalStateException.class,
                () -> service.recordReadReceipt(context, "task-1-recipient-person-1", "task-version-1"));
        service.recordDeliveryResult(context, "task-1-recipient-person-1", true, "external-1", null);
        ObjectRecord read = service.recordReadReceipt(context, "task-1-recipient-person-1", "task-version-1");
        assertEquals("READ", read.properties().get("state"));
        service.recordReadReceipt(context, "task-1-recipient-person-1", "task-version-1");
        assertEquals(1, storage.queryObjects(context, "ReadReceipt", QueryOptions.defaults()).size());

        service.createReminderTask(context, "task-2", "task-version-2", "Title", "Body",
                List.of("person-1"), "1d", Instant.now());
        service.recordDeliveryResult(context, "task-2-recipient-person-1", true, "external-2", null);
        ObjectRecord recipient = storage.getObject(context, "RecipientRecord", "task-2-recipient-person-1");
        try (var tx = storage.beginTransaction(context)) {
            tx.updateObject("RecipientRecord", recipient.id(), Map.of("deadlineAt", Instant.now().minusSeconds(1)), recipient.version());
            tx.commit();
        }
        assertEquals(1, service.scanOverdue(context, Instant.now()));
        assertEquals("OVERDUE", storage.getObject(context, "RecipientRecord", recipient.id()).properties().get("state"));
        service.recordReadReceipt(context, recipient.id(), "task-version-2");
        ObjectRecord overdue = storage.getObject(context, "OverdueRecord", "overdue-" + recipient.id());
        service.resolveOverdue(context, overdue.id());
        assertEquals("RESOLVED", storage.getObject(context, "OverdueRecord", overdue.id()).properties().get("state"));
    }

    @Test
    void reminderLifecycleFreezesApprovalRevisionAndWithdrawal() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.registerPerson(context, "person-2", "Bob", "E002", "OK");
        service.createReminderTask(context, "task-1", "task-version-1", "Title", "Body",
                List.of("person-1"), "1d", Instant.now());
        service.addRecipient(context, "task-version-1", "person-2");
        service.removeRecipient(context, "task-version-1", "person-2");
        service.freezeReminderSnapshot(context, "task-1", "task-version-1");
        assertThrows(IllegalStateException.class,
                () -> service.addRecipient(context, "task-version-1", "person-2"));
        service.approveReminder(context, "task-1");
        service.transitionTask(context, "task-1", "SENDING");
        service.reviseReminder(context, "task-1", "task-version-1", "task-version-2", "New title", "New body");
        assertEquals("DRAFT", storage.getObject(context, "ReminderTask", "task-1").properties().get("state"));
        assertEquals("2", storage.getObject(context, "ReminderTaskVersion", "task-version-2").properties().get("version"));
        assertEquals(2, service.withdrawReminder(context, "task-1", "withdraw for revision"));
        assertEquals("WITHDRAWN", storage.getObject(context, "ReminderTask", "task-1").properties().get("state"));
    }

    @Test
    void connectorRetriesOnlyFailedRecipients() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.registerPerson(context, "person-2", "Bob", "E002", "OK");
        service.createReminderTask(context, "task-1", "task-version-1", "Title", "Body",
                List.of("person-1", "person-2"), "1d", Instant.now());
        MockLulutongConnector connector = new MockLulutongConnector()
                .respond("task-1-recipient-person-2", false, "TIMEOUT");
        ReminderDeliveryService delivery = new ReminderDeliveryService(storage, connector);
        var first = delivery.send(context, "task-version-1");
        assertEquals(2, first.attempted());
        assertEquals(1, first.succeeded());
        assertEquals(1, first.failed());
        var stats = new ReminderStatisticsService(storage).summarize(context, "task-version-1");
        assertEquals(1, stats.delivered());
        assertEquals(1, stats.failed());
        var second = delivery.send(context, "task-version-1");
        assertEquals(1, second.attempted());
        assertEquals(0, second.succeeded());
        assertEquals(3, storage.queryObjects(context, "DeliveryAttempt", QueryOptions.defaults()).size());
    }

    @Test
    void authorizationAndAiActionCardRequireScopedHumanConfirmation() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.assignToOrganization(context, "person-1", "org-a", "assignment-1");
        BusinessAuthorization authorization = new BusinessAuthorization(storage);
        assertTrue(authorization.canReadPerson(context, BusinessAuthorization.UNIT_ADMIN, "person-1", "org-a"));
        assertTrue(!authorization.canReadPerson(context, BusinessAuthorization.UNIT_ADMIN, "person-1", "org-b"));
        authorization.readSensitivePerson(context, BusinessAuthorization.UNIT_ADMIN, "person-1", "org-a");
        assertTrue(storage.auditEntries(context).stream().anyMatch(a -> "sensitive-read".equals(a.operationType())));

        service.createReminderTask(context, "task-1", "task-version-1", "Title", "Body",
                List.of("person-1"), "1d", Instant.now());
        AiAssistantService assistant = new AiAssistantService(storage);
        var card = assistant.proposeSend(context, "task-1", "operator requested send");
        assertEquals("DRAFT", storage.getObject(context, "ReminderTask", "task-1").properties().get("state"));
        assistant.confirmSend(context, card.id());
        assertEquals("SENDING", storage.getObject(context, "ReminderTask", "task-1").properties().get("state"));
    }
}
