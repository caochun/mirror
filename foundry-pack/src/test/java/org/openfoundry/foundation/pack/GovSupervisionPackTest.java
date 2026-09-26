package org.openfoundry.foundation.pack;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.actions.ActionActor;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovSupervisionPackTest {
    @Test
    void loadsAndExecutesAssignmentTransfer() {
        Path packPath = Path.of("..", "examples", "gov-supervision-pack");
        if (!java.nio.file.Files.exists(packPath)) packPath = Path.of("examples", "gov-supervision-pack");
        LoadedDomainPack pack = new DomainPackLoader().load(packPath.toAbsolutePath().normalize());
        var storage = new InMemoryStorageProvider();
        RequestContext context = RequestContext.system("tenant", "operator");
        storage.applySchema(context, pack.ontology().schema());
        ObjectRecord assignment;
        ObjectRecord organizationB;
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Person", "person-1", Map.of("name", "Alice", "status", "ACTIVE"));
            tx.createObject("Organization", "org-a", Map.of("name", "Organization A", "nature", "CITY"));
            organizationB = tx.createObject("Organization", "org-b", Map.of("name", "Organization B", "nature", "CITY"));
            assignment = tx.createObject("Assignment", "assignment-1", Map.of("title", "Engineer", "status", "ACTIVE"));
            tx.createLink("AssignmentInOrganization", "assignment-org-a", assignment.key(), new EntityKey("Organization", "org-a"), Map.of("startedAt", "2026-01-01"));
            tx.commit();
        }

        var action = pack.actions().get("TransferAssignment");
        var result = new ActionExecutor().execute(action, context,
                new ActionActor("operator", Set.of("admin")),
                Map.of("assignment", assignment, "oldLinkId", "assignment-org-a", "organization", organizationB, "reason", "organization transfer"), storage);

        assertTrue(result.success());
        assertEquals("org-b", storage.getLinks(context, assignment.key(), "AssignmentInOrganization",
                org.openfoundry.foundation.spi.StorageProvider.Direction.OUTBOUND,
                org.openfoundry.foundation.spi.QueryOptions.defaults()).getFirst().to().id());
        assertEquals(2, storage.getEntityHistory(context, new EntityKey("AssignmentInOrganization", "assignment-org-a")).size());
    }
}
