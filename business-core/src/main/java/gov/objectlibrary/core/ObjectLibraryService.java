package gov.objectlibrary.core;

import org.openfoundry.foundation.spi.AuditEntry;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.OutboxEntry;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Application services for the government object library.
 *
 * The service deliberately uses only Foundry's storage SPI.  Domain rules
 * live here, while object/relationship history, audit and outbox atomicity
 * remain the responsibility of the Foundry provider.
 */
public final class ObjectLibraryService {
    private final StorageProvider storage;

    public ObjectLibraryService(StorageProvider storage) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    public ObjectRecord registerPerson(RequestContext context, String id, String name,
                                       String employeeNo, String identityStatus) {
        requireText(id, "person id");
        requireText(name, "person name");
        return write(context, "RegisterPerson", tx -> tx.createObject("Person", id,
                values("name", name, "employeeNo", employeeNo, "status", "ACTIVE",
                        "identityStatus", identityStatus == null ? "PENDING" : identityStatus)));
    }

    public ObjectRecord changePersonStatus(RequestContext context, String personId, String status) {
        ObjectRecord person = requireObject(context, "Person", personId);
        requireText(status, "status");
        return write(context, "ChangePersonStatus", tx -> tx.updateObject("Person", personId,
                Map.of("status", status), person.version()));
    }

    /** Creates the explicit account/eligibility records used for non-object accounts. */
    public ObjectRecord markNonObjectAccount(RequestContext context, String personId,
                                              String accountId, String username, String reason) {
        ObjectRecord person = requireObject(context, "Person", personId);
        requireText(accountId, "account id");
        requireText(username, "username");
        return write(context, "MarkNonObjectAccount", tx -> {
            ObjectRecord account = tx.createObject("UserAccount", accountId,
                    values("username", username, "state", "NON_OBJECT", "personId", personId,
                            "changedAt", Instant.now()));
            tx.createObject("ObjectEligibility", "eligibility-" + personId,
                    values("state", "NON_OBJECT_ACCOUNT", "reason", reason == null ? "" : reason,
                            "changedAt", Instant.now()));
            tx.createLink("AccountForPerson", "account-person-" + accountId,
                    account.key(), person.key(), Map.of("linkedAt", Instant.now()));
            tx.createLink("EligibilityForPerson", "eligibility-person-" + personId,
                    new EntityKey("ObjectEligibility", "eligibility-" + personId), person.key(),
                    Map.of("linkedAt", Instant.now()));
            return account;
        });
    }

    /**
     * Assigns an organisation while closing the previous current relationship.
     * Closing is represented by deleting the current link and creating a new
     * link, so the old endpoint and its recorded history remain queryable.
     */
    public LinkRecord assignToOrganization(RequestContext context, String personId,
                                           String organizationId, String assignmentId) {
        ObjectRecord person = requireObject(context, "Person", personId);
        ObjectRecord organization = requireObject(context, "Organization", organizationId);
        requireObject(context, "Assignment", assignmentId);
        return write(context, "AssignToOrganization", tx -> {
            for (LinkRecord link : storage.getLinks(context, person.key(), "PersonBelongsToOrganization",
                    StorageProvider.Direction.OUTBOUND, QueryOptions.defaults())) {
                if (!link.isDeleted() && link.properties().get("endedAt") == null) {
                    tx.deleteLink(link.type(), link.id(), link.version());
                }
            }
            for (LinkRecord link : storage.getLinks(context, new EntityKey("Assignment", assignmentId),
                    "AssignmentInOrganization", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults())) {
                if (!link.isDeleted() && link.properties().get("endedAt") == null) {
                    tx.deleteLink(link.type(), link.id(), link.version());
                }
            }
            Instant now = Instant.now();
            String token = UUID.randomUUID().toString();
            tx.createLink("PersonBelongsToOrganization", "person-org-" + personId + "-" + token,
                    person.key(), organization.key(), Map.of("startedAt", now));
            return tx.createLink("AssignmentInOrganization", "assignment-org-" + assignmentId + "-" + token,
                    new EntityKey("Assignment", assignmentId), organization.key(), Map.of("startedAt", now));
        });
    }

