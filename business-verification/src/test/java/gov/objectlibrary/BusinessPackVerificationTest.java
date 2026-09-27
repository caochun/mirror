package gov.objectlibrary;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessPackVerificationTest {
    static Path packPath() {
        Path path = Path.of("..", "domain-pack").toAbsolutePath().normalize();
        return Files.exists(path) ? path : Path.of("domain-pack").toAbsolutePath().normalize();
    }

    @Test
    void loadsBusinessDefinitionWithoutExposingIncompleteExecutableActions() {
        var pack = new DomainPackLoader().load(packPath());
        assertEquals("government-object-library", pack.manifest().name());
        assertEquals("0.2.1", pack.manifest().version());
        assertEquals(pack.manifest().version(), pack.ontology().schema().version());
        assertTrue(pack.actions().isEmpty(), "Definition-only business commands must not become callable stubs");
        assertTrue(pack.ontology().schema().actionTypes().isEmpty());
        assertTrue(pack.ontology().objectTypes().containsKey("Person"));
    }

    @Test
    void modelsSharedTagsMultipleCandidatesAndVersionIndependentRecipients() {
        var pack = new DomainPackLoader().load(packPath());
        var storage = new InMemoryStorageProvider();
        var context = RequestContext.system("tenant", "fixture");
        storage.applySchema(context, pack.ontology().schema());
        String deadline = "2026-09-27T08:00:00Z";
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Person", "p1", Map.of("name", "演示甲"));
            tx.createObject("Person", "p2", Map.of("name", "演示乙"));
            tx.createObject("TagDefinition", "tag", Map.of("name", "重点领域岗位"));
            for (String id : new String[]{"c1", "c2"}) {
                var candidate = tx.createObject("TagCandidate", id, Map.of("state", "PENDING"));
                tx.createLink("TagCandidateForPerson", id, candidate.key(), new EntityKey("Person", "p1"), Map.of());
                var content = tx.createObject("ReminderContent", id, Map.of("title", id));
                tx.createLink("ContentSuggestsTag", id, content.key(), new EntityKey("TagDefinition", "tag"), Map.of());
                var risk = tx.createObject("RiskEvent", id, Map.of("state", "OPEN"));
                tx.createLink("RiskInvolvesPerson", id, risk.key(), new EntityKey("Person", "p1"), Map.of());
            }
            var task = tx.createObject("ReminderTask", "task", Map.of("state", "ALL_SUCCESS"));
            var recipient = tx.createObject("RecipientRecord", "r1", Map.of("taskId", "task", "personId", "p1",
                    "firstDeliveredAt", "2026-09-26T08:00:00Z", "deadlineAt", deadline));
            tx.createLink("TaskHasRecipient", "task-r1", task.key(), recipient.key(), Map.of());
            tx.createLink("RecipientForPerson", "r1-p1", recipient.key(), new EntityKey("Person", "p1"), Map.of());
            for (String id : new String[]{"v1", "v2"}) {
                var version = tx.createObject("ReminderTaskVersion", id, Map.of("taskId", "task", "state", "PUBLISHED"));
                tx.createLink("VersionTargetsRecipient", id, version.key(), recipient.key(), Map.of());
                var reading = tx.createObject("RecipientVersionState", id, Map.of("state", id.equals("v1") ? "READ" : "UNREAD"));
                tx.createLink("VersionStateForRecipient", id, reading.key(), recipient.key(), Map.of());
                tx.createLink("VersionStateForVersion", id, reading.key(), version.key(), Map.of());
            }
            tx.commit();
        }
        assertEquals(2, storage.getLinks(context, new EntityKey("Person", "p1"), "TagCandidateForPerson",
                StorageProvider.Direction.INBOUND, QueryOptions.defaults()).size());
        assertEquals(2, storage.getLinks(context, new EntityKey("TagDefinition", "tag"), "ContentSuggestsTag",
                StorageProvider.Direction.INBOUND, QueryOptions.defaults()).size());
        assertEquals(2, storage.getLinks(context, new EntityKey("Person", "p1"), "RiskInvolvesPerson",
                StorageProvider.Direction.INBOUND, QueryOptions.defaults()).size());
        assertEquals(1, storage.queryObjects(context, "RecipientRecord", QueryOptions.defaults()).size());
        assertEquals(deadline, storage.getObject(context, "RecipientRecord", "r1").properties().get("deadlineAt"));
        assertEquals("READ", storage.getObject(context, "RecipientVersionState", "v1").properties().get("state"));
        assertEquals("UNREAD", storage.getObject(context, "RecipientVersionState", "v2").properties().get("state"));
        try (var tx = storage.beginTransaction(context)) {
            assertThrows(IllegalStateException.class, () -> tx.createLink("TagCandidateForPerson", "invalid",
                    new EntityKey("TagCandidate", "c1"), new EntityKey("Person", "p2"), Map.of()));
        }
    }

    @Test
    void keepsSeparateMatterEvidenceAndAllowsIssuesBeforeAnyTagAssignment() {
        var pack = new DomainPackLoader().load(packPath());
        var storage = new InMemoryStorageProvider();
        var context = RequestContext.system("tenant", "fixture");
        storage.applySchema(context, pack.ontology().schema());
        try (var tx = storage.beginTransaction(context)) {
            var person = tx.createObject("Person", "p", Map.of("name", "演示甲"));
            var issue = tx.createObject("TagProcessingIssue", "missing-birth-date", Map.of("state", "OPEN"));
            tx.createLink("TagIssueForPerson", "issue-person", issue.key(), person.key(), Map.of());
            tx.commit();
        }
        assertTrue(storage.queryObjects(context, "PersonTagAssignment", QueryOptions.defaults()).isEmpty());
        try (var tx = storage.beginTransaction(context)) {
            var tag = tx.createObject("PersonTagAssignment", "tag", Map.of("state", "ACTIVE"));
            for (String id : new String[]{"A", "B"}) {
                var stage = tx.createObject("MatterStage", id, Map.of("name", id, "state", "ACTIVE"));
                var contribution = tx.createObject("TagContribution", id, Map.of("state", "ACTIVE", "source", "STAGE"));
                tx.createLink("AssignmentHasContribution", id, tag.key(), contribution.key(), Map.of());
                tx.createLink("ContributionFromStage", id, contribution.key(), stage.key(), Map.of());
            }
            tx.commit();
        }
        try (var tx = storage.beginTransaction(context)) {
            tx.updateObject("MatterStage", "A", Map.of("state", "ENDED"), 1);
            tx.updateObject("TagContribution", "A", Map.of("state", "EXPIRED"), 1);
            tx.commit();
        }
        assertEquals("ACTIVE", storage.getObject(context, "TagContribution", "B").properties().get("state"));
        assertEquals(2, storage.getEntityHistory(context, new EntityKey("TagContribution", "A")).size());
        assertEquals(2, storage.getLinks(context, new EntityKey("PersonTagAssignment", "tag"), "AssignmentHasContribution",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
    }
}
