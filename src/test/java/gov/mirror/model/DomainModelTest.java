package gov.mirror.model;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Model feasibility only. These transactions are test fixtures, not implementations of Mirror workflows. */
class DomainModelTest {
    private static final RequestContext CTX = RequestContext.system("model-review", "reviewer");
    private static final Path PACK = Path.of("src", "main", "resources", "domain-pack");

    @Test
    void loadsTheWholePackWithRegisteredTypedBusinessCommands() {
        var pack = new DomainPackLoader().load(PACK);
        assertEquals("mirror.domain", pack.ontology().schema().namespace());
        assertEquals(14, pack.actions().size());
        assertEquals(14, pack.ontology().schema().actionTypes().size());
        assertEquals("OBJECT_MEMBERSHIP_WRITE", pack.ontology().schema().actionTypes().stream()
                .filter(type -> type.name().equals("DecideObjectMembership")).findFirst().orElseThrow().permission());
        assertTrue(pack.actions().keySet().containsAll(java.util.Set.of("DecideObjectMembership", "SuppressPersonTag", "RecordFirstRead")));
        assertTrue(pack.ontology().schema().objectTypes().stream().anyMatch(type -> type.name().equals("TagContribution")));
    }

    @TestFactory
    Stream<DynamicTest> actualProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "person transfer preserves ended relationship and state history", this::transfer),
                test(provider, "duplicate logical pairs and invalid values are rejected", this::constraints),
                test(provider, "failed relation transaction leaves no partial person", this::rollback),
                test(provider, "tag suppression retains independent evidence", this::tagEvidence),
                test(provider, "semantic versions and read receipts can remain independently addressable", this::versions),
                test(provider, "snapshots and receipts retain original version and organization", this::reminderGraph),
                test(provider, "trusted storage does not implement service authorization or minimum relations", this::serviceBoundary),
                test(provider, "source identity lifecycle is independent of business membership", this::membership),
                test(provider, "published content and pending review coexist with withdrawal", this::independentStates)));
    }

    private DynamicTest test(String provider, String name, Scenario scenario) {
        return DynamicTest.dynamicTest(provider + ": " + name, () -> {
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:model_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            try {
                storage.applySchema(CTX, new DomainPackLoader().load(PACK).ontology().schema());
                scenario.run(storage);
            } finally {
                if (storage instanceof AutoCloseable closeable) closeable.close();
            }
        });
    }

    private void transfer(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            person(tx, "p");
            tx.createObject("Organization", "a", Map.of("name", "A", "sourceStatus", "ACTIVE"));
            tx.createObject("Organization", "b", Map.of("name", "B", "sourceStatus", "ACTIVE"));
            tx.createLink("PersonCurrentOrganization", "pa", key("Person", "p"), key("Organization", "a"), Map.of());
            tx.commit();
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.createLink("PersonCurrentOrganization", "pb-invalid", key("Person", "p"), key("Organization", "b"), Map.of()));
        }
        try (var tx = storage.beginTransaction(CTX)) {
            tx.deleteLink("PersonCurrentOrganization", "pa", 1);
            tx.createLink("PersonCurrentOrganization", "pb", key("Person", "p"), key("Organization", "b"), Map.of());
            tx.updateObject("Person", "p", Map.of("name", "Changed"), 1);
            tx.commit();
        }
        assertEquals(2, storage.getEntityHistory(CTX, key("PersonCurrentOrganization", "pa")).size());
        assertEquals(2, storage.getEntityHistory(CTX, key("Person", "p")).size());
        assertEquals("Person", storage.getObjectAtVersion(CTX, "Person", "p", 1).state().get("name"));
        assertTrue(storage.getLink(CTX, "PersonCurrentOrganization", "pa").isDeleted());
        assertEquals("b", storage.getLink(CTX, "PersonCurrentOrganization", "pb").to().id());
    }

    private void constraints(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("PersonTag", "one", Map.of("pairKey", "person-tag", "suppression", "NONE"));
            tx.commit();
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.createObject("PersonTag", "two", Map.of("pairKey", "person-tag", "suppression", "NONE")));
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.updateObject("PersonTag", "one", Map.of("suppression", "UNKNOWN_STATE"), 1));
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.updateObject("PersonTag", "one", Map.of("pairKey", "changed"), 1));
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.updateObject("PersonTag", "one", Map.of("suppression", "NONE"), 2));
        }
    }

    private void rollback(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            person(tx, "p");
            assertThrows(RuntimeException.class, () -> tx.createLink("PersonCurrentOrganization", "broken", key("Person", "p"), key("Organization", "missing"), Map.of()));
        }
        assertNull(storage.getObject(CTX, "Person", "p"));
        assertNull(storage.getObject(RequestContext.system("other", "reviewer"), "Person", "p"));
    }

    private void tagEvidence(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            person(tx, "p");
            tx.createObject("Tag", "t", Map.of("status", "ENABLED"));
            tx.createObject("PersonTag", "pt", Map.of("pairKey", "p-t", "suppression", "NONE"));
            tx.createLink("PersonTagPerson", "ptp", key("PersonTag", "pt"), key("Person", "p"), Map.of());
            tx.createLink("PersonTagTag", "ptt", key("PersonTag", "pt"), key("Tag", "t"), Map.of());
            for (String kind : new String[]{"RULE", "MATTER"}) {
                tx.createObject("TagContribution", kind, Map.of("contributionKey", kind, "kind", kind, "status", "ACTIVE", "effectiveFrom", "2026-01-01T00:00:00Z"));
                tx.createLink("ContributionForPersonTag", kind, key("TagContribution", kind), key("PersonTag", "pt"), Map.of());
            }
            tx.commit();
        }
        try (var tx = storage.beginTransaction(CTX)) {
            tx.updateObject("PersonTag", "pt", Map.of("suppression", "SUPPRESSED", "suppressedBy", "reviewer"), 1);
            tx.updateObject("TagContribution", "MATTER", Map.of("status", "ENDED", "effectiveTo", "2026-02-01T00:00:00Z"), 1);
            tx.commit();
        }
        assertEquals("ACTIVE", storage.getObject(CTX, "TagContribution", "RULE").properties().get("status"));
        assertEquals("SUPPRESSED", storage.getObject(CTX, "PersonTag", "pt").properties().get("suppression"));
        assertEquals(2, storage.getEntityHistory(CTX, key("TagContribution", "MATTER")).size());
    }

    private void versions(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Tag", "t", Map.of("status", "ENABLED"));
            for (int revision = 1; revision <= 2; revision++) {
                String id = "v" + revision;
                tx.createObject("TagVersion", id, Map.of("versionKey", "t:" + revision, "revision", revision, "name", "Label " + revision,
                        "dimension", "PERSON", "duration", "LONG_TERM", "publishedAt", "2026-01-01T00:00:00Z", "publishedBy", "reviewer"));
                tx.createLink("TagVersionOf", id, key("TagVersion", id), key("Tag", "t"), Map.of());
            }
            tx.createLink("TagCurrentVersion", "current", key("Tag", "t"), key("TagVersion", "v2"), Map.of());
            tx.createObject("ReadReceipt", "r1", Map.of("readKey", "recipient:v1", "firstReadAt", "2026-01-01T00:00:00Z", "renderedContentHash", "hash1"));
            tx.createObject("ReadReceipt", "r2", Map.of("readKey", "recipient:v2", "firstReadAt", "2026-01-02T00:00:00Z", "renderedContentHash", "hash2"));
            tx.commit();
        }
        assertEquals("Label 1", storage.getObject(CTX, "TagVersion", "v1").properties().get("name"));
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.updateObject("TagVersion", "v1", Map.of("name", "Rewritten"), 1));
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.createObject("ReadReceipt", "duplicate", Map.of("readKey", "recipient:v1", "firstReadAt", "2026-01-03T00:00:00Z", "renderedContentHash", "hash1")));
        }
        assertEquals("hash2", storage.getObject(CTX, "ReadReceipt", "r2").properties().get("renderedContentHash"));
    }

    private void reminderGraph(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            person(tx, "p");
            tx.createObject("Organization", "org", Map.of("name", "Original unit", "sourceStatus", "ACTIVE"));
            tx.createObject("ReminderTask", "task", Map.of("lifecycle", "OPEN", "createdBy", "author", "createdAt", "2026-01-01T00:00:00Z"));
            tx.createLink("TaskCreatedIn", "task-org", key("ReminderTask", "task"), key("Organization", "org"), Map.of());
            tx.createObject("TaskRecipient", "recipient", Map.of("recipientKey", "task:p", "deliveryState", "NOT_SUBMITTED"));
            tx.createLink("RecipientTask", "rt", key("TaskRecipient", "recipient"), key("ReminderTask", "task"), Map.of());
            tx.createLink("RecipientPerson", "rp", key("TaskRecipient", "recipient"), key("Person", "p"), Map.of());
            tx.createObject("AudienceSnapshot", "audience", Map.of("snapshotKey", "task:audience1", "selection", Map.of("schemaVersion", 1),
                    "selectionHash", "audience-hash", "sendMode", "IMMEDIATE", "deadlineHours", 24,
                    "confirmedBy", "author", "confirmedAt", "2026-01-01T00:00:00Z", "capturedAt", "2026-01-01T00:00:00Z"));
            tx.createLink("AudienceTask", "at", key("AudienceSnapshot", "audience"), key("ReminderTask", "task"), Map.of());
            tx.createLink("TaskApprovedAudience", "approved-audience", key("ReminderTask", "task"), key("AudienceSnapshot", "audience"), Map.of());
            tx.createObject("RecipientSnapshot", "snapshot", Map.of("snapshotKey", "audience:recipient", "personName", "Original name",
                    "organizationName", "Original unit", "selectionBasis", Map.of("schemaVersion", 1, "origins", java.util.List.of("SPECIFIED")),
                    "capturedAt", "2026-01-01T00:00:00Z"));
            tx.createLink("SnapshotAudience", "sa", key("RecipientSnapshot", "snapshot"), key("AudienceSnapshot", "audience"), Map.of());
            tx.createLink("SnapshotRecipient", "sr", key("RecipientSnapshot", "snapshot"), key("TaskRecipient", "recipient"), Map.of());
            tx.createLink("SnapshotOrganization", "so", key("RecipientSnapshot", "snapshot"), key("Organization", "org"), Map.of());
            for (int revision = 1; revision <= 2; revision++) {
                String version = "version" + revision;
                tx.createObject("ReminderVersion", version, Map.of("versionKey", "task:" + revision, "revision", revision,
                        "state", "FROZEN", "title", "Reminder " + revision,
                        "body", Map.of("schemaVersion", 1, "blocks", java.util.List.of(Map.of("type", "paragraph", "text", "A reminder"))),
                        "category", "GENERAL"));
                tx.createLink("ReminderVersionOf", version, key("ReminderVersion", version), key("ReminderTask", "task"), Map.of());
                tx.createLink("VersionAudience", version, key("ReminderVersion", version), key("AudienceSnapshot", "audience"), Map.of());
            }
            tx.createObject("ReadReceipt", "read", Map.of("readKey", "recipient:version1", "firstReadAt", "2026-01-01T02:00:00Z", "renderedContentHash", "old-content"));
            tx.createLink("ReadRecipient", "rr", key("ReadReceipt", "read"), key("TaskRecipient", "recipient"), Map.of());
            tx.createLink("ReadVersion", "rv", key("ReadReceipt", "read"), key("ReminderVersion", "version1"), Map.of());
            tx.createLink("TaskPublishedVersion", "published1", key("ReminderTask", "task"), key("ReminderVersion", "version1"), Map.of());
            tx.commit();
        }
        try (var tx = storage.beginTransaction(CTX)) {
            tx.deleteLink("TaskPublishedVersion", "published1", 1);
            tx.createLink("TaskPublishedVersion", "published2", key("ReminderTask", "task"), key("ReminderVersion", "version2"), Map.of());
            tx.updateObject("Organization", "org", Map.of("name", "Renamed"), 1);
            tx.updateObject("Person", "p", Map.of("name", "Changed"), 1);
            tx.commit();
        }
        assertEquals("Original unit", storage.getObject(CTX, "RecipientSnapshot", "snapshot").properties().get("organizationName"));
        assertEquals("version1", storage.getLink(CTX, "ReadVersion", "rv").to().id());
        assertEquals("version2", storage.getLink(CTX, "TaskPublishedVersion", "published2").to().id());
        assertEquals(1, storage.getObject(CTX, "ReadReceipt", "read").version());
        assertEquals("audience", storage.getLink(CTX, "VersionAudience", "version1").to().id());
        assertEquals("audience", storage.getLink(CTX, "VersionAudience", "version2").to().id());
        assertEquals(2, storage.getEntityHistory(CTX, key("TaskPublishedVersion", "published1")).size());
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.updateObject("AudienceSnapshot", "audience", Map.of("deadlineHours", 48), 1));
        }
        try (var tx = storage.beginTransaction(CTX)) {
            assertThrows(RuntimeException.class, () -> tx.createLink("TaskPublishedVersion", "duplicate", key("ReminderTask", "task"), key("ReminderVersion", "version1"), Map.of()));
        }
    }

    private void serviceBoundary(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            person(tx, "p");
            tx.updateObject("Person", "p", Map.of("nationalIdRef", "protected-ref"), 1);
            tx.commit();
        }
        // SPI enforces at-most-one, not mandatory membership, and treats its caller as trusted.
        try (var tx = storage.beginTransaction(CTX)) {
            assertTrue(tx.findLinks("PersonCurrentOrganization", key("Person", "p"), null).isEmpty());
        }
        assertEquals("protected-ref", storage.getObject(CTX, "Person", "p").properties().get("nationalIdRef"));
        assertNull(storage.getObject(RequestContext.system("another-tenant", "reviewer"), "Person", "p"));
    }

    private void membership(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CTX)) {
            person(tx, "p");
            tx.createObject("ObjectMembership", "membership", Map.of("status", "IN_SCOPE", "decisionCode", "AUTHORITY_CONFIRMED",
                    "decidedBy", "source-adapter", "decidedAt", "2026-01-01T00:00:00Z"));
            tx.createLink("MembershipPerson", "mp", key("ObjectMembership", "membership"), key("Person", "p"), Map.of());
            tx.createObject("ExternalIdentity", "channel", Map.of("identityKey", "channel:p", "sourceSystem", "channel",
                    "kind", "CHANNEL_IDENTITY", "subjectRef", "protected-ref", "sourceStatus", "ACTIVE"));
            tx.createLink("IdentityPerson", "ip", key("ExternalIdentity", "channel"), key("Person", "p"), Map.of());
            tx.commit();
        }
        try (var tx = storage.beginTransaction(CTX)) {
            tx.updateObject("ExternalIdentity", "channel", Map.of("sourceStatus", "INACTIVE"), 1);
            tx.commit();
        }
        assertEquals("IN_SCOPE", storage.getObject(CTX, "ObjectMembership", "membership").properties().get("status"));
        assertEquals(1, storage.getObject(CTX, "Person", "p").version());
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("ObjectMembership", "other", Map.of("status", "EXCLUDED", "decisionCode", "MANUAL",
                    "decidedBy", "reviewer", "decidedAt", "2026-01-01T00:00:00Z"));
            assertThrows(RuntimeException.class, () -> tx.createLink("MembershipPerson", "other-mp", key("ObjectMembership", "other"), key("Person", "p"), Map.of()));
        }
        assertNull(storage.getObject(CTX, "ObjectMembership", "other"));
    }

    private void independentStates(StorageProvider storage) {
        reminderGraph(storage);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("ReminderVersion", "version3", Map.of("versionKey", "task:3", "revision", 3, "state", "FROZEN",
                    "title", "Proposed revision", "body", Map.of("schemaVersion", 1), "category", "GENERAL"));
            tx.createLink("ReminderVersionOf", "version3", key("ReminderVersion", "version3"), key("ReminderTask", "task"), Map.of());
            tx.createLink("VersionAudience", "version3", key("ReminderVersion", "version3"), key("AudienceSnapshot", "audience"), Map.of());
            tx.createLink("TaskWorkingVersion", "working", key("ReminderTask", "task"), key("ReminderVersion", "version3"), Map.of());
            tx.createObject("ReviewRound", "review3", Map.of("roundKey", "version3:1", "roundNumber", 1, "decision", "PENDING",
                    "submittedBy", "author", "submittedAt", "2026-01-03T00:00:00Z", "snapshotHash", "version3-hash"));
            tx.createLink("ReviewVersion", "review3", key("ReviewRound", "review3"), key("ReminderVersion", "version3"), Map.of());
            tx.updateObject("TaskRecipient", "recipient", Map.of("deliveryState", "SUCCEEDED", "firstDeliveredAt", "2026-01-01T00:00:00Z",
                    "deadlineAt", "2026-01-02T00:00:00Z"), 1);
            tx.createObject("Withdrawal", "withdrawal", Map.of("status", "PROCESSING", "reason", "Administrative withdrawal",
                    "requestedBy", "author", "requestedAt", "2026-01-03T00:00:00Z"));
            tx.createLink("WithdrawalRecipient", "wr", key("Withdrawal", "withdrawal"), key("TaskRecipient", "recipient"), Map.of());
            tx.commit();
        }
        assertEquals("version2", storage.getLink(CTX, "TaskPublishedVersion", "published2").to().id());
        assertEquals("PENDING", storage.getObject(CTX, "ReviewRound", "review3").properties().get("decision"));
        assertEquals("SUCCEEDED", storage.getObject(CTX, "TaskRecipient", "recipient").properties().get("deliveryState"));
        assertEquals("PROCESSING", storage.getObject(CTX, "Withdrawal", "withdrawal").properties().get("status"));
        assertEquals("OPEN", storage.getObject(CTX, "ReminderTask", "task").properties().get("lifecycle"));
    }

    private static EntityKey key(String type, String id) { return new EntityKey(type, id); }
    private static void person(Transaction tx, String id) {
        tx.createObject("Person", id, Map.of("name", "Person"));
    }
    private interface Scenario { void run(StorageProvider storage) throws Exception; }
}