    public ObjectRecord resolveAssociationIssue(RequestContext context, String issueId,
                                                String resolution) {
        ObjectRecord issue = requireObject(context, "DataAssociationIssue", issueId);
        return write(context, "ResolveAssociationIssue", tx -> tx.updateObject(issue.type(), issue.id(),
                values("status", "RESOLVED", "resolution", resolution == null ? "" : resolution,
                "resolvedAt", Instant.now()), issue.version()));
    }

    public ObjectRecord registerExternalIdentity(RequestContext context, String identityId,
                                                 String sourceSystem, String sourceRecordId,
                                                 String personId) {
        ObjectRecord person = requireObject(context, "Person", personId);
        return write(context, "RegisterExternalIdentity", tx -> {
            ObjectRecord identity = tx.createObject("ExternalIdentity", identityId,
                    values("sourceSystem", sourceSystem, "sourceRecordId", sourceRecordId,
                            "status", "LINKED", "observedAt", Instant.now()));
            tx.createLink("ExternalIdentityForPerson", "identity-person-" + identityId,
                    identity.key(), person.key(), Map.of("linkedAt", Instant.now()));
            return identity;
        });
    }

    public ObjectRecord openAssociationIssue(RequestContext context, String issueId,
                                              String category, String reason, String personId) {
        ObjectRecord person = requireObject(context, "Person", personId);
        return write(context, "OpenAssociationIssue", tx -> {
            ObjectRecord issue = tx.createObject("DataAssociationIssue", issueId,
                    values("category", category, "reason", reason, "status", "OPEN",
                            "detectedAt", Instant.now()));
            tx.createLink("IssueForPerson", "issue-person-" + issueId,
                    issue.key(), person.key(), Map.of("linkedAt", Instant.now()));
            return issue;
        });
    }

    public LinkRecord assignPosition(RequestContext context, String personId, String positionId) {
        ObjectRecord person = requireObject(context, "Person", personId);
        ObjectRecord position = requireObject(context, "Position", positionId);
        return write(context, "AssignPosition", tx -> tx.createLink("PersonHoldsPosition",
                "person-position-" + UUID.randomUUID(), person.key(), position.key(),
                Map.of("startedAt", Instant.now())));
    }

    public LinkRecord assignPersonToAssignment(RequestContext context, String personId, String assignmentId) {
        ObjectRecord person = requireObject(context, "Person", personId);
        ObjectRecord assignment = requireObject(context, "Assignment", assignmentId);
        return write(context, "AssignPersonToAssignment", tx -> tx.createLink("PersonHasAssignment",
                "person-assignment-" + UUID.randomUUID(), person.key(), assignment.key(),
                Map.of("startedAt", Instant.now())));
    }

    public ObjectRecord publishTagVersion(RequestContext context, String versionId) {
        ObjectRecord version = requireObject(context, "TagVersion", versionId);
        return write(context, "PublishTagVersion", tx -> tx.updateObject(version.type(), version.id(),
                values("status", "PUBLISHED", "publishedAt", Instant.now()), version.version()));
    }

    public ObjectRecord assignTag(RequestContext context, String assignmentId, String personId,
                                  String tagDefinitionId, String tagVersion, String source) {
        requireObject(context, "Person", personId);
        requireObject(context, "TagDefinition", tagDefinitionId);
        requireText(source, "tag source");
        return write(context, "AssignTag", tx -> {
            Instant now = Instant.now();
            ObjectRecord assignment = tx.createObject("PersonTagAssignment", assignmentId,
                    values("state", "ACTIVE", "source", source, "tagVersion", tagVersion,
                            "effectiveFrom", now, "manualSuppressed", false));
            tx.createLink("PersonHasTag", "person-tag-" + assignmentId,
                    new EntityKey("Person", personId), assignment.key(), Map.of("linkedAt", now));
            tx.createLink("TagAssignmentUsesDefinition", "tag-definition-" + assignmentId,
                    assignment.key(), new EntityKey("TagDefinition", tagDefinitionId), Map.of());
            return assignment;
        });
    }

