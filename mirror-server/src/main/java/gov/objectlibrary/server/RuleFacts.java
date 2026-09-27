package gov.objectlibrary.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static gov.objectlibrary.server.DeliveryService.text;

/** Reads only declared rule inputs. Non-standard job titles are never turned into classifications by keywords. */
@Component
class RuleFacts {
    private final StorageProvider storage;
    private final DirectoryService directory;
    private final ObjectMapper json;
    private final Clock clock;
    private final ClassificationMappings mappings;
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    RuleFacts(StorageProvider storage, DirectoryService directory, ObjectMapper json, Clock clock, ClassificationMappings mappings) {
        this.storage = storage;
        this.directory = directory;
        this.json = json;
        this.clock = clock;
        this.mappings = mappings;
    }

    Input load(Accounts.Actor actor, ObjectRecord person, Set<String> fields) {
        var catalog = fields.stream().anyMatch(ClassificationMappings.FIELDS::containsValue) ? mappings.load(actor) : null;
        return load(actor, person, fields, catalog);
    }

    Input load(Accounts.Actor actor, ObjectRecord person, Set<String> fields, ClassificationMappings.Snapshot catalog) {
        Map<String, Object> values = new HashMap<>();
        var evidence = new TreeSet<String>();
        evidence.add("person:" + person.id() + ":" + person.version());
        var organizations = directory.links(actor, person.key(), "PersonBelongsToOrganization", StorageProvider.Direction.OUTBOUND);
        evidence.addAll(organizations.stream().map(link -> "membership:" + link.id() + ":" + link.version()).toList());
        var org = organizations.size() == 1 ? storage.getObject(actor.context(), "Organization", organizations.getFirst().to().id()) : null;
        boolean eligible = "ACTIVE".equals(text(person, "status")) && org != null && !org.isDeleted() && "ACTIVE".equals(text(org, "status"));
        if (org != null) {
            evidence.add("org:" + org.id() + ":" + org.version());
            values.put("organizationId", org.id());
        }
        for (var link : directory.links(actor, person.key(), "EligibilityForPerson", StorageProvider.Direction.INBOUND)) {
            var qualification = storage.getObject(actor.context(), "ObjectEligibility", link.from().id());
            if (qualification != null && !qualification.isDeleted()) {
                evidence.add("eligibility:" + qualification.id() + ":" + qualification.version());
                eligible &= text(qualification, "state").equals("ELIGIBLE");
            }
        }
        Map<String, Object> profile = new HashMap<>();
        var profiles = directory.links(actor, person.key(), "ProfileForPerson", StorageProvider.Direction.INBOUND).stream()
                .map(link -> storage.getObject(actor.context(), "PersonProfile", link.from().id()))
                .filter(record -> record != null && !record.isDeleted() && Set.of("ACTIVE", "LINKED", "VALID").contains(text(record, "state"))).toList();
        profiles.forEach(record -> evidence.add("profile:" + record.id() + ":" + record.version()));
        if (profiles.size() == 1) {
            try {
                var root = json.readTree(text(profiles.getFirst(), "fieldsJson"));
                var availability = text(profiles.getFirst(), "fieldAvailabilityJson").isEmpty() ? null : json.readTree(text(profiles.getFirst(), "fieldAvailabilityJson"));
                if (root != null && root.isObject()) {
                    for (String name : List.of("birthDate", "joinedAt", "rank")) {
                        if (root.hasNonNull(name) && (availability == null || !availability.has(name) || availability.path(name).asText().equals("AVAILABLE"))) {
                            profile.put(name, root.get(name).asText());
                        }
                    }
                }
            } catch (Exception invalidProjection) {
                // Invalid fields become UNKNOWN for only their dependent conditions.
            }
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        if (fields.contains("ageYears") || fields.contains("serviceMonths")) evidence.add("date:" + today);
        LocalDate birth = date(value(person, profile, "birthDate"));
        LocalDate joined = date(value(person, profile, "joinedAt"));
        if (birth != null && !birth.isAfter(today) && Period.between(birth, today).getYears() <= 130) values.put("ageYears", Period.between(birth, today).getYears());
        if (joined != null && !joined.isAfter(today)) values.put("serviceMonths", (int) ChronoUnit.MONTHS.between(joined, today));
        String rank = value(person, profile, "rank");
        if (!rank.isBlank()) values.put("rank", rank);
        Set<String> positions = new TreeSet<>();
        boolean missingPosition = false;
        boolean principal = false;
        boolean principalUnknown = false;
        Set<String> principalPositions = new TreeSet<>();
        Set<String> unknownPrincipalPositions = new TreeSet<>();
        if (fields.contains("positionCode") || fields.contains("roleLevel") || fields.contains("positionDomain")) {
            for (var link : directory.links(actor, person.key(), "PersonHasAssignment", StorageProvider.Direction.OUTBOUND)) {
                var assignment = storage.getObject(actor.context(), "Assignment", link.to().id());
                if (assignment == null || assignment.isDeleted() || !text(assignment, "status").equals("ACTIVE") || !validInterval(assignment)) continue;
                var assignmentOrgs = directory.links(actor, assignment.key(), "AssignmentInOrganization", StorageProvider.Direction.OUTBOUND);
                if (org == null || assignmentOrgs.size() != 1 || !assignmentOrgs.getFirst().to().id().equals(org.id())) continue;
                evidence.add("assignment:" + assignment.id() + ":" + assignment.version());
                principal |= Boolean.TRUE.equals(assignment.properties().get("isPrincipal"));
                principalUnknown |= assignment.properties().get("isPrincipal") == null;
                var links = directory.links(actor, assignment.key(), "AssignmentUsesPosition", StorageProvider.Direction.OUTBOUND);
                if (links.size() != 1) { missingPosition = true; continue; }
                var position = storage.getObject(actor.context(), "Position", links.getFirst().to().id());
                if (position == null || position.isDeleted() || text(position, "standardCode").isEmpty()) missingPosition = true;
                else {
                    evidence.add("position:" + position.id() + ":" + position.version());
                    String code = text(position, "standardCode");
                    positions.add(code);
                    if (Boolean.TRUE.equals(assignment.properties().get("isPrincipal"))) principalPositions.add(code);
                    if (assignment.properties().get("isPrincipal") == null) unknownPrincipalPositions.add(code);
                }
            }
            if (!positions.isEmpty()) values.put("positionCode", new PartialValues(java.util.Collections.unmodifiableSet(new TreeSet<>(positions)), !missingPosition));
        }
        Map<String, ClassificationMappings.Entry> mappingEvidence = new java.util.TreeMap<>();
        if (catalog != null && org != null) {
            catalog.classify(fields, org.id(), rank, positions, missingPosition, principalPositions, unknownPrincipalPositions,
                    principal, principalUnknown, person.properties().get("managedCadre"), org.properties().get("managedUnit"), values, mappingEvidence);
        }
        mappingEvidence.values().forEach(entry -> evidence.add("mapping:" + entry.id() + ":" + entry.version()));
        Map<String, Object> used = new HashMap<>();
        for (String field : fields) if (values.containsKey(field)) used.put(field, values.get(field));
        String digest = BusinessCommands.hash(encode(used) + "/" + eligible + "/" + encode(mappingEvidence));
        return new Input(person.id(), text(person, "name"), org == null ? "" : org.id(), org == null ? "单位待核实" : text(org, "name"),
                eligible, Map.copyOf(used), digest, List.copyOf(evidence), List.copyOf(mappingEvidence.values()));
    }

    private boolean validInterval(ObjectRecord assignment) {
        try {
            String start = text(assignment, "startedAt");
            String end = text(assignment, "endedAt");
            return (start.isEmpty() || !Instant.parse(start).isAfter(clock.instant())) && (end.isEmpty() || Instant.parse(end).isAfter(clock.instant()));
        } catch (Exception invalid) { return false; }
    }
    private static String value(ObjectRecord person, Map<String, Object> profile, String key) {
        String value = text(person, key);
        return value.isEmpty() ? profile.getOrDefault(key, "").toString() : value;
    }
    private static LocalDate date(String value) {
        try { return value == null || value.isEmpty() ? null : LocalDate.parse(value); }
        catch (Exception invalid) { return null; }
    }
    private String encode(Object value) {
        try { return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
    }
    record Input(String personId, String name, String organizationId, String organizationName, boolean eligible,
                 Map<String, Object> fields, String digest, List<String> references, List<ClassificationMappings.Entry> mappings) {
        Input(String personId, String name, String organizationId, String organizationName, boolean eligible,
              Map<String, Object> fields, String digest, List<String> references) {
            this(personId, name, organizationId, organizationName, eligible, fields, digest, references, List.of());
        }
    }
    record PartialValues(Set<String> values, boolean complete) {}
}
