package gov.mirror.model;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.pack.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real Pack CEL/effects tests. The fixture authorizer is deliberately not a Mirror production identity provider. */
class DomainActionTest {
    static final LoadedDomainPack PACK = new DomainPackLoader().load(Path.of("src", "main", "resources", "domain-pack"));
    static final RequestContext CTX = RequestContext.system("action-tests", "reviewer");
    static final String TIME = "2026-01-01T00:00:00Z";

    @TestFactory
    Stream<DynamicTest> providers() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "all eight manifests execute with audit/outbox and replay", this::executeEveryAction),
                test(provider, "default deny and revoked replay", this::permissions),
                test(provider, "late denial rolls back multiple effects and metadata", this::rollback),
                test(provider, "stale expectedVersion and reference version reject writes", this::stale),
                test(provider, "wrong tag version and non-leaf reject manual contribution", this::tagGuards),
                test(provider, "read accepts FAILED delivery but rejects old/foreign/withdrawn content", this::readingGuards),
                test(provider, "first-read identity rejects duplicates without a second receipt", this::duplicateRead),
                test(provider, "independent review rejects self/expired/missing rejection comment", this::reviewGuards),
                test(provider, "cross-tenant references cannot be executed", this::tenant),
                test(provider, "manual registration creates pending membership atomically", this::manualRegistration),
                test(provider, "manual appointment is a separate fact", this::manualAppointment),
                test(provider, "first manual tag and contribution are one transaction", this::firstManualTag)));
    }

    DynamicTest test(String provider, String label, Scenario scenario) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            JdbcDataSource data = null;
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:actions_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            try {
                storage.applySchema(CTX, PACK.ontology().schema());
                var fixture = new Fixture(storage, data);
                fixture.seed();
                scenario.run(fixture);
            } finally {
                if (storage instanceof AutoCloseable closeable) closeable.close();
            }
        });
    }

    void executeEveryAction(Fixture f) throws Exception {
        for (String name : PACK.actions().keySet().stream().sorted().toList()) {
            var params = f.params(name);
            long count = f.count("audit");
            var first = f.execute(name, params, "request-" + name, f.policy);
            assertTrue(first.success(), name + ": " + first);
            var replay = f.execute(name, params, "request-" + name, f.policy);
            assertEquals(first.actionId(), replay.actionId(), name);
            assertEquals(count + 1, f.count("audit"), name);
            assertEquals(count + 1, f.count("outbox"), name);
        }
        assertEquals("SUPPRESSED", f.object("PersonTag", "pt").properties().get("suppression"));
        assertEquals("SUSPENDED", f.object("ObjectMembership", "membership").properties().get("status"));
        assertEquals("org2", f.storage.getLinks(CTX, key("Person", "p"), "PersonCurrentOrganization", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst().to().id());
        assertTrue(f.storage.getLink(CTX, "PersonCurrentOrganization", "person-org").isDeleted());
        assertEquals("recipient", f.storage.getLinks(CTX, key("ReadReceipt", "read"), "ReadRecipient", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst().to().id());
        assertEquals("APPROVED", f.object("ReviewRound", "review").properties().get("decision"));
        assertEquals("ACTIVE", f.object("TagContribution", "manual").properties().get("status"));
        assertEquals("UNMATCHED", f.object("RiskSignal", "risk").properties().get("status"));
    }

    void permissions(Fixture f) throws Exception {
        var params = f.params("SuppressPersonTag");
        assertThrows(SecurityException.class, () -> f.execute("SuppressPersonTag", params, "r", ActionAuthorizer.denyAll()));
        assertEquals(0, f.count("audit"));
        assertEquals(0, f.count("outbox"));
        assertTrue(f.execute("SuppressPersonTag", params, "r", f.policy).success());
        f.granted.set(false);
        assertThrows(SecurityException.class, () -> f.execute("SuppressPersonTag", params, "r", f.policy));
        assertEquals(1, f.count("audit"));
        assertEquals(2, f.object("PersonTag", "pt").version());
    }

    void rollback(Fixture f) throws Exception {
        var policy = new ActionAuthorizer() {
            public boolean allowed(RequestContext c, ActionActor a, ActionTypeDefinition d, Map<String, Object> p) { return true; }
            public boolean allowedEffects(RequestContext c, ActionActor a, ActionTypeDefinition d, Map<String, Object> p, List<ActionEffectAccess> effects, Transaction tx) {
                assertNotNull(tx.getObject("TagContribution", "manual"));
                assertEquals(5, effects.size());
                return false;
            }
        };
        var params = f.params("ApplyManualTagContribution");
        assertThrows(SecurityException.class, () -> f.execute("ApplyManualTagContribution", params, "r", policy));
        assertNull(f.object("TagContribution", "manual"));
        assertEquals(1, f.object("PersonTag", "pt").version());
        assertEquals(0, f.count("audit"));
        assertEquals(0, f.count("outbox"));
        // Same key can execute after rollback: no successful receipt was left behind.
        assertTrue(f.execute("ApplyManualTagContribution", params, "r", f.policy).success());
    }

    void stale(Fixture f) throws Exception {
        var params = f.params("SuppressPersonTag");
        params.put("expectedVersion", 99);
        assertFalse(f.execute("SuppressPersonTag", params, "stale", f.policy).success());
        assertEquals(0, f.count("audit"));
        params.put("expectedVersion", 1);
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.updateObject("PersonTag", "pt", Map.of("note", "concurrent"), 1);
            tx.commit();
        }
        assertThrows(RuntimeException.class, () -> f.execute("SuppressPersonTag", params, "stale", f.policy));
        assertEquals("NONE", f.object("PersonTag", "pt").properties().get("suppression"));
    }

    void tagGuards(Fixture f) throws Exception {
        var params = f.params("ApplyManualTagContribution");
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("Tag", "child", Map.of("status", "ENABLED"));
            tx.createLink("TagParent", "child-parent", key("Tag", "child"), key("Tag", "tag"), Map.of());
            tx.commit();
        }
        assertFalse(f.execute("ApplyManualTagContribution", params, "non-leaf", f.policy).success());
        assertNull(f.object("TagContribution", "manual"));
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.deleteLink("TagParent", "child-parent", 1);
            tx.deleteLink("TagVersionOf", "tv", 1);
            tx.createLink("TagVersionOf", "wrong-root", key("TagVersion", "tag-v1"), key("Tag", "child"), Map.of());
            tx.commit();
        }
        assertFalse(f.execute("ApplyManualTagContribution", params, "wrong-root", f.policy).success());
        assertEquals(0, f.count("outbox"));
    }

    void readingGuards(Fixture f) throws Exception {
        var params = f.params("RecordFirstRead");
        params.put("renderedContentHash", "forged");
        assertFalse(f.execute("RecordFirstRead", params, "bad-hash", f.policy).success());
        params.put("renderedContentHash", "hash");
        params.put("version", f.object("ReminderVersion", "revision"));
        assertFalse(f.execute("RecordFirstRead", params, "not-published", f.policy).success());
        params.put("version", f.object("ReminderVersion", "published"));
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("ReminderTask", "foreign-task", Map.of("lifecycle", "OPEN", "createdBy", "another", "createdAt", TIME));
            tx.createObject("TaskRecipient", "foreign-recipient", Map.of("recipientKey", "foreign:p", "deliveryState", "SUCCEEDED"));
            tx.createLink("RecipientTask", "foreign-rt", key("TaskRecipient", "foreign-recipient"), key("ReminderTask", "foreign-task"), Map.of());
            tx.createLink("RecipientPerson", "foreign-rp", key("TaskRecipient", "foreign-recipient"), key("Person", "p"), Map.of());
            tx.commit();
        }
        params.put("recipient", f.object("TaskRecipient", "foreign-recipient"));
        assertFalse(f.execute("RecordFirstRead", params, "foreign", f.policy).success());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.updateObject("TaskRecipient", "recipient", Map.of("withdrawnAt", TIME), 1);
            tx.commit();
        }
        params.put("recipient", f.object("TaskRecipient", "recipient"));
        assertFalse(f.execute("RecordFirstRead", params, "withdrawn", f.policy).success());
        assertNull(f.object("ReadReceipt", "read"));
        assertEquals(0, f.count("audit"));
    }

    void duplicateRead(Fixture f) throws Exception {
        var params = f.params("RecordFirstRead");
        assertTrue(f.execute("RecordFirstRead", params, "read-once", f.policy).success());
        var changed = new LinkedHashMap<>(params);
        changed.put("receiptId", "read-other");
        assertThrows(IllegalArgumentException.class, () -> f.execute("RecordFirstRead", changed, "read-once", f.policy));
        assertThrows(RuntimeException.class, () -> f.execute("RecordFirstRead", changed, "another-request", f.policy));
        assertNull(f.object("ReadReceipt", "read-other"));
        assertEquals(1, f.count("audit"));
    }

    void reviewGuards(Fixture f) throws Exception {
        var params = f.params("DecideReminderReview");
        params.put("decision", "REJECTED");
        params.put("comment", null);
        assertFalse(f.execute("DecideReminderReview", params, "no-comment", f.policy).success());
        params.put("decision", "APPROVED");
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("ReviewRound", "self", Map.of("roundKey", "self", "roundNumber", 2, "decision", "PENDING", "submittedBy", "reviewer", "submittedAt", TIME, "snapshotHash", "hash"));
            tx.createLink("ReviewVersion", "self-version", key("ReviewRound", "self"), key("ReminderVersion", "revision"), Map.of());
            tx.commit();
        }
        params.put("review", f.object("ReviewRound", "self"));
        assertFalse(f.execute("DecideReminderReview", params, "self", f.policy).success());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("AudienceSnapshot", "expired", Map.of("snapshotKey", "expired", "selection", Map.of(), "selectionHash", "selection",
                    "sendMode", "SCHEDULED", "scheduledAt", TIME, "deadlineHours", 24, "confirmedBy", "author", "confirmedAt", TIME, "capturedAt", TIME));
            tx.createLink("AudienceTask", "expired-at", key("AudienceSnapshot", "expired"), key("ReminderTask", "task"), Map.of());
            tx.deleteLink("VersionAudience", "revision", 1);
            tx.createLink("VersionAudience", "expired-va", key("ReminderVersion", "revision"), key("AudienceSnapshot", "expired"), Map.of());
            tx.deleteLink("TaskPublishedVersion", "published", 1);
            tx.commit();
        }
        params.put("review", f.object("ReviewRound", "review"));
        assertFalse(f.execute("DecideReminderReview", params, "expired-initial", f.policy).success());
        // A content revision must not inherit the expired original sending schedule as a new deadline.
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createLink("TaskPublishedVersion", "published-again", key("ReminderTask", "task"), key("ReminderVersion", "published"), Map.of());
            tx.commit();
        }
        assertTrue(f.execute("DecideReminderReview", params, "content-revision", f.policy).success());
        assertEquals(1, f.count("audit"));
    }

    void tenant(Fixture f) {
        var params = f.params("SuppressPersonTag");
        var definition = f.definition("SuppressPersonTag");
        assertThrows(SecurityException.class, () -> new ActionExecutor().withParameterSchema(PACK.ontology().schema()).withAuthorization(f.policy)
                .execute(PACK.actions().get("SuppressPersonTag"), definition, RequestContext.system("other", "reviewer"),
                        f.actor(definition), params, "cross", f.storage));
    }

    void manualRegistration(Fixture f) {
        var params = f.params("RegisterManualPerson");
        var result = f.execute("RegisterManualPerson", params, "manual-person", f.policy);
        assertTrue(result.success(), result.toString());
        var person = f.storage.queryObjects(CTX, "Person", new QueryOptions(100, 0, null, null, false)).stream()
                .filter(o -> o.properties().get("name").equals("Manual person")).findFirst().orElseThrow();
        var membership = f.storage.getLinks(CTX, person.key(), "MembershipPerson", StorageProvider.Direction.INBOUND, QueryOptions.defaults()).getFirst().from();
        assertEquals("PENDING", f.object(membership.type(), membership.id()).properties().get("status"));
    }

    void manualAppointment(Fixture f) {
        var result = f.execute("RecordManualAppointment", f.params("RecordManualAppointment"), "manual-appointment", f.policy);
        assertTrue(result.success(), result.toString());
        assertTrue(f.storage.queryObjects(CTX, "Appointment", new QueryOptions(100, 0, null, null, false)).stream()
                .anyMatch(o -> "MANUAL".equals(o.properties().get("sourceSystem"))));
    }

    void firstManualTag(Fixture f) {
        var result = f.execute("AssignManualTag", f.params("AssignManualTag"), "first-manual-tag", f.policy);
        assertTrue(result.success(), result.toString());
        assertEquals("ACTIVE", f.object("TagContribution", "new-contribution").properties().get("status"));
        assertEquals("new-pt", f.storage.getLinks(CTX, key("TagContribution", "new-contribution"), "ContributionForPersonTag", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst().to().id());
    }

    static final class Fixture {
        final StorageProvider storage;
        final JdbcDataSource data;
        final AtomicBoolean granted = new AtomicBoolean(true);
        final ActionAuthorizer policy = (context, actor, definition, parameters) -> granted.get() && actor.roles().contains(definition.permission());
        Fixture(StorageProvider storage, JdbcDataSource data) { this.storage = storage; this.data = data; }
        ObjectRecord object(String type, String id) { return storage.getObject(CTX, type, id); }
        ActionTypeDefinition definition(String action) { return PACK.ontology().schema().actionTypes().stream().filter(type -> type.name().equals(action)).findFirst().orElseThrow(); }
        ActionActor actor(ActionTypeDefinition definition) { return new ActionActor("reviewer", Set.of(definition.permission())); }
        ActionResult execute(String action, Map<String, Object> params, String request, ActionAuthorizer policy) {
            var definition = definition(action);
            return new ActionExecutor().withParameterSchema(PACK.ontology().schema()).withAuthorization(policy)
                    .execute(PACK.actions().get(action), definition, CTX, actor(definition), params, request, storage);
        }
        long count(String kind) throws Exception {
            if (storage instanceof InMemoryStorageProvider memory) return kind.equals("audit") ? memory.auditEntries(CTX).size() : memory.outboxEntries(CTX).size();
            String table = kind.equals("audit") ? "of_audit_records" : "of_outbox_events";
            try (var connection = data.getConnection(); var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?")) {
                query.setString(1, CTX.tenantId());
                try (var rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
            }
        }
        Map<String, Object> params(String action) {
            var p = new LinkedHashMap<String, Object>();
            // Optional keys are explicitly resolved to null, as the authenticated ApplicationService does.
            definition(action).parameters().forEach(parameter -> p.put(parameter.name(), null));
            switch (action) {
                case "DecideObjectMembership" -> p.putAll(Map.of("membership", object("ObjectMembership", "membership"), "expectedVersion", 1, "status", "SUSPENDED", "decisionCode", "CONFIRMED", "decisionOrganization", object("Organization", "org")));
                case "RegisterManualOrganization" -> p.put("name", "Manual organization");
                case "RegisterManualChildOrganization" -> p.putAll(Map.of("parent", object("Organization", "org"), "name", "Manual child"));
                case "RegisterManualPosition" -> p.putAll(Map.of("name", "Manual position", "standardCategory", "ADMIN"));
                case "RegisterManualPerson" -> p.putAll(Map.of("organization", object("Organization", "org"), "name", "Manual person", "note", "Verified in person"));
                case "RecordManualAppointment" -> p.putAll(Map.of("person", object("Person", "p"), "organization", object("Organization", "org"), "position", object("Position", "position"), "startedOn", "2026-01-01", "appointmentRoleCode", "HANDLER"));
                case "TransferCurrentOrganization" -> p.putAll(Map.of("person", object("Person", "p"), "expectedVersion", 1, "organization", object("Organization", "org2")));
                case "AssignManualTag" -> p.putAll(Map.ofEntries(
                        Map.entry("person", object("Person", "p")), Map.entry("expectedPersonVersion", 1),
                        Map.entry("membership", object("ObjectMembership", "membership")), Map.entry("expectedMembershipVersion", 1),
                        Map.entry("tag", object("Tag", "tag")), Map.entry("expectedTagVersion", 1),
                        Map.entry("tagVersion", object("TagVersion", "tag-v1")), Map.entry("sourceOrganization", object("Organization", "org")),
                        Map.entry("personTagId", "new-pt"), Map.entry("pairKey", "p:tag-new"),
                        Map.entry("contributionId", "new-contribution"), Map.entry("contributionKey", "new-contribution-key")));
                case "SuppressPersonTag" -> p.putAll(Map.of("personTag", object("PersonTag", "pt"), "expectedVersion", object("PersonTag", "pt").version(), "note", "manual suppression"));
                case "ApplyManualTagContribution" -> p.putAll(Map.of("personTag", object("PersonTag", "pt"), "expectedVersion", 1, "tagVersion", object("TagVersion", "tag-v1"), "sourceOrganization", object("Organization", "org"), "contributionId", "manual", "contributionKey", "manual-key"));
                case "RecordMatterParticipation" -> p.putAll(Map.of("stage", object("MatterStage", "stage"), "person", object("Person", "p"), "role", "HANDLER", "participationId", "participation"));
                case "RecordRiskSignal" -> p.putAll(Map.of("organization", object("Organization", "org"), "signalId", "risk", "sourceKey", "source-risk", "sourceSystem", "trusted", "summary", "Risk evidence", "reportedAt", TIME));
                case "DecideReminderReview" -> p.putAll(Map.of("review", object("ReviewRound", "review"), "expectedVersion", 1, "decision", "APPROVED"));
                case "RecordFirstRead" -> p.putAll(Map.of("recipient", object("TaskRecipient", "recipient"), "version", object("ReminderVersion", "published"), "expectedVersion", 1, "receiptId", "read", "readKey", "recipient:published", "renderedContentHash", "hash"));
                default -> throw new AssertionError(action);
            }
            return p;
        }
        void seed() {
            try (var tx = storage.beginTransaction(CTX)) {
                tx.createObject("Person", "p", Map.of("name", "Person"));
                for (String org : List.of("org", "org2")) tx.createObject("Organization", org, Map.of("name", org, "sourceStatus", "ACTIVE"));
                tx.createLink("PersonCurrentOrganization", "person-org", key("Person", "p"), key("Organization", "org"), Map.of());
                tx.createObject("ObjectMembership", "membership", Map.of("status", "IN_SCOPE", "decisionCode", "INITIAL", "decidedBy", "source", "decidedAt", TIME));
                tx.createLink("MembershipPerson", "membership-person", key("ObjectMembership", "membership"), key("Person", "p"), Map.of());
                tx.createLink("MembershipDecisionOrganization", "membership-org", key("ObjectMembership", "membership"), key("Organization", "org2"), Map.of());
                tx.createObject("Position", "position", Map.of("name", "Project Handler", "sourceStatus", "ACTIVE"));
                tx.createObject("Tag", "tag", Map.of("status", "ENABLED"));
                tx.createObject("TagVersion", "tag-v1", Map.of("versionKey", "tag:1", "revision", 1, "name", "Tag", "dimension", "PERSON", "duration", "LONG_TERM", "publishedAt", TIME, "publishedBy", "publisher"));
                tx.createLink("TagVersionOf", "tv", key("TagVersion", "tag-v1"), key("Tag", "tag"), Map.of());
                tx.createLink("TagCurrentVersion", "tc", key("Tag", "tag"), key("TagVersion", "tag-v1"), Map.of());
                tx.createObject("PersonTag", "pt", Map.of("pairKey", "p:tag", "suppression", "NONE"));
                tx.createLink("PersonTagPerson", "ptp", key("PersonTag", "pt"), key("Person", "p"), Map.of());
                tx.createLink("PersonTagTag", "ptt", key("PersonTag", "pt"), key("Tag", "tag"), Map.of());
                tx.createObject("SupervisionMatter", "matter", Map.of("name", "Matter", "category", "PROJECT", "status", "ACTIVE"));
                tx.createObject("MatterStage", "stage", Map.of("name", "Stage", "kind", "DURING", "status", "ACTIVE"));
                tx.createLink("StageMatter", "sm", key("MatterStage", "stage"), key("SupervisionMatter", "matter"), Map.of());
                tx.createObject("ReminderTask", "task", Map.of("lifecycle", "OPEN", "createdBy", "author", "createdAt", TIME));
                tx.createLink("TaskCreatedIn", "task-org", key("ReminderTask", "task"), key("Organization", "org"), Map.of());
                tx.createObject("AudienceSnapshot", "audience", Map.of("snapshotKey", "audience", "selection", Map.of(), "selectionHash", "selection", "sendMode", "IMMEDIATE", "deadlineHours", 24, "confirmedBy", "author", "confirmedAt", TIME, "capturedAt", TIME));
                tx.createLink("AudienceTask", "audience-task", key("AudienceSnapshot", "audience"), key("ReminderTask", "task"), Map.of());
                for (String version : List.of("published", "revision")) {
                    tx.createObject("ReminderVersion", version, Map.of("versionKey", version, "revision", version.equals("published") ? 1 : 2, "state", "FROZEN", "title", "Reminder", "body", Map.of(), "category", "GENERAL", "contentHash", "hash"));
                    tx.createLink("ReminderVersionOf", version, key("ReminderVersion", version), key("ReminderTask", "task"), Map.of());
                    tx.createLink("VersionAudience", version, key("ReminderVersion", version), key("AudienceSnapshot", "audience"), Map.of());
                }
                tx.createLink("TaskPublishedVersion", "published", key("ReminderTask", "task"), key("ReminderVersion", "published"), Map.of());
                tx.createLink("TaskWorkingVersion", "working", key("ReminderTask", "task"), key("ReminderVersion", "revision"), Map.of());
                tx.createObject("TaskRecipient", "recipient", Map.of("recipientKey", "task:p", "deliveryState", "FAILED"));
                tx.createLink("RecipientTask", "rt", key("TaskRecipient", "recipient"), key("ReminderTask", "task"), Map.of());
                tx.createLink("RecipientPerson", "rp", key("TaskRecipient", "recipient"), key("Person", "p"), Map.of());
                tx.createObject("ReviewRound", "review", Map.of("roundKey", "review:1", "roundNumber", 1, "decision", "PENDING", "submittedBy", "author", "submittedAt", TIME, "snapshotHash", "hash"));
                tx.createLink("ReviewVersion", "review-version", key("ReviewRound", "review"), key("ReminderVersion", "revision"), Map.of());
                tx.commit();
            }
        }
    }
    static EntityKey key(String type, String id) { return new EntityKey(type, id); }
    interface Scenario { void run(Fixture fixture) throws Exception; }
}