    public ObjectRecord removeTag(RequestContext context, String assignmentId, String note) {
        ObjectRecord assignment = requireObject(context, "PersonTagAssignment", assignmentId);
        return write(context, "RemoveTag", tx -> tx.updateObject(assignment.type(), assignment.id(),
                values("state", "REMOVED", "manualSuppressed", true, "note", note == null ? "" : note,
                "effectiveTo", Instant.now()), assignment.version()));
    }

    public ObjectRecord expireRuleTag(RequestContext context, String assignmentId, String reason) {
        ObjectRecord assignment = requireObject(context, "PersonTagAssignment", assignmentId);
        return write(context, "ExpireRuleTag", tx -> tx.updateObject(assignment.type(), assignment.id(),
                values("state", "EXPIRED", "manualSuppressed", false, "note", reason == null ? "" : reason,
                        "effectiveTo", Instant.now()), assignment.version()));
    }

    public ObjectRecord createTagBatch(RequestContext context, String batchId, String kind) {
        return write(context, "CreateTagBatch", tx -> tx.createObject("TagBatch", batchId,
                values("kind", kind, "state", "RUNNING", "startedAt", Instant.now(),
                        "successCount", 0, "failureCount", 0)));
    }

    public ObjectRecord completeTagBatch(RequestContext context, String batchId, int successCount, int failureCount) {
        ObjectRecord batch = requireObject(context, "TagBatch", batchId);
        return write(context, "CompleteTagBatch", tx -> tx.updateObject(batch.type(), batch.id(),
                values("state", failureCount == 0 ? "SUCCEEDED" : "PARTIAL_FAILED",
                        "finishedAt", Instant.now(), "successCount", successCount, "failureCount", failureCount), batch.version()));
    }

    public ObjectRecord restoreTag(RequestContext context, String assignmentId) {
        ObjectRecord assignment = requireObject(context, "PersonTagAssignment", assignmentId);
        return write(context, "RestoreTag", tx -> tx.updateObject(assignment.type(), assignment.id(),
                values("state", "ACTIVE", "manualSuppressed", false, "effectiveTo", null), assignment.version()));
    }

    public ObjectRecord reviewTagCandidate(RequestContext context, String candidateId,
                                           String decision, String assignmentId,
                                           String personId, String tagDefinitionId, String tagVersion) {
        ObjectRecord candidate = requireObject(context, "TagCandidate", candidateId);
        requireText(decision, "candidate decision");
        return write(context, "ReviewTagCandidate", tx -> {
            ObjectRecord reviewed = tx.updateObject(candidate.type(), candidate.id(),
                    values("state", decision, "reviewedAt", Instant.now()), candidate.version());
            if ("APPROVED".equals(decision)) {
                Instant now = Instant.now();
                ObjectRecord assignment = tx.createObject("PersonTagAssignment", assignmentId,
                        values("state", "ACTIVE", "source", "AI_REVIEWED", "sourceReference", candidateId,
                                "tagVersion", tagVersion, "effectiveFrom", now, "manualSuppressed", false));
                tx.createLink("PersonHasTag", "person-tag-" + assignmentId,
                        new EntityKey("Person", personId), assignment.key(), Map.of("linkedAt", now));
                tx.createLink("TagAssignmentUsesDefinition", "tag-definition-" + assignmentId,
                        assignment.key(), new EntityKey("TagDefinition", tagDefinitionId), Map.of());
            }
            return reviewed;
        });
    }

