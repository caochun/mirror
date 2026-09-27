package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Resolves selection intent, retaining exclusions and explicit/condition overlap. */
@Service
public class ReminderSelection {
    private final StorageProvider storage;
    private final DirectoryService directory;

    public ReminderSelection(StorageProvider storage, DirectoryService directory) {
        this.storage = storage;
        this.directory = directory;
    }

    public Result resolve(Accounts.Actor actor, Filter filter) {
        filter.validate();
        Set<String> scope = directory.scope(actor);
        if (!scope.containsAll(filter.organizationIds())) throw new AccessDeniedException("组织超出权限范围");
        Set<String> organizations = new HashSet<>();
        var queue = new ArrayDeque<>(filter.organizationIds());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!organizations.add(id)) continue;
            for (var link : directory.links(actor, new EntityKey("Organization", id), "OrganizationParent",
                    StorageProvider.Direction.INBOUND)) {
                if (scope.contains(link.from().id())) queue.addLast(link.from().id());
            }
        }
        for (String id : filter.personIds()) directory.person(actor, id);
        for (String id : filter.excludedIds()) directory.person(actor, id);
        var tagCatalog = directory.all(actor, "TagDefinition");
        Map<String, Set<String>> tagGroups = new HashMap<>();
        for (String id : filter.tagIds()) {
            var tag = storage.getObject(actor.context(), "TagDefinition", id);
            if (tag == null || tag.isDeleted() || !"ACTIVE".equals(text(tag, "status"))) {
                throw new BusinessConflict("筛选标签不存在或已停用");
            }
            Set<String> descendants = new HashSet<>();
            descendants.add(id);
            boolean changed;
            do {
                changed = false;
                for (var child : tagCatalog) {
                    if (descendants.contains(text(child, "parentId")) && "ACTIVE".equals(text(child, "status"))) {
                        changed |= descendants.add(child.id());
                    }
                }
            } while (changed);
            tagGroups.put(id, descendants);
        }
        Map<String, Set<String>> tags = new HashMap<>();
        for (var tag : directory.all(actor, "PersonTagAssignment")) {
            if ("ACTIVE".equals(text(tag, "state")) && !Boolean.TRUE.equals(tag.properties().get("manualSuppressed"))) {
                tags.computeIfAbsent(text(tag, "personId"), ignored -> new HashSet<>()).add(text(tag, "tagDefinitionId"));
            }
        }
        List<Entry> entries = new ArrayList<>();
        boolean hasConditions = !organizations.isEmpty() || !filter.tagIds().isEmpty();
        for (var person : directory.all(actor, "Person")) {
            var links = directory.links(actor, person.key(), "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND);
            String orgId = links.size() == 1 ? links.getFirst().to().id() : "";
            if (!scope.contains(orgId) && !actor.role().equals("SUPER_ADMIN")) continue;
            Set<String> personTags = tags.getOrDefault(person.id(), Set.of());
            boolean tagged = filter.tagIds().isEmpty() || (filter.tagOperator().equals("ALL")
                    ? filter.tagIds().stream().allMatch(id -> tagGroups.get(id).stream().anyMatch(personTags::contains))
                    : filter.tagIds().stream().anyMatch(id -> tagGroups.get(id).stream().anyMatch(personTags::contains)));
            boolean matched = hasConditions && (organizations.isEmpty() || organizations.contains(orgId)) && tagged;
            boolean explicit = filter.personIds().contains(person.id());
            boolean excluded = filter.excludedIds().contains(person.id());
            if (!matched && !explicit && !excluded) continue;
            var organization = orgId.isEmpty() ? null : storage.getObject(actor.context(), "Organization", orgId);
            String reason = "";
            if (!"ACTIVE".equals(text(person, "status"))) reason = "人员已停用";
            else if (organization == null || organization.isDeleted() || !"ACTIVE".equals(text(organization, "status"))) reason = "当前单位不唯一或已停用";
            else if (!"OK".equals(text(person, "identityStatus")) || text(person, "identityReference").isBlank()) reason = "接收身份未唯一核实";
            List<String> evidence = new ArrayList<>();
            evidence.add(person.id() + ":" + person.version());
            evidence.add(organization == null ? "missing-org" : organization.id() + ":" + organization.version());
            for (var link : directory.links(actor, person.key(), "EligibilityForPerson", StorageProvider.Direction.INBOUND)) {
                var eligibility = storage.getObject(actor.context(), "ObjectEligibility", link.from().id());
                if (eligibility != null && !eligibility.isDeleted()) {
                    evidence.add(eligibility.id() + ":" + eligibility.version());
                    if (!"ELIGIBLE".equals(text(eligibility, "state"))) reason = "非对象账号或资格待核实";
                }
            }
            entries.add(new Entry(person.id(), text(person, "name"), orgId,
                    organization == null ? "待核实单位" : text(organization, "name"), matched, explicit, excluded,
                    reason, BusinessCommands.hash(String.join("|", new TreeSet<>(evidence))), person.version()));
        }
        entries.sort(java.util.Comparator.comparing(Entry::personId));
        return new Result(filter, List.copyOf(entries));
    }

    private static String text(ObjectRecord object, String field) {
        Object value = object.properties().get(field);
        return value == null ? "" : value.toString();
    }

    public record Filter(List<String> organizationIds, List<String> tagIds, String tagOperator,
                         List<String> personIds, List<String> excludedIds) {
        public void validate() {
            if (organizationIds == null || tagIds == null || personIds == null || excludedIds == null
                    || tagOperator == null || !Set.of("ANY", "ALL").contains(tagOperator)) throw new IllegalArgumentException("Invalid selection");
            for (var values : List.of(organizationIds, tagIds, personIds, excludedIds)) {
                if (values.size() > 50000 || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 512)) {
                    throw new IllegalArgumentException("Invalid selection members");
                }
            }
        }
    }

    public record Entry(String personId, String name, String organizationId, String organizationName,
                        boolean matchedByCondition, boolean explicitlyIncluded, boolean manuallyExcluded,
                        String ineligibleReason, String eligibilityDigest, long personVersion) {
        public boolean included() {
            return (matchedByCondition || explicitlyIncluded) && !manuallyExcluded && ineligibleReason.isEmpty();
        }
    }

    public record Result(Filter filter, List<Entry> entries) {
        public List<Entry> included() { return entries.stream().filter(Entry::included).toList(); }
    }
}
