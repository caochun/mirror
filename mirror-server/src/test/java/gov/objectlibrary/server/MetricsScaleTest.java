package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_metrics_scale;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=false", "mirror.scheduling-enabled=false",
        "logging.level.org.springframework.jdbc=WARN", "logging.level.root=WARN", "debug=false"})
class MetricsScaleTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired StorageProvider storage;
    @Autowired Accounts accounts;
    @Autowired BusinessMetricsService metrics;

    @Test
    void projectsFiftyThousandObjectsWithoutTruncationAndKeepsAnotherTenantOutOfTheSnapshot() throws Exception {
        var context = RequestContext.system("scale", "fixture");
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Organization", "scale-root", Map.of("name", "规模测试单位", "nature", "CITY", "status", "ACTIVE"));
            tx.commit();
        }
        jdbc.update("""
                INSERT INTO mirror_accounts(username,password_hash,display_name,tenant_id,organization_id,role_name)
                SELECT 'scale-user',password_hash,'规模测试管理员','scale','scale-root','SUPER_ADMIN' FROM mirror_accounts WHERE username='admin'
                """);
        Instant now = Instant.now();
        // A projection fixture, not a benchmark of foundation history writes: populate its current-state rows in batches.
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var objects = connection.prepareStatement("INSERT INTO of_objects (tenant_id,object_type,object_id,version,created_at,updated_at,properties_json) VALUES ('scale','Person',?,1,?,?,?)");
                 var links = connection.prepareStatement("INSERT INTO of_links (tenant_id,link_type,link_id,from_type,from_id,to_type,to_id,version,created_at,updated_at,valid_from,properties_json) VALUES ('scale','PersonBelongsToOrganization',?,'Person',?,'Organization','scale-root',1,?,?,?,'{}')")) {
                for (int i = 0; i < 50000; i++) {
                    String id = "scale-person-" + i;
                    objects.setString(1, id);
                    objects.setTimestamp(2, Timestamp.from(now));
                    objects.setTimestamp(3, Timestamp.from(now));
                    objects.setString(4, "{\"name\":\"规模人员" + i + "\",\"status\":\"ACTIVE\",\"identityReference\":\"mock:" + id + "\"}");
                    objects.addBatch();
                    links.setString(1, "org-" + i);
                    links.setString(2, id);
                    for (int column = 3; column <= 5; column++) links.setTimestamp(column, Timestamp.from(now));
                    links.addBatch();
                    if ((i + 1) % 500 == 0) { objects.executeBatch(); links.executeBatch(); }
                }
            }
            connection.commit();
        }
        long start = System.nanoTime();
        var actor = accounts.actor("scale-user");
        var report = metrics.query(actor, BusinessMetricsService.Filter.all());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(50000, report.metrics().stream().filter(m -> m.code().equals("effectivePeople")).findFirst().orElseThrow().numerator());
        var last = metrics.details(actor, report.snapshotId(), "effectivePeople", "", 499, 100);
        assertEquals(50000, last.total());
        assertEquals(100, last.items().size());
        assertTrue(last.items().stream().allMatch(row -> row.organization().equals("规模测试单位")));
        assertTrue(millis < 5000, "50k metrics query took " + millis + "ms");
        System.out.println("METRICS_50K_QUERY_MS=" + millis);
        var other = metrics.query(accounts.actor("admin"), BusinessMetricsService.Filter.all());
        assertEquals("EMPTY", other.dataMode());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> metrics.details(accounts.actor("admin"), report.snapshotId(), "effectivePeople", "", 0, 20));
    }
}