    public ObjectRecord createReminderTask(RequestContext context, String taskId, String versionId,
                                           String title, String body, List<String> personIds,
                                           String readingWindow, Instant plannedAt) {
        requireText(taskId, "task id");
        requireText(versionId, "task version id");
        if (personIds == null || personIds.isEmpty()) throw new IllegalArgumentException("recipients must not be empty");
        return write(context, "CreateReminderTask", tx -> {
            Instant now = Instant.now();
            ObjectRecord task = tx.createObject("ReminderTask", taskId,
                    values("state", "DRAFT", "sendMode", plannedAt == null ? "IMMEDIATE" : "SCHEDULED",
                            "plannedAt", plannedAt, "readingWindow", readingWindow, "createdAt", now));
            ObjectRecord version = tx.createObject("ReminderTaskVersion", versionId,
                    values("taskId", taskId, "version", "1", "state", "DRAFT", "titleSnapshot", title,
                            "bodySnapshot", body, "readingWindow", readingWindow));
            tx.createLink("TaskHasVersion", "task-version-" + versionId, task.key(), version.key(), Map.of());
            for (String personId : personIds.stream().distinct().toList()) {
                requireObject(context, "Person", personId);
                String recipientId = taskId + "-recipient-" + personId;
                ObjectRecord recipient = tx.createObject("RecipientRecord", recipientId,
                        values("taskId", taskId, "taskVersionId", versionId, "personId", personId, "state", "PENDING",
                                "readingWindow", readingWindow));
                tx.createLink("VersionTargetsRecipient", "version-recipient-" + recipientId,
                        version.key(), recipient.key(), Map.of());
            }
            return task;
        });
    }

