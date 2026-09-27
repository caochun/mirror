package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static gov.objectlibrary.server.DeliveryService.text;
import static gov.objectlibrary.server.DeliveryService.values;

/** Reading is a recipient fact, independent of delivery. Overdue projections retain their original supervisor. */
@Service
class ReadingService {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final BusinessCommands commands;
    private final DomainContracts contracts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReadingService(StorageProvider storage, DirectoryService directory, BusinessCommands commands,
                   DomainContracts contracts, JdbcTemplate jdbc, Clock clock) {
        this.storage = storage;
        this.directory = directory;
        this.commands = commands;
        this.contracts = contracts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Map<String, Object> recordFirstRead(Accounts.Actor actor, String recipientId, String versionId,
                                        String evidenceReference, Runnable authorize) {
        String id = "read-" + BusinessCommands.hash(recipientId + "/" + versionId);
        return commands.executeDefinedWithRetry(actor, "RecordFirstRead", id, List.of(recipientId, versionId), authorize, tx -> {
            contracts.authorizeRecipient("RecordFirstRead");
            contracts.validateInputs(actor, "RecordFirstRead", Map.of("recipientId", recipientId, "versionId", versionId,
                    "eventKey", id, "bodyRendered", true));
            var recipient = required(actor, "RecipientRecord", recipientId);
            var previous = storage.getObject(actor.context(), "ReadReceipt", id);
            if (previous != null) return Map.of("state", "READ", "firstReadAt", text(previous, "readAt"));
            String now = clock.instant().toString();
            var receipt = tx.createObject("ReadReceipt", id, values("recipientId", recipientId, "taskVersionId", versionId,
                    "readAt", now, "recordedAt", now, "eventKey", id, "identityEvidenceReference", evidenceReference));
            tx.createLink("RecipientHasReadReceipt", "recipient-" + id, recipient.key(), receipt.key(), Map.of());
            tx.createLink("ReadReceiptForVersion", "version-" + id, receipt.key(), new EntityKey("ReminderTaskVersion", versionId), Map.of());
            String stateId = DeliveryService.readingStateId(recipientId, versionId);
            var state = storage.getObject(actor.context(), "RecipientVersionState", stateId);
            if (state == null) {
                var version = required(actor, "ReminderTaskVersion", versionId);
                state = tx.createObject("RecipientVersionState", stateId, values("state", "READ", "firstReadAt", now,
                        "publishedAt", text(version, "publishedAt"), "updatedAt", now));
                tx.createLink("VersionStateForRecipient", "recipient-" + stateId, state.key(), recipient.key(), Map.of());
                tx.createLink("VersionStateForVersion", "version-" + stateId, state.key(), version.key(), Map.of());
            } else {
                contracts.requireTransition(state.type(), "state", text(state, "state"), "READ", "RecordFirstRead");
                tx.updateObject(state.type(), state.id(), values("state", "READ", "firstReadAt", now, "updatedAt", now), state.version());
            }
            var overdue = storage.getObject(actor.context(), "OverdueRecord", overdueId(recipientId, versionId));
            if (overdue != null && text(overdue, "state").equals("OPEN")) {
                contracts.requireTransition(overdue.type(), "state", "OPEN", "RESOLVED", "RecordFirstRead");
                tx.updateObject(overdue.type(), overdue.id(), values("state", "RESOLVED", "resolvedAt", now), overdue.version());
            }
            if (!text(recipient, "deliveryState").equals("DELIVERED")) {
                addIssue(tx, actor, recipientId, versionId, "READ_WITHOUT_DELIVERY", id, "", now);
            }
            return Map.of("state", "READ", "firstReadAt", now);
        }, contracts.eventType("RecordFirstRead"));
    }

    Map<String, Object> reportIssue(Accounts.Actor actor, String recipientId, String versionId,
                                   String category, String eventId, String mediaId, Runnable authorize) {
        String id = "issue-" + BusinessCommands.hash(eventId);
        return commands.executeDefinedWithRetry(actor, "RecordIntegrationIssue", id,
                List.of(recipientId, versionId, category, eventId, mediaId), authorize, tx -> {
            contracts.authorizeSystem("RecordIntegrationIssue");
            contracts.validateInputs(actor, "RecordIntegrationIssue", values("recipientId", recipientId, "versionId", versionId,
                    "category", category, "eventId", eventId, "occurredAt", clock.instant().toString(),
                    "mediaId", mediaId.isEmpty() ? null : mediaId));
            addIssue(tx, actor, recipientId, versionId, category, eventId, mediaId, clock.instant().toString());
            return Map.of("recorded", true);
        }, contracts.eventType("RecordIntegrationIssue"));
    }

    private void addIssue(Transaction tx, Accounts.Actor actor, String recipientId, String versionId,
                          String category, String eventId, String mediaId, String now) {
        String id = "integration-" + BusinessCommands.hash(category + "/" + eventId);
        if (storage.getObject(actor.context(), "IntegrationIssue", id) != null) return;
        var issue = tx.createObject("IntegrationIssue", id, values("category", category, "state", "OPEN", "reason", category,
                "externalEventId", eventId, "occurredAt", now));
        tx.createLink("IntegrationIssueForRecipient", "recipient-" + id, issue.key(), new EntityKey("RecipientRecord", recipientId), Map.of());
        tx.createLink("IntegrationIssueForVersion", "version-" + id, issue.key(), new EntityKey("ReminderTaskVersion", versionId), Map.of());
        if (!mediaId.isEmpty()) {
            tx.createLink("IntegrationIssueForMedia", "media-" + id, issue.key(), new EntityKey("MediaAsset", mediaId), Map.of());
        }
    }

    int evaluateDue() {
        int count = 0;
        for (String tenant : jdbc.queryForList("SELECT DISTINCT tenant_id FROM mirror_accounts", String.class)) {
            var actor = new Accounts.Actor("__overdue_worker__", "逾期评估", tenant, "", "SYSTEM");
            for (var recipient : directory.all(actor, "RecipientRecord")) {
                String versionId = dueVersion(actor, recipient);
                if (versionId.isEmpty()) continue;
                String id = overdueId(recipient.id(), versionId);
                if (storage.getObject(actor.context(), "OverdueRecord", id) != null) continue;
                try {
                    var result = commands.executeDefinedWithRetry(actor, "EvaluateOverdue", id, List.of(recipient.id(), versionId),
                            () -> contracts.authorizeSystem("EvaluateOverdue"), tx -> {
                        var current = required(actor, "RecipientRecord", recipient.id());
                        if (!versionId.equals(dueVersion(actor, current))) return Map.of("created", false);
                        contracts.validateInputs(actor, "EvaluateOverdue", Map.of("recipientId", recipient.id(),
                                "versionId", versionId, "evaluatedAt", clock.instant().toString()));
                        String organization = currentOrganization(actor, text(current, "personId"));
                        var warning = tx.createObject("OverdueRecord", id, values("recipientId", current.id(), "taskVersionId", versionId,
                                "state", "OPEN", "createdAt", clock.instant().toString(), "supervisorOrganizationId", organization));
                        tx.createLink("RecipientHasOverdueRecord", "recipient-" + id, current.key(), warning.key(), Map.of());
                        tx.createLink("OverdueForVersion", "version-" + id, warning.key(), new EntityKey("ReminderTaskVersion", versionId), Map.of());
                        if (!organization.isEmpty()) {
                            tx.createLink("OverdueSupervisedBy", "organization-" + id, warning.key(), new EntityKey("Organization", organization), Map.of());
                        }
                        return Map.of("created", true, "id", id);
                    }, contracts.eventType("EvaluateOverdue"));
                    if (Boolean.TRUE.equals(result.get("created"))) count++;
                } catch (BusinessConflict concurrentChange) {
                    // Reading or another evaluator won the transaction. Recheck next scan.
                }
            }
        }
        return count;
    }

    Map<String, Object> list(Accounts.Actor actor, String state, String query, int page, int size) {
        if (!List.of("ALL", "OVERDUE", "UNREAD").contains(state) || page < 0 || size < 1 || size > 100
                || query == null || query.length() > 100) throw new IllegalArgumentException("Invalid reading filter");
        return commands.executeDefinedWithRetry(actor, "ReadOverdueList", "reading-list-" + UUID.randomUUID(),
                List.of(state, query, page, size), () -> contracts.authorize(actor, "ReadOverdueList"), tx -> {
            contracts.validateInputs(actor, "ReadOverdueList", Map.of("filter", Map.of("state", state, "query", query), "page", page, "size", size));
            var scope = directory.scope(actor);
            List<UnreadView> rows = new ArrayList<>();
            for (var recipient : directory.all(actor, "RecipientRecord")) {
                if (!text(recipient, "deliveryState").equals("DELIVERED") || text(recipient, "withdrawalState").equals("WITHDRAWN")
                        || text(recipient, "firstDeliveredAt").isEmpty()) continue;
                String personId = text(recipient, "personId");
                String organizationId = currentOrganization(actor, personId);
                if (!actor.role().equals("SUPER_ADMIN") && !scope.contains(organizationId)) continue;
                var task = storage.getObject(actor.context(), "ReminderTask", text(recipient, "taskId"));
                if (task == null || task.isDeleted()) continue;
                String version = text(task, "currentPublishedVersionId");
                if (version.isEmpty() || isRead(actor, recipient.id(), version) || text(recipient, "deadlineAt").isEmpty()) continue;
                var published = storage.getObject(actor.context(), "ReminderTaskVersion", version);
                if (published == null || !text(published, "state").equals("PUBLISHED")
                        || directory.links(actor, published.key(), "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND)
                        .stream().noneMatch(link -> link.to().equals(recipient.key()))) continue;
                boolean overdue = !Instant.parse(text(recipient, "deadlineAt")).isAfter(clock.instant());
                if (state.equals("OVERDUE") && !overdue || state.equals("UNREAD") && overdue) continue;
                var person = required(actor, "Person", personId);
                var organization = organizationId.isEmpty() ? null : storage.getObject(actor.context(), "Organization", organizationId);
                String name = text(person, "name");
                String organizationName = organization == null ? "当前单位待核实" : text(organization, "name");
                if (!query.isEmpty() && !name.contains(query) && !organizationName.contains(query)) continue;
                rows.add(new UnreadView(recipient.id(), name, organizationName, text(task, "title"),
                        text(recipient, "firstDeliveredAt"), text(recipient, "deadlineAt"), overdue ? "OVERDUE" : "UNREAD",
                        text(person, "phoneReference").isEmpty() ? "无联系方式" : "联系方式受保护", text(recipient, "channelMode"), personId));
            }
            rows.sort(Comparator.comparing(UnreadView::deadlineAt).thenComparing(UnreadView::id));
            var people = new HashSet<String>();
            rows.forEach(row -> people.add(row.personId()));
            int start = (int) Math.min((long) page * size, rows.size());
            return values("items", rows.subList(start, Math.min(start + size, rows.size())), "total", rows.size(),
                    "people", people.size(), "page", page, "size", size, "asOf", clock.instant().toString());
        }, contracts.eventType("ReadOverdueList"));
    }

    private String dueVersion(Accounts.Actor actor, ObjectRecord recipient) {
        if (!text(recipient, "deliveryState").equals("DELIVERED") || text(recipient, "withdrawalState").equals("WITHDRAWN")
                || text(recipient, "firstDeliveredAt").isEmpty() || text(recipient, "deadlineAt").isEmpty()
                || Instant.parse(text(recipient, "deadlineAt")).isAfter(clock.instant())) return "";
        var task = storage.getObject(actor.context(), "ReminderTask", text(recipient, "taskId"));
        if (task == null || task.isDeleted()) return "";
        String versionId = text(task, "currentPublishedVersionId");
        if (versionId.isEmpty() || isRead(actor, recipient.id(), versionId)) return "";
        var version = storage.getObject(actor.context(), "ReminderTaskVersion", versionId);
        if (version == null || !text(version, "state").equals("PUBLISHED")) return "";
        boolean member = directory.links(actor, version.key(), "VersionTargetsRecipient", StorageProvider.Direction.OUTBOUND)
                .stream().anyMatch(link -> link.to().equals(recipient.key()));
        return member ? versionId : "";
    }

    private boolean isRead(Accounts.Actor actor, String recipient, String version) {
        var reading = storage.getObject(actor.context(), "RecipientVersionState", DeliveryService.readingStateId(recipient, version));
        return reading != null && text(reading, "state").equals("READ");
    }

    private String currentOrganization(Accounts.Actor actor, String person) {
        var links = directory.links(actor, new EntityKey("Person", person), "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND);
        return links.size() == 1 ? links.getFirst().to().id() : "";
    }

    private ObjectRecord required(Accounts.Actor actor, String type, String id) {
        var record = storage.getObject(actor.context(), type, id);
        if (record == null || record.isDeleted()) throw new BusinessConflict("业务记录不可用");
        return record;
    }

    static String overdueId(String recipient, String version) {
        return "overdue-" + BusinessCommands.hash(recipient + "/" + version);
    }

    record UnreadView(String id, String name, String organization, String title, String firstDeliveredAt,
                      String deadlineAt, String state, String contact, String channelMode, String personId) {}
}
