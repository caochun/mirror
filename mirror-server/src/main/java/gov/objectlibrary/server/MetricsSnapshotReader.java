package gov.objectlibrary.server;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Read projection adapter for Foundry's JDBC layout. No mutations, vendor JSON functions, or domain code in Foundry. */
@Component
class MetricsSnapshotReader {
    private static final Set<String> TYPES = Set.of("Person", "Organization", "UserAccount", "PersonProfile", "ObjectEligibility",
            "DataAssociationIssue", "TagDefinition", "TagVersion", "TagRule", "PersonTagAssignment", "TagContribution", "TagCandidate",
            "TagProcessingIssue", "ReminderTask", "ReminderTaskVersion", "RecipientRecord", "RecipientVersionState",
            "ReadReceipt", "OverdueRecord", "IntegrationIssue", "DeliveryAttempt", "WithdrawalRecord");
    private static final Set<String> LINKS = Set.of("OrganizationParent", "PersonBelongsToOrganization", "ProfileForPerson",
            "AccountForPerson", "AccountCurrentOrganization", "EligibilityForPerson", "EligibilityForAccount", "IssueForPerson",
            "TagParent", "AssignmentHasContribution", "ContributionUsesVersion", "TagCandidateForPerson", "CandidateForTagVersion",
            "TagIssueForPerson", "TagIssueForTagVersion", "IssueForTag", "VersionTargetsRecipient", "TaskVersionUsesTagVersion",
            "VersionStateForRecipient", "VersionStateForVersion", "IntegrationIssueForRecipient", "IntegrationIssueForVersion");
    private static final Set<String> FIELDS = Set.of("name", "username", "status", "state", "profileStatus", "personId", "taskId",
            "tagDefinitionId", "tagVersion", "version", "nameSnapshot", "parentId", "level", "scope", "dimension", "source", "manualSuppressed",
            "effectiveFrom", "effectiveTo", "validFrom", "validTo", "category", "title", "organizationId", "createdBy", "createdAt",
            "currentPublishedVersionId", "pendingVersionId", "versionKind", "publishedAt", "categorySnapshot", "readingWindow",
            "personNameSnapshot", "organizationIdSnapshot", "organizationNameSnapshot", "deliveryState", "withdrawalState", "channelMode",
            "firstDeliveredAt", "deadlineAt", "firstReadAt", "recipientId", "taskVersionId", "readAt", "occurredAt", "detectedAt",
            "attemptedAt", "requestedAt", "completedAt", "externalEventId", "ruleId", "ruleVersionId", "currentVersionId");
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final Clock clock;

    MetricsSnapshotReader(DataSource dataSource, ObjectMapper json, Clock clock) {
        this.dataSource = dataSource;
        this.json = json;
        this.clock = clock;
    }

    Snapshot read(String tenant) {
        return read(tenant, false);
    }

    Snapshot authorityView(String tenant) {
        return read(tenant, true);
    }

