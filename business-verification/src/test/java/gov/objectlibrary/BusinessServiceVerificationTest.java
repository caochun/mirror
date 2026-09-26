package gov.objectlibrary;

import gov.objectlibrary.core.ObjectLibraryService;
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
    void reminderDeliveryStartsReadingWindowAndRejectsReadBeforeDelivery() {
        service.registerPerson(context, "person-1", "Alice", "E001", "OK");
        service.createReminderTask(context, "task-1", "task-version-1", "Title", "Body",
                List.of("person-1"), "1d", Instant.now());
        assertThrows(IllegalStateException.class,
                () -> service.recordReadReceipt(context, "task-1-recipient-person-1", "task-version-1"));
        service.recordDeliveryResult(context, "task-1-recipient-person-1", true, "external-1", null);
        ObjectRecord read = service.recordReadReceipt(context, "task-1-recipient-person-1", "task-version-1");
        assertEquals("READ", read.properties().get("state"));
        assertEquals(1, storage.queryObjects(context, "ReadReceipt", QueryOptions.defaults()).size());
    }
}
