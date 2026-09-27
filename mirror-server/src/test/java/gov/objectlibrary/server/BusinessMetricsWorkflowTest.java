package gov.objectlibrary.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mirror_metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "mirror.pack=../domain-pack", "mirror.bootstrap-password=TestOnlyPassword-123", "mirror.demo=true",
        "mirror.delivery-mode=mock", "mirror.scheduling-enabled=false"})
@Import(DeliveryWorkflowTest.Config.class)
class BusinessMetricsWorkflowTest {
    private static final RequestContext CONTEXT = RequestContext.system("mirror", "metrics-test");
    @Autowired BusinessMetricsService metrics;
    @Autowired Accounts accounts;
    @Autowired TagService tags;
    @Autowired ReminderService reminders;
    @Autowired ReminderRevisionService revisions;
    @Autowired DeliveryService delivery;
    @Autowired ReceiverService receiver;
    @Autowired ReceiverSessions sessions;
    @Autowired WithdrawalService withdrawals;
    @Autowired StorageProvider storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryWorkflowTest.TestClock clock;
    @Autowired DeliveryWorkflowTest.TestChannel channel;

    @BeforeEach
    void time() {
        clock.now = Instant.parse("2035-01-01T00:00:00Z");
        channel.outcomes.clear();
        channel.crashAfterAccept = false;
    }
    private String key() { return UUID.randomUUID().toString(); }
    private Accounts.Actor unit() { return accounts.actor("unit"); }
    private BusinessMetricsService.Filter filter(String task, String tag, String version, String organization, boolean withdrawn) {
        return new BusinessMetricsService.Filter(organization, "", "", tag, version, "", task, null, null, withdrawn);
    }
    private BusinessMetricsService.Metric metric(BusinessMetricsService.Report report, String code) {
        return report.metrics().stream().filter(m -> m.code().equals(code)).findFirst().orElseThrow();
    }
    private String published(List<String> people, List<String> selectedTags, String outcome) {
        var saved = reminders.save(unit(), null, new ReminderService.Draft(0, "统计任务 " + key(), "<p>请依规履职。</p>", "履责", "1d", null,
                new ReminderSelection.Filter(List.of(), selectedTags, "ANY", people, List.of()), List.of(), ""), key());
        String id = saved.get("id").toString();
        var confirmed = reminders.confirm(unit(), id, new ReminderService.Confirmation(((Number) saved.get("version")).longValue(),
                saved.get("selectionDigest").toString(), ((Number) saved.get("recipientCount")).intValue(), true,
                saved.get("contentDigest").toString(), true, true, List.of()), key());
        var submitted = reminders.submit(unit(), id, ((Number) confirmed.get("version")).longValue(), key());
        var approved = reminders.decide(accounts.actor("reviewer"), id,
                new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", ""), key());
        for (String person : people) channel.outcomes.put("recipient-" + BusinessCommands.hash(id + "/" + person), outcome);
        delivery.dispatchMock(unit(), id, ((Number) approved.get("version")).longValue());
        return id;
    }
    private void read(String task, String person) {
        String recipient = "recipient-" + BusinessCommands.hash(task + "/" + person);
        var session = new MockHttpSession();
        String url = receiver.issueMock(unit(), task, recipient, session);
        String grant = sessions.exchange(url.substring(url.indexOf("#ticket=") + 8), session);
        var content = receiver.content(grant, session);
        receiver.read(grant, session, content.versionId(), content.renderToken(), true);
    }

    @Test
    void numbersAndDetailsUseTheSameScopeAndSensitiveFieldsNeverEnterTheProjection() {
        var report = metrics.query(unit(), BusinessMetricsService.Filter.all());
        assertEquals(16, metric(report, "effectivePeople").numerator());
        assertEquals("MOCK_RECORDS", report.dataMode());
        assertEquals(12, report.metrics().size());
        var detail = metrics.details(unit(), report.snapshotId(), "effectivePeople", "", 0, 100);
        assertEquals(16, detail.total());
        assertTrue(detail.items().stream().noneMatch(row -> row.organization().equals("演示二局")));
        assertThrows(AccessDeniedException.class, () -> metrics.query(unit(), filter("", "", "", "demo-b", true)));
        assertThrows(AccessDeniedException.class, () -> metrics.query(accounts.actor("reviewer"), BusinessMetricsService.Filter.all()));
        assertThrows(AccessDeniedException.class, () -> metrics.details(accounts.actor("admin"), report.snapshotId(), "effectivePeople", "", 0, 20));
        assertThrows(IllegalArgumentException.class, () -> metrics.details(unit(), report.snapshotId(), "integrityRanking", "", 0, 20));
        jdbc.update("DELETE FROM mirror_role_permissions WHERE role_name='UNIT_ADMIN' AND permission_name='METRICS_READ'");
        try {
            assertThrows(AccessDeniedException.class, () -> metrics.details(unit(), report.snapshotId(), "effectivePeople", "", 0, 20));
        } finally { jdbc.update("INSERT INTO mirror_role_permissions VALUES ('UNIT_ADMIN','METRICS_READ')"); }
    }

    @Test
    void deliveryUnknownCanBeReadAndRatiosNeverDivideTheWrongPopulations() {
        String task = published(List.of("demo-person-001"), List.of(), "UNKNOWN");
        read(task, "demo-person-001");
        var report = metrics.query(unit(), filter(task, "", "", "", true));
        assertEquals(1, metric(report, "targetRecipients").numerator());
        assertNull(metric(report, "readRateAmongDelivered").value());
        assertEquals(100.0, metric(report, "readCoverageAmongTargets").value());
        assertEquals(1, metric(report, "integrationIssues").numerator());
        assertEquals(1, metrics.details(unit(), report.snapshotId(), "readCoverageAmongTargets", "", 0, 20).total());
        assertEquals(0, metric(report, "overdueUnread").numerator());
    }

    @Test
    void aRevisionKeepsTargetCountsResetsReadingAndInvalidatesTheOldReadSnapshot() {
        String task = published(List.of("demo-person-001", "demo-person-009"), List.of(), "DELIVERED");
        read(task, "demo-person-001");
        var before = metrics.query(unit(), filter(task, "", "", "", true));
        assertEquals(50.0, metric(before, "readRateAmongDelivered").value());
        clock.now = clock.now.plusSeconds(86401);
        var beforePublication = metrics.query(unit(), filter(task, "", "", "", true));
        var stored = storage.getObject(CONTEXT, "ReminderTask", task);
        var saved = revisions.save(unit(), task, new ReminderRevisionService.Draft(stored.version(), "统计修订", "<p>新正文</p>"), key());
        var check = revisions.confirm(unit(), task, new ReminderRevisionService.Confirmation(((Number) saved.get("version")).longValue(),
                saved.get("contentDigest").toString(), true, List.of()), key());
        var submitted = revisions.submit(unit(), task, ((Number) check.get("version")).longValue(), key());
        revisions.decide(accounts.actor("reviewer"), task, new ReminderService.Decision(((Number) submitted.get("version")).longValue(), "APPROVE", ""), key());
        revisions.publishApproved();
        var after = metrics.query(unit(), filter(task, "", "", "", true));
        assertEquals(2, metric(after, "targetRecipients").numerator());
        assertEquals(0.0, metric(after, "readRateAmongDelivered").value());
        assertEquals(2, metric(after, "overdueUnread").numerator());
        assertTrue(assertThrows(BusinessConflict.class, () -> metrics.details(unit(), beforePublication.snapshotId(), "readRateAmongDelivered", "", 0, 20)).getMessage().contains("发布版本"));
    }

    @Test
    void frozenPerRecipientTagsDriveHistoricalFilteringAfterCurrentTagsChange() {
        String code = "M" + key().replace("-", "");
        String tag = tags.create(accounts.actor("admin"), new TagService.CreateTag(code, "原标签", "", "PERSON", ""), key()).get("id").toString();
        tags.assign(unit(), tag, new TagService.AssignmentCommand(List.of("demo-person-001"), Map.of("demo-person-001", 0L), "ADD", "", 1), key());
        String task = published(List.of("demo-person-001", "demo-person-009"), List.of(), "DELIVERED");
        String oldVersion = tag + "-v1";
        tags.edit(accounts.actor("admin"), tag, new TagService.EditTag("新标签名称", "", "ACTIVE", 1), key());
        var report = metrics.query(unit(), filter(task, tag, oldVersion, "", true));
        assertEquals(1, metric(report, "targetRecipients").numerator(), "Only the historically tagged recipient should match, not the entire task");
        String assignmentId = TagService.assignmentId("demo-person-001", tag);
        var assignment = storage.getObject(CONTEXT, "PersonTagAssignment", assignmentId);
        tags.assign(unit(), tag, new TagService.AssignmentCommand(List.of("demo-person-001"), Map.of("demo-person-001", assignment.version()), "REMOVE", "", 2), key());
        var after = metrics.query(unit(), filter(task, tag, oldVersion, "", true));
        assertEquals(1, metric(after, "targetRecipients").numerator());
        assertEquals(0, metric(after, "tagCoverage").numerator());
    }

    @Test
    void tagCoverageCountsPeopleWhileSourceDistributionCountsIndependentContributions() {
        String parentCode = "P" + key().replace("-", "");
        String parent = tags.create(accounts.actor("admin"), new TagService.CreateTag(parentCode, "统计分类", "", "PERSON", ""), key()).get("id").toString();
        String code = "C" + key().replace("-", "");
        String child = tags.create(accounts.actor("admin"), new TagService.CreateTag(code, "统计子标签", parent, "PERSON", ""), key()).get("id").toString();
        tags.assign(unit(), child, new TagService.AssignmentCommand(List.of("demo-person-001"), Map.of("demo-person-001", 0L), "ADD", "", 1), key());
        String assignment = TagService.assignmentId("demo-person-001", child);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var contribution = tx.createObject("TagContribution", "metrics-rule-" + key(), Map.of("source", "RULE", "sourceReference", "test-rule",
                    "state", "ACTIVE", "effectiveFrom", clock.now.toString()));
            tx.createLink("AssignmentHasContribution", "metrics-source-" + key(), new EntityKey("PersonTagAssignment", assignment), contribution.key(), Map.of());
            tx.commit();
        }
        var report = metrics.query(unit(), filter("", parent, "", "", true));
        assertEquals(16, metric(report, "effectivePeople").numerator());
        assertEquals(1, metric(report, "tagCoverage").numerator());
        assertEquals(6.25, metric(report, "tagCoverage").value());
        assertEquals(2, metric(report, "tagSourceDistribution").numerator());
        assertEquals(1, report.tags().getFirst().count());
        assertTrue(report.sources().stream().allMatch(bar -> bar.people() == 1));
    }

