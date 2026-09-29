package gov.mirror.app;

import org.openfoundry.foundation.spi.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Explicit local acceptance fixtures. No people are silently created or overwritten. */
final class DemoData {
    static void populate(StorageProvider storage) {
        var context = RequestContext.system(MirrorAccounts.TENANT, "synthetic-catalog-bootstrap");
        try (var tx = storage.beginTransaction(context)) {
            tx.acquireWrite();
            if (tx.getObject("Organization", "org") != null) return;
            for (String id : List.of("org", "org2")) {
                tx.createObject("Organization", id, Map.of("name", id.equals("org") ? "示范单位（一）" : "示范单位（二）", "sourceStatus", "ACTIVE"));
            }
            tx.createObject("Tag", "tag", Map.of("status", "ENABLED"));
            tx.createObject("TagVersion", "tag-v1", Map.of("versionKey", "tag:1", "revision", 1,
                    "name", "工程建设领域（合成样例）", "dimension", "PERSON", "duration", "LONG_TERM",
                    "publishedAt", Instant.now().toString(), "publishedBy", context.actorId()));
            tx.createLink("TagVersionOf", "seed-version", new EntityKey("TagVersion", "tag-v1"), new EntityKey("Tag", "tag"), Map.of());
            tx.createLink("TagCurrentVersion", "seed-current", new EntityKey("Tag", "tag"), new EntityKey("TagVersion", "tag-v1"), Map.of());
            tx.appendAudit(new AuditEntry(java.util.UUID.randomUUID().toString(), Instant.now(), context.tenantId(),
                    context.actorId(), "BOOTSTRAP", null, null, null, tx.transactionId(), "SUCCESS",
                    Map.of("synthetic", true, "scope", "organizations-and-tag-catalog-only")));
            tx.commit();
        }
    }
}
