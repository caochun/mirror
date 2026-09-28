package gov.mirror.explorer;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import java.util.List;
import java.util.Map;

/** Synthetic examples only. Data is discarded when the preview process stops. */
final class DemoData {
    private static final String TIME = "2026-01-01T00:00:00Z";
    static void populate(InMemoryStorageProvider storage) {
        try (var tx = storage.beginTransaction(PreviewServer.CONTEXT)) {
            tx.createObject("Person", "p", Map.of("name", "演示人员 · 林青", "nationalIdRef", "PRIVATE-DEMO"));
            for (String org : List.of("org", "org2")) tx.createObject("Organization", org, Map.of("name", org.equals("org") ? "市级示范单位" : "开发区示范单位", "sourceStatus", "ACTIVE"));
            tx.createLink("PersonCurrentOrganization", "person-org", key("Person", "p"), key("Organization", "org"), Map.of());
            tx.createObject("ObjectMembership", "membership", Map.of("status", "IN_SCOPE", "decisionCode", "INITIAL", "decidedBy", "source", "decidedAt", TIME));
            tx.createLink("MembershipPerson", "membership-person", key("ObjectMembership", "membership"), key("Person", "p"), Map.of());
            tx.createLink("MembershipDecisionOrganization", "membership-org", key("ObjectMembership", "membership"), key("Organization", "org2"), Map.of());
            tx.createObject("Tag", "tag", Map.of("status", "ENABLED"));
            tx.createObject("TagVersion", "tag-v1", Map.of("versionKey", "tag:1", "revision", 1, "name", "工程建设领域", "dimension", "PERSON", "duration", "LONG_TERM", "publishedAt", TIME, "publishedBy", "publisher"));
            tx.createLink("TagVersionOf", "tv", key("TagVersion", "tag-v1"), key("Tag", "tag"), Map.of());
            tx.createLink("TagCurrentVersion", "tc", key("Tag", "tag"), key("TagVersion", "tag-v1"), Map.of());
            tx.createObject("PersonTag", "pt", Map.of("pairKey", "p:tag", "suppression", "NONE"));
            tx.createLink("PersonTagPerson", "ptp", key("PersonTag", "pt"), key("Person", "p"), Map.of());
            tx.createLink("PersonTagTag", "ptt", key("PersonTag", "pt"), key("Tag", "tag"), Map.of());
            tx.createObject("SupervisionMatter", "matter", Map.of("name", "示范工程项目", "category", "PROJECT", "status", "ACTIVE"));
            tx.createObject("MatterStage", "stage", Map.of("name", "招标阶段", "kind", "DURING", "status", "ACTIVE"));
            tx.createLink("StageMatter", "sm", key("MatterStage", "stage"), key("SupervisionMatter", "matter"), Map.of());
            tx.createObject("ReminderTask", "task", Map.of("lifecycle", "OPEN", "createdBy", "author", "createdAt", TIME));
            tx.createLink("TaskCreatedIn", "task-org", key("ReminderTask", "task"), key("Organization", "org"), Map.of());
            tx.createObject("AudienceSnapshot", "audience", Map.of("snapshotKey", "audience", "selection", Map.of(), "selectionHash", "selection", "sendMode", "IMMEDIATE", "deadlineHours", 24, "confirmedBy", "author", "confirmedAt", TIME, "capturedAt", TIME));
            tx.createLink("AudienceTask", "audience-task", key("AudienceSnapshot", "audience"), key("ReminderTask", "task"), Map.of());
            for (String version : List.of("published", "revision")) {
                tx.createObject("ReminderVersion", version, Map.of("versionKey", version, "revision", version.equals("published") ? 1 : 2, "state", "FROZEN", "title", version.equals("published") ? "招标纪律提醒" : "招标纪律提醒（修订待审）", "body", Map.of(), "category", "GENERAL", "contentHash", "hash"));
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
            tx.createObject("Person", "p2", Map.of("name", "演示人员 · 陈明", "personalRankCode", "SOURCE_RANK_A"));
            tx.createObject("Position", "position", Map.of("name", "项目经办岗位", "sourceStatus", "ACTIVE", "standardCategory", "PROJECT"));
            tx.createObject("Appointment", "appointment", Map.of("status", "CURRENT", "startedOn", "2026-01-01", "appointmentRoleCode", "HANDLER"));
            tx.createLink("AppointmentPerson", "ap", key("Appointment", "appointment"), key("Person", "p"), Map.of());
            tx.createLink("AppointmentOrganization", "ao", key("Appointment", "appointment"), key("Organization", "org"), Map.of());
            tx.createLink("AppointmentPosition", "aj", key("Appointment", "appointment"), key("Position", "position"), Map.of());
            tx.createLink("MatterOrganization", "mo", key("SupervisionMatter", "matter"), key("Organization", "org"), Map.of());
            tx.createLink("TaskApprovedAudience", "approved-audience", key("ReminderTask", "task"), key("AudienceSnapshot", "audience"), Map.of());
            tx.createObject("RecipientSnapshot", "snapshot", Map.of("snapshotKey", "audience:recipient", "personName", "演示人员 · 林青",
                    "organizationName", "市级示范单位", "selectionBasis", Map.of("schemaVersion", 1, "origins", List.of("SPECIFIED")), "capturedAt", TIME));
            tx.createLink("SnapshotAudience", "sa", key("RecipientSnapshot", "snapshot"), key("AudienceSnapshot", "audience"), Map.of());
            tx.createLink("SnapshotRecipient", "sr", key("RecipientSnapshot", "snapshot"), key("TaskRecipient", "recipient"), Map.of());
            tx.createLink("SnapshotOrganization", "so", key("RecipientSnapshot", "snapshot"), key("Organization", "org"), Map.of());
            tx.createObject("TagContribution", "rule-evidence", Map.of("contributionKey", "rule-evidence", "kind", "RULE", "status", "ACTIVE", "effectiveFrom", TIME, "reason", "合成岗位规则依据"));
            tx.createLink("ContributionForPersonTag", "cp", key("TagContribution", "rule-evidence"), key("PersonTag", "pt"), Map.of());
            tx.createLink("ContributionTagVersion", "cv", key("TagContribution", "rule-evidence"), key("TagVersion", "tag-v1"), Map.of());
            tx.createLink("ContributionOrganization", "co", key("TagContribution", "rule-evidence"), key("Organization", "org"), Map.of());
            tx.commit();
        }
    }
    private static EntityKey key(String type, String id) { return new EntityKey(type, id); }
}