    @Test
    void parentTagVersionChangesInvalidateAnUnsubmittedSelectionConfirmation() {
        String root = tags.create(accounts.actor("admin"), new TagService.CreateTag("R" + key().replace("-", ""), "父类", "", "PERSON", ""), key()).get("id").toString();
        String child = tags.create(accounts.actor("admin"), new TagService.CreateTag("L" + key().replace("-", ""), "末级", root, "PERSON", ""), key()).get("id").toString();
        tags.assign(unit(), child, new TagService.AssignmentCommand(List.of("demo-person-001"), Map.of("demo-person-001", 0L), "ADD", "", 1), key());
        var saved = reminders.save(unit(), null, new ReminderService.Draft(0, "版本确认", "<p>正文</p>", "履责", "1d", null,
                new ReminderSelection.Filter(List.of(), List.of(root), "ANY", List.of(), List.of()), List.of(), ""), key());
        tags.edit(accounts.actor("admin"), root, new TagService.EditTag("父类更名", "", "ACTIVE", 1), key());
        assertThrows(BusinessConflict.class, () -> reminders.confirm(unit(), saved.get("id").toString(),
                new ReminderService.Confirmation(((Number) saved.get("version")).longValue(), saved.get("selectionDigest").toString(), 1,
                        true, saved.get("contentDigest").toString(), true, true, List.of()), key()));
    }

