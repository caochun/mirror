package gov.objectlibrary.server;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Cheap invalidation stamp. Preview publication never silently applies an impact analysis over changed inputs. */
@Component
class RuleInputStamp {
    private final DataSource dataSource;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final java.time.Clock clock;

    RuleInputStamp(DataSource dataSource, com.fasterxml.jackson.databind.ObjectMapper json, java.time.Clock clock) {
        this.dataSource = dataSource;
        this.json = json;
        this.clock = clock;
    }

    String read(String tenant, boolean includeEffects) {
        try (var connection = dataSource.getConnection()) {
            var digest = MessageDigest.getInstance("SHA-256");
            String types = "'Person','Organization','Position','Assignment','PersonProfile','ObjectEligibility','ClassificationMapping','TagDefinition'"
                    + (includeEffects ? ",'PersonTagAssignment','TagContribution','TagRule'" : "");
            try (var query = connection.prepareStatement("SELECT object_type,object_id,version,deleted_at,CASE WHEN object_type='Assignment' THEN properties_json ELSE NULL END AS assignment_properties FROM of_objects WHERE tenant_id=? AND object_type IN (" + types + ") ORDER BY object_type,object_id")) {
                query.setString(1, tenant);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        String temporal = "";
                        if ("Assignment".equals(rows.getString(1))) {
                            try {
                                var fields = json.readTree(rows.getString("assignment_properties"));
                                String start = fields.path("startedAt").asText("");
                                String end = fields.path("endedAt").asText("");
                                temporal = Boolean.toString((start.isEmpty() || !java.time.Instant.parse(start).isAfter(clock.instant()))
                                        && (end.isEmpty() || java.time.Instant.parse(end).isAfter(clock.instant())));
                            } catch (Exception invalid) { temporal = "INVALID"; }
                        }
                        digest.update((rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getLong(3) + "/" + rows.getString(4) + "/" + temporal + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            try (var query = connection.prepareStatement("""
                    SELECT link_type,link_id,version,deleted_at FROM of_links WHERE tenant_id=? AND link_type IN
                    ('PersonBelongsToOrganization','OrganizationParent','ProfileForPerson','EligibilityForPerson','PersonHasAssignment',
                     'AssignmentInOrganization','AssignmentUsesPosition','MappingForOrganization','MappingForTag','AssignmentHasContribution','PersonHasTag') ORDER BY link_type,link_id
                    """)) {
                query.setString(1, tenant);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) digest.update((rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getLong(3) + "/" + rows.getString(4) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw new IllegalStateException("Cannot read rule source stamp", failure); }
    }
}
