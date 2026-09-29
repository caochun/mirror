package gov.mirror.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceDemoDataTest {
    @TempDir Path directory;
    static final RequestContext CTX = RequestContext.system(MirrorAccounts.TENANT, "test");
    static final QueryOptions ALL = new QueryOptions(1000, 0, null, null, false);

    private JdbcStorageProvider open(JdbcDataSource data) throws Exception {
        var provider = new JdbcStorageProvider(data, DatabaseDialect.h2());
        try (var resources = PackResources.open(null)) {
            provider.applySchema(CTX, new DomainPackLoader().load(resources.directory()).ontology().schema());
        }
        return provider;
    }

    private JdbcDataSource source() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:file:" + directory.resolve("reference"));
        return data;
    }

    @Test
    void coherentSnapshotPersistsAndDoesNotOverwriteLaterEditsOnRestart() throws Exception {
        var data = source();
        try (var connection = data.getConnection(); var storage = open(data)) {
            ReferenceDemoData.populate(storage);
            assertEquals(15, storage.queryObjects(CTX, "Person", ALL).size());
            assertEquals(18, storage.queryObjects(CTX, "Organization", ALL).size());
            assertEquals(10, storage.queryObjects(CTX, "Position", ALL).size());
            assertEquals(15, storage.queryObjects(CTX, "Appointment", ALL).size());
            assertEquals(22, storage.queryObjects(CTX, "Tag", ALL).size());
            assertEquals(7, storage.queryObjects(CTX, "TagContribution", ALL).size());
            assertEquals("INACTIVE", storage.getObject(CTX, "ExternalIdentity", "ref-person-p007-source").properties().get("sourceStatus"));
            assertEquals("IN_SCOPE", storage.getObject(CTX, "ObjectMembership", "ref-person-p007-membership").properties().get("status"));
            try (var tx = storage.beginTransaction(CTX)) {
                tx.updateObject("Person", "ref-person-p001", Map.of("name", "Edited by user"), 1);
                tx.commit();
            }
        }
        try (var connection = data.getConnection(); var storage = open(data)) {
            ReferenceDemoData.populate(storage);
            var edited = storage.getObject(CTX, "Person", "ref-person-p001");
            assertEquals("Edited by user", edited.properties().get("name"));
            assertEquals(2, edited.version());
            assertEquals(2, storage.getEntityHistory(CTX, edited.key()).size());
            try (var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM of_audit_records")) {
                    rows.next(); assertEquals(1, rows.getInt(1));
                }
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM of_outbox_events")) {
                    rows.next(); assertEquals(0, rows.getInt(1));
                }
            }
            try (var input = new ClassPathResource(ReferenceDemoData.RESOURCE).getInputStream()) {
                var json = new ObjectMapper();
                var tree = json.readTree(input);
                ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("description", "changed dataset");
                assertThrows(IllegalStateException.class, () -> ReferenceDemoData.populate(storage, json.writeValueAsBytes(tree)));
            }
        }
    }

    @Test
    void invalidLateRelationshipRollsBackAllObjectsAndAllowsCleanRetry() throws Exception {
        var data = source();
        try (var connection = data.getConnection(); var storage = open(data);
             var input = new ClassPathResource(ReferenceDemoData.RESOURCE).getInputStream()) {
            var json = new ObjectMapper();
            var tree = json.readTree(input);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("assignments").get(6)).put("person", "missing-person");
            assertThrows(IllegalArgumentException.class, () -> ReferenceDemoData.populate(storage, json.writeValueAsBytes(tree)));
            assertTrue(storage.queryObjects(CTX, "Person", ALL).isEmpty());
            assertTrue(storage.queryObjects(CTX, "Organization", ALL).isEmpty());
            assertTrue(storage.queryObjects(CTX, "TagContribution", ALL).isEmpty());
            ReferenceDemoData.populate(storage);
            assertEquals(15, storage.queryObjects(CTX, "Person", ALL).size());
        }
    }

    @Test
    void existingIdConflictPreservesExistingDataAndRollsBackTheRest() throws Exception {
        var data = source();
        try (var connection = data.getConnection(); var storage = open(data)) {
            try (var tx = storage.beginTransaction(CTX)) {
                tx.createObject("Person", "ref-person-p001", Map.of("name", "Existing record"));
                tx.commit();
            }
            assertThrows(IllegalStateException.class, () -> ReferenceDemoData.populate(storage));
            assertEquals("Existing record", storage.getObject(CTX, "Person", "ref-person-p001").properties().get("name"));
            assertEquals(1, storage.queryObjects(CTX, "Person", ALL).size());
            assertTrue(storage.queryObjects(CTX, "Organization", ALL).isEmpty());
        }
    }
}