    @Test
    void withdrawalCanBeIncludedInHistoryWithoutRemainingInTheOverduePopulation() {
        String task = published(List.of("demo-person-001", "demo-person-009"), List.of(), "DELIVERED");
        read(task, "demo-person-001");
        var original = metrics.query(unit(), filter(task, "", "", "", true));
        String recipient = "recipient-" + BusinessCommands.hash(task + "/demo-person-001");
        withdrawals.request(unit(), task, new WithdrawalService.Request(storage.getObject(CONTEXT, "ReminderTask", task).version(), List.of(recipient), "统计口径验证"), key(), false);
        String withdrawal = text(storage.getObject(CONTEXT, "RecipientRecord", recipient), "latestWithdrawalId");
        withdrawals.recordResult("mirror", withdrawal, new ReminderChannel.WithdrawalResult("WITHDRAWN", "metric-" + key(), clock.now, ""));
        assertEquals(2, metrics.details(unit(), original.snapshotId(), "targetRecipients", "", 0, 20).total());
        clock.now = clock.now.plusSeconds(86401);
        var active = metrics.query(unit(), filter(task, "", "", "", false));
        assertEquals(1, metric(active, "targetRecipients").numerator());
        assertEquals(0.0, metric(active, "readRateAmongDelivered").value());
        assertEquals(1, metric(active, "overdueUnread").numerator(), "Overdue must not wait for a projection scheduler sweep");
        var history = metrics.query(unit(), filter(task, "", "", "", true));
        assertEquals(2, metric(history, "targetRecipients").numerator());
        assertEquals(50.0, metric(history, "readRateAmongDelivered").value());
        assertEquals(1, history.counts().get("withdrawn"));
    }

