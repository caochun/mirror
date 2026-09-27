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
    RuleInputStamp(DataSource dataSource) { this.dataSource = dataSource; }

    String read(String tenant, boolean includeEffects) {
        try (var connection = dataSource.getConnection()) {
            var digest = MessageDigest.getInstance("SHA-256");
            String types = "'Person','Organization','Position','Assignment','PersonProfile','ObjectEligibility','ClassificationMapping','TagDefinition'"
                    + (includeEffects ? ",'PersonTagAssignment','TagContribution','TagRule'" : "");
            try (var query = connection.prepareStatement("SELECT object_type,object_id,version,deleted_at FROM of_objects WHERE tenant_id=? AND object_type IN (" + types + ") ORDER BY object_type,object_id")) {
                query.setString(1, tenant);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) digest.update((rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getLong(3) + "/" + rows.getString(4) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            try (var query = connection.prepareStatement("""
                    SELECT link_type,link_id,version,deleted_at FROM of_links WHERE tenant_id=? AND link_type IN
                    ('PersonBelongsToOrganization','OrganizationParent','ProfileForPerson','EligibilityForPerson','PersonHasAssignment',
                     'AssignmentInOrganization','AssignmentUsesPosition','MappingForOrganization','MappingForTag') ORDER BY link_type,link_id
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
