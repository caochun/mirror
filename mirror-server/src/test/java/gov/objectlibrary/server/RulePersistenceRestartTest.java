package gov.objectlibrary.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;
import java.util.Map;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

class RulePersistenceRestartTest {
    @TempDir Path temporary;

    @Test
    void resumesAOneHundredItemCheckpointWithoutDuplicatingContributionsOrEvaluations() {
        String[] args = {"--server.port=0", "--spring.datasource.url=jdbc:h2:file:" + temporary.resolve("rules"), "--mirror.pack=../domain-pack",
                "--mirror.bootstrap-password=RestartTest-Password-123", "--mirror.demo=true", "--mirror.scheduling-enabled=false",
                "--logging.level.root=WARN", "--debug=false"};
        var context = RequestContext.system("mirror", "fixture");
        String batchId;
        String ruleId;
        String stopBatchId;
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var storage = app.getBean(StorageProvider.class);
            try (var tx = storage.beginTransaction(context)) {
                for (int i = 0; i < 110; i++) {
                    var person = tx.createObject("Person", "extra-rule-person-" + i, Map.of("name", "批次验证" + i, "status", "ACTIVE", "identityStatus", "PENDING"));
                    tx.createLink("PersonBelongsToOrganization", "extra-rule-org-" + i, person.key(), new EntityKey("Organization", "demo-a"), Map.of("startedAt", "2026-01-01T00:00:00Z"));
                }
                tx.commit();
            }
            var actor = app.getBean(Accounts.class).actor("admin");
            String tagId = app.getBean(TagService.class).create(actor, new TagService.CreateTag("RESTART_RULE_TAG", "批次恢复标签", "", "PERSON", ""), "restart-rule-tag").get("id").toString();
            var rules = app.getBean(RuleConfigurationService.class);
            ruleId = rules.create(actor, tagId, "恢复规则", "restart-rule-create").get("id").toString();
            String previewId = rules.preview(actor, ruleId, Map.of("field", "organizationId", "operator", "EQ", "value", "demo-a"), "restart-rule-preview").get("id").toString();
            rules.processPreviews();
            var preview = rules.previewDetail(actor, previewId, 0, 20);
            batchId = rules.publish(actor, ruleId, new RuleConfigurationService.Publish(previewId, ((Number) preview.get("ruleVersion")).longValue(), preview.get("digest").toString()), "restart-rule-publish").get("batchId").toString();
            assertEquals(100, app.getBean(RuleBatchService.class).processPending());
            var batch = storage.getObject(context, "TagBatch", batchId);
            assertEquals("RUNNING", text(batch, "state"));
            assertEquals(100, RuleBatchService.number(batch, "cursor"));
        }
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var batches = app.getBean(RuleBatchService.class);
            assertEquals(34, batches.processPending());
            assertEquals(0, batches.processPending());
            var storage = app.getBean(StorageProvider.class);
            var batch = storage.getObject(context, "TagBatch", batchId);
            assertEquals("SUCCEEDED", text(batch, "state"));
            assertEquals(134, RuleBatchService.number(batch, "cursor"));
            assertEquals(134, storage.queryObjects(context, "TagEvaluation", new QueryOptions(200, 0, null, null, false)).size());
            assertEquals(118, storage.queryObjects(context, "TagContribution", new QueryOptions(200, 0, null, null, false)).size());
            var actor = app.getBean(Accounts.class).actor("admin");
            var stop = app.getBean(RuleDeactivationService.class);
            var rule = storage.getObject(context, "TagRule", ruleId);
            stopBatchId = stop.deactivate(actor, ruleId, new RuleDeactivationService.Deactivate(rule.version(), true), "restart-stop").get("batchId").toString();
            assertEquals(100, stop.processPending());
            assertEquals(100, RuleBatchService.number(storage.getObject(context, "TagBatch", stopBatchId), "cursor"));
        }
        try (var app = new SpringApplicationBuilder(MirrorApplication.class).run(args)) {
            var stop = app.getBean(RuleDeactivationService.class);
            assertEquals(18, stop.processPending());
            assertEquals(0, stop.processPending());
            var storage = app.getBean(StorageProvider.class);
            assertEquals("SUCCEEDED", text(storage.getObject(context, "TagBatch", stopBatchId), "state"));
            var sources = storage.queryObjects(context, "TagContribution", new QueryOptions(200, 0, null, null, false));
            assertEquals(118, sources.size());
            assertTrue(sources.stream().allMatch(source -> text(source, "state").equals("EXPIRED")));
            var evaluations = storage.queryObjects(context, "TagEvaluation", new QueryOptions(500, 0, null, null, false));
            assertEquals(252, evaluations.size(), "134 rule evaluations plus exactly 118 source endings across restarts");
        }
    }
}