    /** Combines organization scope, active tags and explicit people into a frozen candidate list. */
    public List<String> selectRecipients(RequestContext context, String organizationId,
                                         String tagDefinitionId, List<String> explicitPersonIds) {
        return storage.queryObjects(context, "Person", QueryOptions.defaults()).stream()
                .filter(person -> "ACTIVE".equals(person.properties().get("status")))
                .filter(person -> explicitPersonIds == null || explicitPersonIds.isEmpty()
                        || explicitPersonIds.contains(person.id()))
                .filter(person -> organizationId == null || storage.getLinks(context, person.key(),
                        "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).stream()
                        .anyMatch(link -> organizationId.equals(link.to().id())))
                .filter(person -> tagDefinitionId == null || storage.getLinks(context, person.key(),
                        "PersonHasTag", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).stream()
                        .map(link -> storage.getLinks(context, link.to(), "TagAssignmentUsesDefinition",
                                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()))
                        .flatMap(List::stream).anyMatch(link -> tagDefinitionId.equals(link.to().id())))
                .map(ObjectRecord::id).toList();
    }

    public ObjectRecord addRecipient(RequestContext context, String taskVersionId, String personId) {
        ObjectRecord version = requireObject(context, "ReminderTaskVersion", taskVersionId);
        ObjectRecord person = requireObject(context, "Person", personId);
        if ("FROZEN".equals(version.properties().get("state")) || "PUBLISHED".equals(version.properties().get("state"))) {
            throw new IllegalStateException("recipient snapshot is frozen: " + taskVersionId);
        }
        return write(context, "AddRecipient", tx -> {
            String recipientId = taskVersionId + "-recipient-" + personId;
            ObjectRecord recipient = tx.createObject("RecipientRecord", recipientId,
                    values("taskId", version.properties().get("taskId"), "taskVersionId", taskVersionId, "personId", personId, "state", "PENDING"));
            tx.createLink("VersionTargetsRecipient", "version-recipient-" + recipientId,
                    version.key(), recipient.key(), Map.of());
            return recipient;
        });
    }

    public void removeRecipient(RequestContext context, String taskVersionId, String personId) {
        ObjectRecord version = requireObject(context, "ReminderTaskVersion", taskVersionId);
        if ("FROZEN".equals(version.properties().get("state")) || "PUBLISHED".equals(version.properties().get("state"))) {
            throw new IllegalStateException("recipient snapshot is frozen: " + taskVersionId);
        }
        String recipientId = taskVersionId + "-recipient-" + personId;
        ObjectRecord recipient = requireObject(context, "RecipientRecord", recipientId);
        write(context, "RemoveRecipient", tx -> {
            for (var link : storage.getLinks(context, version.key(), "VersionTargetsRecipient",
                    StorageProvider.Direction.OUTBOUND, QueryOptions.defaults())) {
                if (link.to().id().equals(recipientId)) tx.deleteLink(link.type(), link.id(), link.version());
            }
            tx.deleteObject(recipient.type(), recipient.id(), recipient.version());
            return null;
        });
    }

    public ObjectRecord transitionTask(RequestContext context, String taskId, String state) {
        ObjectRecord task = requireObject(context, "ReminderTask", taskId);
        requireText(state, "task state");
        String current = String.valueOf(task.properties().get("state"));
        if (!validTaskTransition(current, state)) {
            throw new IllegalStateException("invalid reminder task transition: " + current + " -> " + state);
        }
        return write(context, "TransitionReminderTask", tx -> tx.updateObject(task.type(), task.id(),
                Map.of("state", state), task.version()));
    }

    public ObjectRecord expireReminderReview(RequestContext context, String taskId) {
        ObjectRecord task = requireObject(context, "ReminderTask", taskId);
        if (!"PENDING_REVIEW".equals(task.properties().get("state"))) {
            throw new IllegalStateException("task is not pending review: " + taskId);
        }
        return write(context, "ExpireReminderReview", tx -> tx.updateObject(task.type(), task.id(),
                Map.of("state", "REVIEW_EXPIRED"), task.version()));
    }

    public ObjectRecord createReminderContent(RequestContext context, String contentId,
                                              String title, String category) {
        return write(context, "CreateReminderContent", tx -> tx.createObject("ReminderContent", contentId,
                values("title", title, "category", category, "state", "ACTIVE")));
    }

    public ObjectRecord createContentVersion(RequestContext context, String contentId, String versionId,
                                             String version, String title, String body) {
        ObjectRecord content = requireObject(context, "ReminderContent", contentId);
        return write(context, "CreateContentVersion", tx -> {
            ObjectRecord value = tx.createObject("ContentVersion", versionId,
                    values("contentId", contentId, "version", version, "title", title, "body", body,
                            "state", "DRAFT", "createdAt", Instant.now()));
            tx.createLink("ContentHasVersion", "content-version-" + versionId, content.key(), value.key(), Map.of());
            return value;
        });
    }

    public LinkRecord suggestTagForContent(RequestContext context, String contentId, String tagDefinitionId) {
        ObjectRecord content = requireObject(context, "ReminderContent", contentId);
        ObjectRecord tag = requireObject(context, "TagDefinition", tagDefinitionId);
        return write(context, "SuggestTagForContent", tx -> tx.createLink("ContentSuggestsTag",
                "content-tag-" + UUID.randomUUID(), content.key(), tag.key(), Map.of()));
    }

    public ObjectRecord freezeReminderSnapshot(RequestContext context, String taskId, String versionId) {
        ObjectRecord task = requireObject(context, "ReminderTask", taskId);
        ObjectRecord version = requireObject(context, "ReminderTaskVersion", versionId);
        return write(context, "FreezeReminderSnapshot", tx -> {
            tx.updateObject("ReminderTaskVersion", version.id(), Map.of("state", "FROZEN"), version.version());
            return tx.updateObject("ReminderTask", task.id(), Map.of("state", "PENDING_REVIEW"), task.version());
        });
    }

    public ObjectRecord approveReminder(RequestContext context, String taskId) {
        return transitionTask(context, taskId, "APPROVED");
    }

    public ObjectRecord rejectReminder(ContextAndReason request) {
        ObjectRecord task = requireObject(request.context(), "ReminderTask", request.taskId());
        return write(request.context(), "RejectReminder", tx -> tx.updateObject(task.type(), task.id(),
                values("state", "REJECTED", "rejectionReason", request.reason()), task.version()));
    }

    public ObjectRecord reviseReminder(RequestContext context, String taskId, String oldVersionId,
                                       String newVersionId, String title, String body) {
        ObjectRecord task = requireObject(context, "ReminderTask", taskId);
        ObjectRecord oldVersion = requireObject(context, "ReminderTaskVersion", oldVersionId);
        return write(context, "ReviseReminder", tx -> {
            String oldNumber = String.valueOf(oldVersion.properties().getOrDefault("version", "1"));
            int number = Integer.parseInt(oldNumber) + 1;
            Instant now = Instant.now();
            ObjectRecord version = tx.createObject("ReminderTaskVersion", newVersionId,
                    values("taskId", taskId, "version", String.valueOf(number), "state", "DRAFT",
                            "titleSnapshot", title, "bodySnapshot", body,
                            "readingWindow", task.properties().get("readingWindow")));
            tx.createLink("TaskHasVersion", "task-version-" + newVersionId, task.key(), version.key(), Map.of());
            for (var recipientLink : storage.getLinks(context, oldVersion.key(), "VersionTargetsRecipient",
                    StorageProvider.Direction.OUTBOUND, QueryOptions.defaults())) {
                ObjectRecord oldRecipient = storage.getObject(context, "RecipientRecord", recipientLink.to().id());
                if (oldRecipient == null) continue;
                String personId = String.valueOf(oldRecipient.properties().get("personId"));
                String recipientId = newVersionId + "-recipient-" + personId;
                ObjectRecord recipient = tx.createObject("RecipientRecord", recipientId,
                        values("taskId", taskId, "taskVersionId", newVersionId, "personId", personId, "state", "PENDING",
                                "readingWindow", task.properties().get("readingWindow")));
                tx.createLink("VersionTargetsRecipient", "version-recipient-" + recipientId,
                        version.key(), recipient.key(), Map.of());
            }
            return tx.updateObject(task.type(), task.id(), Map.of("state", "DRAFT", "pendingVersionId", newVersionId), task.version());
        });
    }

    public int withdrawReminder(RequestContext context, String taskId, String reason) {
        ObjectRecord task = requireObject(context, "ReminderTask", taskId);
        List<String> versionIds = storage.getLinks(context, task.key(), "TaskHasVersion",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).stream().map(link -> link.to().id()).toList();
        List<ObjectRecord> recipients = storage.queryObjects(context, "RecipientRecord", QueryOptions.defaults()).stream()
                .filter(record -> versionIds.contains(String.valueOf(record.properties().get("taskVersionId")))).toList();
        write(context, "WithdrawReminder", tx -> {
            Instant now = Instant.now();
            for (ObjectRecord recipient : recipients) {
                String id = "withdrawal-" + UUID.randomUUID();
                ObjectRecord withdrawal = tx.createObject("WithdrawalRecord", id,
                        values("recipientId", recipient.id(), "state", "WITHDRAWN", "reason", reason == null ? "" : reason,
                                "requestedAt", now, "completedAt", now));
                tx.createLink("RecipientHasWithdrawal", "recipient-withdrawal-" + id,
                        recipient.key(), withdrawal.key(), Map.of());
                tx.updateObject("RecipientRecord", recipient.id(), Map.of("state", "WITHDRAWN"), recipient.version());
            }
            return tx.updateObject(task.type(), task.id(), Map.of("state", "WITHDRAWN"), task.version());
        });
        return recipients.size();
    }

    public record ContextAndReason(RequestContext context, String taskId, String reason) {}

    public ObjectRecord recordDeliveryResult(RequestContext context, String recipientId,
                                              boolean success, String externalId, String errorCode) {
        ObjectRecord recipient = requireObject(context, "RecipientRecord", recipientId);
        return write(context, "RecordDeliveryResult", tx -> {
            Instant now = Instant.now();
            String state = success ? "DELIVERED" : "DELIVERY_FAILED";
            String attemptId = recipientId + "-attempt-" + UUID.randomUUID();
            tx.createObject("DeliveryAttempt", attemptId,
                    values("recipientId", recipientId, "state", state, "attemptedAt", now,
                            "errorCode", errorCode == null ? "" : errorCode));
            tx.createLink("RecipientHasDeliveryAttempt", "recipient-attempt-" + attemptId,
                    recipient.key(), new EntityKey("DeliveryAttempt", attemptId), Map.of());
            if (success) {
                String receiptId = recipientId + "-delivery-" + UUID.randomUUID();
                tx.createObject("DeliveryReceipt", receiptId,
                        values("recipientId", recipientId, "state", "DELIVERED", "receivedAt", now,
                                "externalId", externalId == null ? "" : externalId));
                tx.createLink("RecipientHasDeliveryReceipt", "recipient-receipt-" + receiptId,
                        recipient.key(), new EntityKey("DeliveryReceipt", receiptId), Map.of());
            }
            Map<String, Object> recipientValues = new HashMap<>();
            recipientValues.put("state", success ? "UNREAD" : state);
            if (success) {
                recipientValues.put("sentAt", now);
                recipientValues.put("deadlineAt", deadline(now, String.valueOf(
                        recipient.properties().getOrDefault("readingWindow", "1d"))));
            }
            return tx.updateObject(recipient.type(), recipient.id(), recipientValues, recipient.version());
        });
    }

    public ObjectRecord recordReadReceipt(RequestContext context, String recipientId,
                                          String taskVersionId) {
        ObjectRecord recipient = requireObject(context, "RecipientRecord", recipientId);
        boolean alreadyRead = storage.queryObjects(context, "ReadReceipt", QueryOptions.defaults()).stream()
                .anyMatch(receipt -> recipientId.equals(receipt.properties().get("recipientId"))
                        && taskVersionId.equals(receipt.properties().get("taskVersionId")));
        if (alreadyRead) return recipient;
        if (!"DELIVERED".equals(recipient.properties().get("state"))
                && !"OVERDUE".equals(recipient.properties().get("state"))
                && !"UNREAD".equals(recipient.properties().get("state"))) {
            throw new IllegalStateException("recipient has not been delivered: " + recipientId);
        }
        return write(context, "RecordReadReceipt", tx -> {
            Instant now = Instant.now();
            String receiptId = recipientId + "-read-" + UUID.randomUUID();
            tx.createObject("ReadReceipt", receiptId,
                    values("recipientId", recipientId, "taskVersionId", taskVersionId, "readAt", now));
            tx.createLink("RecipientHasReadReceipt", "recipient-read-" + receiptId,
                    recipient.key(), new EntityKey("ReadReceipt", receiptId), Map.of());
            return tx.updateObject(recipient.type(), recipient.id(), Map.of("state", "READ"), recipient.version());
        });
    }

    /** Creates one overdue record per recipient after its frozen deadline. */
    public int scanOverdue(RequestContext context, Instant now) {
        int created = 0;
        for (ObjectRecord recipient : storage.queryObjects(context, "RecipientRecord", QueryOptions.defaults())) {
            Object deadline = recipient.properties().get("deadlineAt");
            if (deadline == null || !"UNREAD".equals(recipient.properties().get("state"))) continue;
            Instant deadlineAt = Instant.parse(deadline.toString());
            if (deadlineAt.isAfter(now)) continue;
            String overdueId = "overdue-" + recipient.id();
            if (storage.getObject(context, "OverdueRecord", overdueId) != null) continue;
            write(context, "CreateOverdueRecord", tx -> {
                ObjectRecord overdue = tx.createObject("OverdueRecord", overdueId,
                        values("recipientId", recipient.id(), "state", "OPEN", "createdAt", now));
                tx.createLink("RecipientHasOverdueRecord", "recipient-overdue-" + recipient.id(),
                        recipient.key(), overdue.key(), Map.of());
                return tx.updateObject(recipient.type(), recipient.id(), Map.of("state", "OVERDUE"), recipient.version());
            });
            created++;
        }
        return created;
    }

    public ObjectRecord resolveOverdue(RequestContext context, String overdueId) {
        ObjectRecord overdue = requireObject(context, "OverdueRecord", overdueId);
        return write(context, "ResolveOverdueRecord", tx -> tx.updateObject(overdue.type(), overdue.id(),
                values("state", "RESOLVED", "resolvedAt", Instant.now()), overdue.version()));
    }

    private ObjectRecord requireObject(RequestContext context, String type, String id) {
        ObjectRecord object = storage.getObject(context, type, id);
        if (object == null || object.isDeleted()) throw new IllegalArgumentException(type + " not found: " + id);
        return object;
    }

    private <T> T write(RequestContext context, String operation,
                        BiFunction<Transaction, List<EntityKey>, T> operationBody) {
        List<EntityKey> affected = new ArrayList<>();
        try (Transaction tx = storage.beginTransaction(context)) {
            T result = operationBody.apply(new TrackingTransaction(tx, affected), affected);
            Instant now = Instant.now();
            Map<String, Object> detail = Map.of("operation", operation, "affected", affected.stream()
                    .map(key -> key.type() + "/" + key.id()).toList());
            tx.appendAudit(new AuditEntry("audit-" + UUID.randomUUID(), now, context.tenantId(),
                    context.actorId(), "business", null, null, operation, tx.transactionId(), "success", detail));
            tx.enqueueOutbox(new OutboxEntry("event-" + UUID.randomUUID(), context.tenantId(),
                    "gov.object-library." + operation, operation, now, tx.transactionId(), detail));
            tx.commit();
            return result;
        }
    }

    private <T> T write(RequestContext context, String operation, Function<Transaction, T> operationBody) {
        return write(context, operation, (tx, ignored) -> operationBody.apply(tx));
    }

    private static Instant deadline(Instant sentAt, String window) {
        String normalized = window == null ? "1d" : window.toLowerCase();
        if (normalized.endsWith("h")) return sentAt.plus(Long.parseLong(normalized.substring(0, normalized.length() - 1)), ChronoUnit.HOURS);
        if (normalized.endsWith("w")) return sentAt.plus(Long.parseLong(normalized.substring(0, normalized.length() - 1)), ChronoUnit.WEEKS);
        return sentAt.plus(Long.parseLong(normalized.replace("d", "")), ChronoUnit.DAYS);
    }

    private static boolean validTaskTransition(String from, String to) {
        return switch (from) {
            case "DRAFT" -> "PENDING_REVIEW".equals(to) || "SENDING".equals(to);
            case "PENDING_REVIEW" -> "APPROVED".equals(to) || "REJECTED".equals(to) || "REVIEW_EXPIRED".equals(to);
            case "REJECTED" -> "DRAFT".equals(to);
            case "APPROVED" -> "SENDING".equals(to) || "CANCELLED".equals(to);
            case "SENDING" -> "ALL_SUCCESS".equals(to) || "PARTIAL_FAILED".equals(to)
                    || "ALL_FAILED".equals(to) || "WITHDRAWN".equals(to);
            default -> false;
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static Map<String, Object> values(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) if (values[i + 1] != null) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    /** Records every object/link returned by a transaction operation for audit detail. */
    private static final class TrackingTransaction implements Transaction {
        private final Transaction delegate;
        private final List<EntityKey> affected;
        private TrackingTransaction(Transaction delegate, List<EntityKey> affected) { this.delegate = delegate; this.affected = affected; }
        public String transactionId() { return delegate.transactionId(); }
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties) { ObjectRecord value = delegate.createObject(type, id, properties); affected.add(value.key()); return value; }
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties, long version) { ObjectRecord value = delegate.updateObject(type, id, properties, version); affected.add(value.key()); return value; }
        public void deleteObject(String type, String id, long version) { delegate.deleteObject(type, id, version); affected.add(new EntityKey(type, id)); }
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to, Map<String, Object> properties) { LinkRecord value = delegate.createLink(type, id, from, to, properties); affected.add(new EntityKey(type, id)); return value; }
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties, long version) { LinkRecord value = delegate.updateLink(type, id, properties, version); affected.add(new EntityKey(type, id)); return value; }
        public void deleteLink(String type, String id, long version) { delegate.deleteLink(type, id, version); affected.add(new EntityKey(type, id)); }
        public void appendAudit(AuditEntry audit) { delegate.appendAudit(audit); }
        public void enqueueOutbox(OutboxEntry event) { delegate.enqueueOutbox(event); }
        public void commit() { delegate.commit(); }
        public void rollback() { delegate.rollback(); }
    }
}
