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
    private static final String NOW = "2026-09-26T08:00:00Z";
    static Path packPath() {
        Path path = Path.of("..", "domain-pack").toAbsolutePath().normalize();
        return Files.exists(path) ? path : Path.of("domain-pack").toAbsolutePath().normalize();
    }

    @Test
    void loadsBusinessDefinitionWithoutExposingIncompleteExecutableActions() {
        var pack = new DomainPackLoader().load(packPath());
        assertEquals("government-object-library", pack.manifest().name());
        assertEquals("0.2.9", pack.manifest().version());
        assertEquals(pack.manifest().version(), pack.ontology().schema().version());
        assertTrue(pack.actions().isEmpty(), "Definition-only business commands must not become callable stubs");
        assertTrue(pack.ontology().schema().actionTypes().isEmpty());
        assertTrue(pack.ontology().objectTypes().containsKey("Person"));
    }

    @Test
    void organizationChangesAndAccountGrantsExistWithoutPersonnelAndKeepHistory() {
        var pack = new DomainPackLoader().load(packPath());
        var storage = new InMemoryStorageProvider();
        var context = RequestContext.system("tenant", "authority-fixture");
        storage.applySchema(context, pack.ontology().schema());
        var child = new EntityKey("Organization", "C");
        var parentA = new EntityKey("Organization", "A");
        var parentB = new EntityKey("Organization", "B");
        try (var tx = storage.beginTransaction(context)) {
            for (String id : new String[]{"A", "B", "C"}) {
                tx.createObject("Organization", id, Map.of("name", id, "nature", "DEPARTMENT", "status", "ACTIVE"));
            }
            tx.createLink("OrganizationParent", "C-A", child, parentA, Map.of("relation", "PARENT", "startedAt", NOW));
            var issue = tx.createObject("DataAssociationIssue", "org-issue", Map.of("category", "ORGANIZATION", "reason", "测试组织关联异常", "detectedAt", NOW, "status", "OPEN"));
            tx.createLink("IssueForOrganization", "issue-C", issue.key(), child, Map.of());
            var account = tx.createObject("UserAccount", "operator", Map.of("username", "operator", "state", "ACTIVE", "changedAt", NOW));
            tx.createLink("AccountCurrentOrganization", "operator-A", account.key(), parentA, Map.of());
            for (String id : new String[]{"source-one", "source-two"}) {
                var grant = tx.createObject("AuthorityGrant", id, Map.of("permission", "PERSON_READ",
                        "scopeKind", "CUSTOM_ORGS", "state", "ACTIVE", "sourceSystem", id, "validFrom", NOW));
                tx.createLink("GrantForAccount", id, grant.key(), account.key(), Map.of());
                tx.createLink("GrantScopeRoot", id + "-A", grant.key(), parentA, Map.of());
                tx.createLink("GrantScopeRoot", id + "-B", grant.key(), parentB, Map.of());
            }
            tx.commit();
        }
        try (var tx = storage.beginTransaction(context)) {
            assertThrows(IllegalStateException.class, () -> tx.createLink("OrganizationParent", "invalid",
                    child, parentB, Map.of("relation", "PARENT", "startedAt", NOW)), "One organization cannot have two current parents");
        }
        try (var tx = storage.beginTransaction(context)) {
            tx.deleteLink("OrganizationParent", "C-A", 1);
            tx.createLink("OrganizationParent", "C-B", child, parentB, Map.of("relation", "PARENT", "startedAt", NOW));
            tx.updateObject("AuthorityGrant", "source-one", Map.of("state", "REVOKED"), 1);
            tx.commit();
        }
        assertTrue(storage.queryObjects(context, "Person", QueryOptions.defaults()).isEmpty());
        var currentParents = storage.getLinks(context, child, "OrganizationParent",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults());
        assertEquals(1, currentParents.size());
        assertEquals(parentB, currentParents.getFirst().to());
        assertEquals(2, storage.getEntityHistory(context, new EntityKey("OrganizationParent", "C-A")).size());
        assertNotNull(storage.getLinkAtVersion(context, "OrganizationParent", "C-A", 1));
        assertEquals("ACTIVE", storage.getObject(context, "AuthorityGrant", "source-two").properties().get("state"));
        assertEquals(2, storage.getEntityHistory(context, new EntityKey("AuthorityGrant", "source-one")).size());
    }

    @Test
    void modelsSharedTagsMultipleCandidatesAndVersionIndependentRecipients() {
        var pack = new DomainPackLoader().load(packPath());
        var storage = new InMemoryStorageProvider();
        var context = RequestContext.system("tenant", "fixture");
        storage.applySchema(context, pack.ontology().schema());
        String deadline = "2026-09-27T08:00:00Z";
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Person", "p1", Map.of("name", "演示甲", "status", "ACTIVE", "identityStatus", "PENDING"));
            tx.createObject("Person", "p2", Map.of("name", "演示乙", "status", "ACTIVE", "identityStatus", "PENDING"));
            tx.createObject("TagDefinition", "tag", Map.of("code", "WORK", "name", "重点领域岗位", "status", "ACTIVE", "dimension", "WORK", "scope", "LONG_TERM", "level", 1));
            for (String id : new String[]{"c1", "c2"}) {
                var candidate = tx.createObject("TagCandidate", id, Map.of("state", "PENDING", "source", "AI", "reason", "测试候选依据", "createdAt", NOW));
                tx.createLink("TagCandidateForPerson", id, candidate.key(), new EntityKey("Person", "p1"), Map.of());
                var content = tx.createObject("ReminderContent", id, Map.of("title", id, "category", "专项", "state", "ACTIVE"));
                tx.createLink("ContentSuggestsTag", id, content.key(), new EntityKey("TagDefinition", "tag"), Map.of());
                var risk = tx.createObject("RiskEvent", id, Map.of("state", "OPEN", "category", "TEST_SIGNAL", "severity", "UNKNOWN", "occurredAt", NOW));
                tx.createLink("RiskInvolvesPerson", id, risk.key(), new EntityKey("Person", "p1"), Map.of());
            }
            var task = tx.createObject("ReminderTask", "task", Map.of("state", "ALL_SUCCESS", "sendMode", "IMMEDIATE", "readingWindow", "1d", "createdAt", NOW));
            var recipient = tx.createObject("RecipientRecord", "r1", Map.of("taskId", "task", "personId", "p1",
                    "firstDeliveredAt", "2026-09-26T08:00:00Z", "deadlineAt", deadline));
            tx.createLink("TaskHasRecipient", "task-r1", task.key(), recipient.key(), Map.of());
            tx.createLink("RecipientForPerson", "r1-p1", recipient.key(), new EntityKey("Person", "p1"), Map.of());
            for (String id : new String[]{"v1", "v2"}) {
                var version = tx.createObject("ReminderTaskVersion", id, Map.of("taskId", "task", "state", "PUBLISHED", "version", id, "titleSnapshot", "测试提醒", "bodySnapshot", "测试正文", "readingWindow", "1d"));
                tx.createLink("VersionTargetsRecipient", id, version.key(), recipient.key(), Map.of());
                var reading = tx.createObject("RecipientVersionState", id, Map.of("state", id.equals("v1") ? "READ" : "UNREAD", "publishedAt", NOW, "updatedAt", NOW));
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
            var person = tx.createObject("Person", "p", Map.of("name", "演示甲", "status", "ACTIVE", "identityStatus", "PENDING"));
            var issue = tx.createObject("TagProcessingIssue", "missing-birth-date", Map.of("state", "OPEN", "category", "RULE_UNCOMPUTABLE", "reason", "出生日期缺失", "detectedAt", NOW));
            tx.createLink("TagIssueForPerson", "issue-person", issue.key(), person.key(), Map.of());
            tx.commit();
        }
        assertTrue(storage.queryObjects(context, "PersonTagAssignment", QueryOptions.defaults()).isEmpty());
        try (var tx = storage.beginTransaction(context)) {
            var tag = tx.createObject("PersonTagAssignment", "tag", Map.of("state", "ACTIVE", "source", "STAGE", "tagVersion", "stage-v1", "effectiveFrom", NOW, "manualSuppressed", false));
            for (String id : new String[]{"A", "B"}) {
                var stage = tx.createObject("MatterStage", id, Map.of("name", id, "state", "ACTIVE", "sequence", 1));
                var contribution = tx.createObject("TagContribution", id, Map.of("state", "ACTIVE", "source", "STAGE", "sourceReference", id, "effectiveFrom", NOW));
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
