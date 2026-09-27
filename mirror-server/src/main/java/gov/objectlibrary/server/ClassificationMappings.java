package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static gov.objectlibrary.server.DeliveryService.text;

/** The same immutable mapping view is used by simulation and rule evaluation. */
@Component
class ClassificationMappings {
    static final Map<String, String> FIELDS = Map.of("ORGANIZATION_NATURE", "organizationNature",
            "ROLE_LEVEL", "roleLevel", "POSITION_DOMAIN", "positionDomain");
    private final DirectoryService directory;

    ClassificationMappings(DirectoryService directory) {
        this.directory = directory;
    }

    Snapshot load(Accounts.Actor actor) {
        Map<String, ObjectRecord> tags = new HashMap<>();
        directory.all(actor, "TagDefinition").forEach(tag -> tags.put(tag.id(), tag));
        List<Entry> entries = new ArrayList<>();
        for (var mapping : directory.all(actor, "ClassificationMapping")) {
            var targets = directory.links(actor, mapping.key(), "MappingForTag", StorageProvider.Direction.OUTBOUND);
            String targetId = targets.size() == 1 ? targets.getFirst().to().id() : "";
            var tag = tags.get(targetId);
            var roots = directory.links(actor, mapping.key(), "MappingForOrganization", StorageProvider.Direction.OUTBOUND)
                    .stream().map(link -> link.to().id()).distinct().sorted().toList();
            entries.add(new Entry(mapping.id(), mapping.version(), text(mapping, "kind"), text(mapping, "sourceCode"), targetId,
                    tag == null ? "" : text(tag, "currentVersionId"), tag == null ? "" : text(tag, "code"),
                    tag != null && "ACTIVE".equals(text(tag, "status")), roots,
                    ((Number) mapping.properties().getOrDefault("priority", 0)).intValue(),
                    Boolean.TRUE.equals(mapping.properties().get("inherited")), "ACTIVE".equals(text(mapping, "state"))));
        }
        Map<String, List<String>> parents = new HashMap<>();
        Set<String> activeOrganizations = new HashSet<>();
        for (var org : directory.all(actor, "Organization")) {
            parents.put(org.id(), directory.links(actor, org.key(), "OrganizationParent", StorageProvider.Direction.OUTBOUND)
                    .stream().map(link -> link.to().id()).toList());
            if ("ACTIVE".equals(text(org, "status"))) activeOrganizations.add(org.id());
        }
        return new Snapshot(List.copyOf(entries), Map.copyOf(parents), Set.copyOf(activeOrganizations));
    }

    record Entry(String id, long version, String kind, String sourceCode, String targetTagId, String targetTagVersionId,
                 String targetCode, boolean targetActive, List<String> organizationIds, int priority, boolean inherit, boolean enabled) {}

    record Snapshot(List<Entry> entries, Map<String, List<String>> parents, Set<String> activeOrganizations) {
        Snapshot replacing(Entry replacement) {
            var proposed = new ArrayList<>(entries.stream().filter(entry -> !entry.id().equals(replacement.id())).toList());
            proposed.add(replacement);
            return new Snapshot(List.copyOf(proposed), parents, activeOrganizations);
        }

        List<String> lineage(String organization) {
            List<String> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String current = organization;
            while (!current.isEmpty()) {
                if (!seen.add(current) || !activeOrganizations.contains(current)) return List.of();
                result.add(current);
                var links = parents.getOrDefault(current, List.of());
                if (links.size() > 1) return List.of();
                current = links.isEmpty() ? "" : links.getFirst();
            }
            return result;
        }

        boolean applies(Entry entry, String organization) {
            var lineage = lineage(organization);
            if (lineage.isEmpty()) return false;
            if (entry.organizationIds().isEmpty()) return !entry.kind().equals("ORGANIZATION_NATURE");
            return entry.organizationIds().contains(organization)
                    || entry.inherit() && lineage.stream().anyMatch(entry.organizationIds()::contains);
        }

        void classify(Set<String> fields, String organization, String rank, Set<String> positions,
                      boolean missingPosition, Set<String> principalPositions, Set<String> unknownPrincipalPositions,
                      boolean principal, boolean principalUnknown, Object managedCadre, Object managedUnit,
                      Map<String, Object> values, Map<String, Entry> evidence) {
            var ancestry = lineage(organization);
            if (ancestry.isEmpty()) return;
            if (fields.contains("organizationNature")) {
                for (String ancestor : ancestry) {
                    Set<String> targets = new TreeSet<>();
                    for (var entry : entries) {
                        if (!entry.enabled() || !entry.targetActive() || !entry.kind().equals("ORGANIZATION_NATURE")
                                || !entry.organizationIds().contains(ancestor) || !ancestor.equals(organization) && !entry.inherit()) continue;
                        evidence.put(entry.id(), entry);
                        targets.add(entry.targetTagId());
                    }
                    if (!targets.isEmpty()) {
                        if (targets.size() == 1) values.put("organizationNature", targets.iterator().next());
                        break;
                    }
                }
            }
            Set<String> domains = new TreeSet<>();
            List<Entry> levels = new ArrayList<>();
            Set<String> mappedPositions = new HashSet<>();
            int uncertainPriority = Integer.MIN_VALUE;
            for (var entry : entries) {
                if (!entry.enabled() || !entry.targetActive() || !fields.contains(FIELDS.getOrDefault(entry.kind(), ""))
                        || entry.kind().equals("ORGANIZATION_NATURE") || !applies(entry, organization)) continue;
                boolean matches = !rank.isBlank() && entry.sourceCode().equals("RANK:" + rank)
                        || positions.stream().anyMatch(code -> entry.sourceCode().equals("POSITION:" + code));
                if (!matches) continue;
                evidence.put(entry.id(), entry);
                if (entry.kind().equals("POSITION_DOMAIN")) {
                    domains.add(entry.targetTagId());
                    if (entry.sourceCode().startsWith("POSITION:")) mappedPositions.add(entry.sourceCode().substring(9));
                    continue;
                }
                if (entry.targetCode().equals("PRINCIPAL")) {
                    boolean principalMatch = entry.sourceCode().startsWith("POSITION:")
                            ? principalPositions.contains(entry.sourceCode().substring(9)) : principal;
                    boolean uncertain = entry.sourceCode().startsWith("POSITION:")
                            ? unknownPrincipalPositions.contains(entry.sourceCode().substring(9)) : principalUnknown;
                    if (Boolean.FALSE.equals(managedCadre) || Boolean.FALSE.equals(managedUnit)) continue;
                    if (managedCadre == null || managedUnit == null || !principalMatch && uncertain) {
                        uncertainPriority = Math.max(uncertainPriority, entry.priority());
                        continue;
                    }
                    if (!principalMatch) continue;
                }
                levels.add(entry);
            }
            if (!domains.isEmpty()) {
                values.put("positionDomain", new RuleFacts.PartialValues(Set.copyOf(domains), !missingPosition && mappedPositions.containsAll(positions)));
            }
            if (!missingPosition && !levels.isEmpty()) {
                int highest = levels.stream().mapToInt(Entry::priority).max().orElseThrow();
                var targets = levels.stream().filter(entry -> entry.priority() == highest).map(Entry::targetTagId).distinct().toList();
                if (uncertainPriority < highest && targets.size() == 1) values.put("roleLevel", targets.getFirst());
            }
        }
    }
}