    @Test
    void missingReadingProjectionIsUnknownRatherThanZeroPercent() {
        String task = published(List.of("demo-person-001"), List.of(), "DELIVERED");
        String recipient = "recipient-" + BusinessCommands.hash(task + "/demo-person-001");
        String version = text(storage.getObject(CONTEXT, "ReminderTask", task), "currentPublishedVersionId");
        var reading = storage.getObject(CONTEXT, "RecipientVersionState", DeliveryService.readingStateId(recipient, version));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(reading.type(), reading.id(), Map.of("state", "LEGACY_UNVERIFIED"), reading.version());
            tx.commit();
        }
        var report = metrics.query(unit(), filter(task, "", "", "", true));
        assertNull(metric(report, "readRateAmongDelivered").value());
        assertEquals("UNKNOWN", metric(report, "readRateAmongDelivered").status());
        assertEquals(1, metric(report, "readRateAmongDelivered").missingInputs());
    }

    @Test
    void associationAndPendingCountsDeduplicatePeopleAndPairsWhileKeepingCategoryBreakdowns() {
        String tenant = "quality-" + key();
        String user = "quality-" + key();
        var context = RequestContext.system(tenant, "fixture");
        jdbc.update("INSERT INTO mirror_accounts(username,password_hash,display_name,tenant_id,organization_id,role_name) SELECT ?,password_hash,'质量测试',?,'quality-root','SUPER_ADMIN' FROM mirror_accounts WHERE username='admin'", user, tenant);
        try (var tx = storage.beginTransaction(context)) {
            var org = tx.createObject("Organization", "quality-root", Map.of("name", "质量测试单位", "status", "ACTIVE", "nature", "CITY"));
            for (String id : List.of("p1", "p2")) {
                var person = tx.createObject("Person", id, Map.of("name", id, "status", "ACTIVE", "identityReference", "mock:" + id));
                tx.createLink("PersonBelongsToOrganization", "org-" + id, person.key(), org.key(), Map.of());
            }
            var account = tx.createObject("UserAccount", "technical", Map.of("username", "technical", "state", "ACTIVE"));
            var eligibility = tx.createObject("ObjectEligibility", "non-object", Map.of("state", "NON_OBJECT"));
            tx.createLink("AccountCurrentOrganization", "account-org", account.key(), org.key(), Map.of());
            tx.createLink("EligibilityForAccount", "elig-account", eligibility.key(), account.key(), Map.of());
            tx.createLink("EligibilityForPerson", "elig-person", eligibility.key(), new EntityKey("Person", "p2"), Map.of());
            for (String category : List.of("IDENTITY", "PROFILE")) {
                var issue = tx.createObject("DataAssociationIssue", category, Map.of("category", category, "status", "OPEN", "detectedAt", clock.now.toString()));
                tx.createLink("IssueForPerson", category, issue.key(), new EntityKey("Person", "p1"), Map.of());
            }
            tx.createObject("TagDefinition", "tag", Map.of("name", "质量标签", "status", "ACTIVE"));
            var version = tx.createObject("TagVersion", "tag-v1", Map.of("tagDefinitionId", "tag", "status", "PUBLISHED"));
            tx.createObject("PersonTagAssignment", "assignment", Map.of("personId", "p1", "tagDefinitionId", "tag", "state", "SUPPRESSED", "manualSuppressed", true));
            for (String id : List.of("issue-one", "issue-two")) {
                var issue = tx.createObject("TagProcessingIssue", id, Map.of("category", "RULE_UNCOMPUTABLE", "state", "OPEN", "detectedAt", clock.now.toString()));
                tx.createLink("TagIssueForPerson", id, issue.key(), new EntityKey("Person", "p1"), Map.of());
                tx.createLink("TagIssueForTagVersion", id, issue.key(), version.key(), Map.of());
            }
            tx.commit();
        }
        var report = metrics.query(accounts.actor(user), BusinessMetricsService.Filter.all());
        assertEquals(1, metric(report, "effectivePeople").numerator());
        assertEquals(1, metric(report, "associationIssues").numerator());
        assertEquals(1, metric(report, "nonObjectAccounts").numerator());
        assertEquals(1, metric(report, "tagPending").numerator(), "Different categories on the same person/tag are one pending pair");
        assertEquals(2, report.pending().size());
        assertTrue(report.pending().stream().allMatch(bar -> bar.count() == 1 && bar.people() == 1));
        assertEquals(1, report.counts().get("association.IDENTITY"));
        assertEquals(1, report.counts().get("association.PROFILE"));
    }

    @Test
    void reminderTimeFiltersDoNotChangeTheCurrentPersonnelPopulation() {
        String task = published(List.of("demo-person-001"), List.of(), "DELIVERED");
        var future = new BusinessMetricsService.Filter("", "", "", "", "", "", task, clock.now.plusSeconds(1), clock.now.plusSeconds(3600), true);
        var report = metrics.query(unit(), future);
        assertEquals(16, metric(report, "effectivePeople").numerator());
        assertEquals(0, metric(report, "targetRecipients").numerator());
        assertNull(metric(report, "deliveryRate").value());
    }

    @Test
    void expiredSnapshotAndChangedPersonnelScopeCannotReturnOldDetails() {
        var report = metrics.query(unit(), BusinessMetricsService.Filter.all());
        clock.now = clock.now.plusSeconds(121);
        assertThrows(BusinessConflict.class, () -> metrics.details(unit(), report.snapshotId(), "effectivePeople", "", 0, 20));
        var fresh = metrics.query(unit(), BusinessMetricsService.Filter.all());
        var link = storage.getLinks(CONTEXT, new EntityKey("Person", "demo-person-009"), "PersonBelongsToOrganization",
                org.openfoundry.foundation.spi.StorageProvider.Direction.OUTBOUND, org.openfoundry.foundation.spi.QueryOptions.defaults()).getFirst();
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.deleteLink(link.type(), link.id(), link.version());
            tx.createLink(link.type(), "metrics-move-" + key(), link.from(), new EntityKey("Organization", "demo-b"), Map.of());
            tx.commit();
        }
        try {
            assertThrows(BusinessConflict.class, () -> metrics.details(unit(), fresh.snapshotId(), "effectivePeople", "", 0, 20));
        } finally {
            var current = storage.getLinks(CONTEXT, link.from(), link.type(), org.openfoundry.foundation.spi.StorageProvider.Direction.OUTBOUND,
                    org.openfoundry.foundation.spi.QueryOptions.defaults()).getFirst();
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.deleteLink(current.type(), current.id(), current.version());
                tx.createLink(link.type(), "metrics-restore-" + key(), link.from(), link.to(), Map.of());
                tx.commit();
            }
        }
    }
}