    private Snapshot read(String tenant, boolean authorityOnly) {
        var facts = new HashMap<String, Map<String, Fact>>();
        var links = new ArrayList<Edge>();
        var roots = new HashMap<String, List<String>>();
        var permissions = new TreeSet<String>();
        var nativeOrganizations = new HashMap<String, String>();
        Instant asOf = clock.instant();
        Set<String> types = authorityOnly ? Set.of("Organization", "ReminderTask") : TYPES;
        Set<String> linkTypes = authorityOnly ? Set.of("OrganizationParent", "PersonBelongsToOrganization", "AccountCurrentOrganization") : LINKS;
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                String objectSql = "SELECT object_type, object_id, version, created_at, properties_json FROM of_objects WHERE tenant_id=? AND deleted_at IS NULL AND object_type IN ("
                        + placeholders(types.size()) + ")";
                try (var statement = connection.prepareStatement(objectSql)) {
                    statement.setString(1, tenant);
                    int parameter = 2;
                    for (String type : types) statement.setString(parameter++, type);
                    statement.setFetchSize(500);
                    try (var result = statement.executeQuery()) {
                        while (result.next()) {
                            String type = result.getString("object_type");
                            var properties = new HashMap<String, Object>();
                            try (JsonParser parser = json.getFactory().createParser(result.getCharacterStream("properties_json"))) {
                                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalStateException("Invalid stored properties");
                                while (parser.nextToken() != JsonToken.END_OBJECT) {
                                    String name = parser.currentName();
                                    parser.nextToken();
                                    if (FIELDS.contains(name)) properties.put(name, parser.readValueAs(Object.class));
                                    else if (name.equals("identityReference")) properties.put("mock", parser.getValueAsString("").startsWith("mock:"));
                                    else parser.skipChildren();
                                }
                            }
                            var fact = new Fact(type, result.getString("object_id"), result.getLong("version"),
                                    result.getTimestamp("created_at").toInstant(), properties);
                            facts.computeIfAbsent(type, ignored -> new HashMap<>()).put(fact.id(), fact);
                        }
                    }
                }
                String edgeSql = "SELECT link_type, from_id, to_id, version, properties_json FROM of_links WHERE tenant_id=? AND deleted_at IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) AND link_type IN ("
                        + placeholders(linkTypes.size()) + ")";
                try (var statement = connection.prepareStatement(edgeSql)) {
                    statement.setString(1, tenant);
                    statement.setTimestamp(2, Timestamp.from(asOf));
                    statement.setTimestamp(3, Timestamp.from(asOf));
                    int parameter = 4;
                    for (String type : linkTypes) statement.setString(parameter++, type);
                    statement.setFetchSize(500);
                    try (var result = statement.executeQuery()) {
                        while (result.next()) {
                            List<String> versions = null;
                            if (result.getString("link_type").equals("VersionTargetsRecipient")) {
                                var properties = json.readTree(result.getCharacterStream("properties_json"));
                                if (properties.hasNonNull("tagVersionIdsJson")) {
                                    versions = json.readValue(properties.get("tagVersionIdsJson").asText(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                                }
                            }
                            links.add(new Edge(result.getString("link_type"), result.getString("from_id"), result.getString("to_id"), result.getLong("version"), versions));
                        }
                    }
                }
                try (var statement = connection.prepareStatement("SELECT username, organization_id FROM mirror_accounts WHERE tenant_id=?")) {
                    statement.setString(1, tenant);
                    try (var result = statement.executeQuery()) {
                        while (result.next()) nativeOrganizations.put(result.getString("username"), result.getString("organization_id"));
                    }
                }
                try (var statement = connection.prepareStatement("SELECT r.username, r.organization_id FROM mirror_scope_roots r JOIN mirror_accounts a ON a.username=r.username WHERE a.tenant_id=?")) {
                    statement.setString(1, tenant);
                    try (var result = statement.executeQuery()) {
                        while (result.next()) roots.computeIfAbsent(result.getString("username"), ignored -> new ArrayList<>()).add(result.getString("organization_id"));
                    }
                }
                try (var statement = connection.prepareStatement("SELECT role_name, permission_name FROM mirror_role_permissions")) {
                    try (var result = statement.executeQuery()) {
                        while (result.next()) permissions.add(result.getString("role_name") + "/" + result.getString("permission_name"));
                    }
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read a consistent metrics snapshot", failure);
        }
        var authority = new TreeSet<String>();
        facts.getOrDefault("Organization", Map.of()).values().forEach(org -> authority.add("org/" + org.id() + "/" + org.text("status")));
        links.stream().filter(edge -> Set.of("OrganizationParent", "PersonBelongsToOrganization", "AccountCurrentOrganization").contains(edge.type()))
                .forEach(edge -> authority.add(edge.type() + "/" + edge.from() + "/" + edge.to() + "/" + edge.version()));
        nativeOrganizations.forEach((account, org) -> authority.add("account/" + account + "/" + org));
        roots.forEach((account, ids) -> ids.forEach(id -> authority.add("root/" + account + "/" + id)));
        authority.addAll(permissions);
        return new Snapshot(asOf, facts, links, nativeOrganizations, roots, BusinessCommands.hash(String.join("\n", authority)));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    record Fact(String type, String id, long version, Instant createdAt, Map<String, Object> properties) {
        String text(String name) {
            Object value = properties.get(name);
            return value == null ? "" : value.toString();
        }
        boolean flag(String name) { return Boolean.TRUE.equals(properties.get(name)); }
    }
    record Edge(String type, String from, String to, long version, List<String> tagVersions) {}
    record Snapshot(Instant asOf, Map<String, Map<String, Fact>> facts, List<Edge> edges,
                    Map<String, String> nativeAccountOrganizations, Map<String, List<String>> roots, String authority) {}
}
