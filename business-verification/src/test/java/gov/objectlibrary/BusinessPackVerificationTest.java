package gov.objectlibrary;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.ActionActor;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.pack.LoadedDomainPack;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessPackVerificationTest {
    @Test
    void loadsFreshPackAndTransfersAssignment() {
        Path packPath = Path.of("..", "domain-pack").toAbsolutePath().normalize();
        if (!Files.exists(packPath)) packPath = Path.of("domain-pack").toAbsolutePath().normalize();
        LoadedDomainPack pack = new DomainPackLoader().load(packPath);
        assertEquals("government-object-library", pack.manifest().name());
        assertEquals(28, pack.ontology().schema().objectTypes().size());
        assertEquals(25, pack.ontology().schema().linkTypes().size());

        RequestContext context = RequestContext.system("tenant", "operator");
        InMemoryStorageProvider storage = new InMemoryStorageProvider();
        storage.applySchema(context, pack.ontology().schema());
        ObjectRecord assignment;
        ObjectRecord organizationB;
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Person", "person-1", Map.of("name", "Alice", "status", "ACTIVE", "identityStatus", "OK"));
            tx.createObject("Organization", "org-a", Map.of("name", "A", "nature", "CITY", "status", "ACTIVE"));
            organizationB = tx.createObject("Organization", "org-b", Map.of("name", "B", "nature", "CITY", "status", "ACTIVE"));
            assignment = tx.createObject("Assignment", "assignment-1", Map.of("title", "Engineer", "status", "ACTIVE"));
            tx.createLink("AssignmentInOrganization", "assignment-org-a", assignment.key(), new EntityKey("Organization", "org-a"), Map.of("startedAt", "2026-01-01"));
            tx.commit();
        }
        var result = new ActionExecutor().execute(pack.actions().get("TransferAssignment"), context,
                new ActionActor("operator", Set.of("admin")),
                Map.of("assignment", assignment, "oldLinkId", "assignment-org-a", "organization", organizationB,
                        "reason", "organization transfer"), storage);
        assertTrue(result.success());
        assertEquals("org-b", storage.getLinks(context, assignment.key(), "AssignmentInOrganization",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst().to().id());
        assertEquals(2, storage.getEntityHistory(context,
                new EntityKey("AssignmentInOrganization", "assignment-org-a")).size());
    }
}
