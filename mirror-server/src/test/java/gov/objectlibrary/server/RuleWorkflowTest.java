package gov.objectlibrary.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_rules;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true", "mirror.scheduling-enabled=false"})
@Import(DeliveryWorkflowTest.Config.class)
class RuleWorkflowTest {
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "rules-test");
    @Autowired RuleConfigurationService rules;
    @Autowired RuleBatchService batches;
    @Autowired TagService tags;
    @Autowired Accounts accounts;
    @Autowired StorageProvider storage;
    @Autowired RuleFacts facts;
    @Autowired DeliveryWorkflowTest.TestClock clock;

    @BeforeEach void time() { clock.now = Instant.parse("2035-01-01T00:00:00Z"); }
    private String key() { return UUID.randomUUID().toString(); }
    private Accounts.Actor admin() { return accounts.actor("admin"); }
    private Accounts.Actor unit() { return accounts.actor("unit"); }
    private ObjectRecord object(String type, String id) { return storage.getObject(CONTEXT, type, id); }

    private Fixture fixture() {
        String suffix = key();
        String org = "rule-org-" + suffix;
        String known = "rule-person-" + suffix;
        String missing = "rule-missing-" + suffix;
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var organization = tx.createObject("Organization", org, Map.of("name", "规则验证单位", "nature", "DEPARTMENT", "status", "ACTIVE"));
            tx.createLink("OrganizationParent", "parent-" + suffix, organization.key(), new EntityKey("Organization", "demo-a"), Map.of());
            for (String person : List.of(known, missing)) {
                var properties = new java.util.HashMap<String, Object>(Map.of("name", person.equals(known) ? "有依据人员" : "待补生日人员", "status", "ACTIVE", "identityStatus", "OK", "identityReference", "mock:" + person));
                if (person.equals(known)) properties.put("birthDate", "2000-01-01");
                var record = tx.createObject("Person", person, properties);
                tx.createLink("PersonBelongsToOrganization", "org-" + person, record.key(), organization.key(), Map.of());
            }
            tx.commit();
        }
        String tag = tags.create(admin(), new TagService.CreateTag("T" + suffix.replace("-", ""), "规则验证标签", "", "PERSON", ""), key()).get("id").toString();
        String rule = rules.create(admin(), tag, "年龄规则", key()).get("id").toString();
        return new Fixture(org, known, missing, tag, rule);
    }

    private Map<String, Object> condition(Fixture fixture, int age) {
        return Map.of("all", List.of(Map.of("field", "organizationId", "operator", "EQ", "value", fixture.org()),
                Map.of("field", "ageYears", "operator", "LT", "value", age)));
    }

    private String publish(Fixture fixture, int age) {
        String preview = rules.preview(admin(), fixture.rule(), condition(fixture, age), key()).get("id").toString();
        rules.processPreviews();
        var detail = rules.previewDetail(admin(), preview, 0, 100);
        assertEquals("READY", detail.get("state"));
        var result = rules.publish(admin(), fixture.rule(), new RuleConfigurationService.Publish(preview, object("TagRule", fixture.rule()).version(), detail.get("digest").toString()), key());
        return result.get("batchId").toString();
    }

    private void finish(String id) {
        for (int i = 0; i < 20 && !List.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED").contains(text(object("TagBatch", id), "state")); i++) batches.processPending();
        assertTrue(List.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED").contains(text(object("TagBatch", id), "state")));
    }

    @Test
    void typedConditionsRejectExecutableInputsAndUseThreeValuedLogic() {
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.validate(Map.of("field", "person.birthDate", "operator", "LT", "value", 40)));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.validate(Map.of("field", "rank", "operator", "REGEX", "value", ".*")));
        assertThrows(IllegalArgumentException.class, () -> RuleExpression.validate(Map.of("sql", "select * from Person")));
        var input = new RuleFacts.Input("p", "人员", "org", "单位", true, Map.of("rank", "KNOWN"), "digest", List.of());
        var unknown = Map.<String, Object>of("field", "ageYears", "operator", "LT", "value", 40);
        var known = Map.<String, Object>of("field", "rank", "operator", "EQ", "value", "KNOWN");
        assertEquals("MATCH", RuleExpression.evaluate(Map.of("any", List.of(unknown, known)), input).outcome());
        assertEquals("UNKNOWN", RuleExpression.evaluate(Map.of("all", List.of(unknown, known)), input).outcome());
        assertEquals("NO_MATCH", RuleExpression.evaluate(Map.of("all", List.of(unknown, Map.of("field", "rank", "operator", "EQ", "value", "OTHER"))), input).outcome());
    }

    @Test
    void previewDoesNotAssignAndBatchCreatesIndependentEvidenceAndLocalIssues() {
        var fixture = fixture();
        String preview = rules.preview(admin(), fixture.rule(), condition(fixture, 40), key()).get("id").toString();
        rules.processPreviews();
        var detail = rules.previewDetail(admin(), preview, 0, 100);
        assertEquals(1, detail.get("added"));
        assertEquals(1, detail.get("unknown"));
        assertNull(object("PersonTagAssignment", TagService.assignmentId(fixture.known(), fixture.tag())));
        String batch = rules.publish(admin(), fixture.rule(), new RuleConfigurationService.Publish(preview, object("TagRule", fixture.rule()).version(), detail.get("digest").toString()), key()).get("batchId").toString();
        finish(batch);
        assertEquals("ACTIVE", text(object("PersonTagAssignment", TagService.assignmentId(fixture.known(), fixture.tag())), "state"));
        assertNull(object("PersonTagAssignment", TagService.assignmentId(fixture.missing(), fixture.tag())));
        var issues = storage.getLinks(CONTEXT, new EntityKey("Person", fixture.missing()), "TagIssueForPerson", StorageProvider.Direction.INBOUND, QueryOptions.defaults());
        assertTrue(issues.stream().map(link -> object("TagProcessingIssue", link.from().id())).anyMatch(issue -> text(issue, "ruleId").equals(fixture.rule()) && text(issue, "state").equals("OPEN")));
        assertTrue(storage.queryObjects(CONTEXT, "ReminderTask", QueryOptions.defaults()).isEmpty(), "Tag rules cannot create reminders");
    }

    @Test
    void noMatchEndsOnlyThisRuleContributionAndNeverErasesManualSource() {
        var fixture = fixture();
        tags.assign(unit(), fixture.tag(), new TagService.AssignmentCommand(List.of(fixture.known()), Map.of(fixture.known(), 0L), "ADD", "人工依据", 1), key());
        finish(publish(fixture, 40));
        String assignment = TagService.assignmentId(fixture.known(), fixture.tag());
        assertEquals(2, batches.activeContributions(admin(), object("PersonTagAssignment", assignment)).size());
        finish(publish(fixture, 20));
        assertEquals("ACTIVE", text(object("PersonTagAssignment", assignment), "state"));
        assertEquals(List.of("MANUAL"), batches.activeContributions(admin(), object("PersonTagAssignment", assignment)).stream().map(c -> text(c, "source")).toList());
    }

    @Test
    void manualSuppressionSurvivesMatchingRecalculationAndRestoresOnlyByExplicitHumanAction() {
        var fixture = fixture();
        finish(publish(fixture, 40));
        String id = TagService.assignmentId(fixture.known(), fixture.tag());
        tags.assign(unit(), fixture.tag(), new TagService.AssignmentCommand(List.of(fixture.known()), Map.of(fixture.known(), object("PersonTagAssignment", id).version()), "REMOVE", "", 1), key());
        String batch = batches.start(admin(), List.of(fixture.rule()), List.of(fixture.known()), key()).get("id").toString();
        finish(batch);
        assertEquals("SUPPRESSED", text(object("PersonTagAssignment", id), "state"));
        tags.assign(unit(), fixture.tag(), new TagService.AssignmentCommand(List.of(fixture.known()), Map.of(fixture.known(), object("PersonTagAssignment", id).version()), "RESTORE", "", 1), key());
        assertEquals("ACTIVE", text(object("PersonTagAssignment", id), "state"));
    }

    @Test
    void changedDataInvalidatesPreviewAndAutomaticScanRecalculatesChangedPeople() {
        var fixture = fixture();
        String preview = rules.preview(admin(), fixture.rule(), condition(fixture, 40), key()).get("id").toString();
        rules.processPreviews();
        var details = rules.previewDetail(admin(), preview, 0, 100);
        var person = object("Person", fixture.known());
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(person.type(), person.id(), Map.of("birthDate", "1990-01-01"), person.version());
            tx.commit();
        }
        assertThrows(BusinessConflict.class, () -> rules.publish(admin(), fixture.rule(), new RuleConfigurationService.Publish(preview,
                object("TagRule", fixture.rule()).version(), details.get("digest").toString()), key()));
        finish(publish(fixture, 40));
        assertNull(object("PersonTagAssignment", TagService.assignmentId(fixture.known(), fixture.tag())));
        person = object("Person", fixture.known());
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(person.type(), person.id(), Map.of("birthDate", "2000-01-01"), person.version());
            tx.commit();
        }
        assertTrue(batches.scheduleChangedInputs() > 0);
        for (int i = 0; i < 10; i++) batches.processPending();
        assertEquals("ACTIVE", text(object("PersonTagAssignment", TagService.assignmentId(fixture.known(), fixture.tag())), "state"));
    }

    @Test
    void artificialRetirementRulesAndUnauthorizedConfigurationAreRejected() {
        String tag = tags.create(admin(), new TagService.CreateTag("RETIREMENT", "退休过渡期干部", "", "PERSON", ""), key()).get("id").toString();
        assertThrows(BusinessConflict.class, () -> rules.create(admin(), tag, "退休年龄猜测", key()));
        var fixture = fixture();
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> rules.preview(unit(), fixture.rule(), condition(fixture, 40), key()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> batches.start(unit(), List.of(fixture.rule()), List.of(), key()));
    }

    @Test
    void ageUsesTheBusinessDateAndStandardPositionEvidenceDoesNotUseFreeText() {
        var fixture = fixture();
        var person = object("Person", fixture.known());
        assertEquals(35, facts.load(admin(), person, java.util.Set.of("ageYears")).fields().get("ageYears"));
        clock.now = Instant.parse("2034-12-31T15:59:59Z");
        assertEquals(34, facts.load(admin(), person, java.util.Set.of("ageYears")).fields().get("ageYears"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var assignment = tx.createObject("Assignment", "job-" + key(), Map.of("title", "局长", "status", "ACTIVE"));
            tx.createLink("PersonHasAssignment", "person-job-" + key(), person.key(), assignment.key(), Map.of());
            tx.createLink("AssignmentInOrganization", "job-org-" + key(), assignment.key(), new EntityKey("Organization", fixture.org()), Map.of());
            tx.commit();
        }
        assertFalse(facts.load(admin(), person, java.util.Set.of("positionCode")).fields().containsKey("positionCode"));
    }

    private record Fixture(String org, String known, String missing, String tag, String rule) {}
}
