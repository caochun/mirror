package gov.mirror.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.*;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Explicit synthetic snapshots inspired by the workbook; never an authoritative source import. */
final class ReferenceDemoData {
    static final String RESOURCE = "demo/reference-personnel.json";
    private static final String ACTOR = "reference-demo-bootstrap";

    record PersonSeed(String id, String name, String organizationPath, String rank, String position,
                      String sex, String sourceStatus, String membershipStatus) {}
    record TagSeed(String id, String name, String parent, String status, String description) {}
    record AssignmentSeed(String person, String tag, String suppression, String status, String reason) {}
    record Dataset(String datasetId, String description, List<PersonSeed> people, List<TagSeed> tags,
                   List<AssignmentSeed> assignments) {}

    static void populate(StorageProvider storage) {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            populate(storage, input.readAllBytes());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read reference demo data", failure);
        }
    }

    static void populate(StorageProvider storage, byte[] content) {
        final Dataset dataset;
        final String fingerprint;
        try {
            dataset = new ObjectMapper().readValue(content, Dataset.class);
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid reference demo data", failure);
        }
        var context = RequestContext.system(MirrorAccounts.TENANT, ACTOR);
        String receiptKey = LineageValues.hash(true, List.of("mirror.bootstrap.reference", dataset.datasetId()));
        Instant now = Instant.now();
        String factTime = now.minusSeconds(86400).toString();
        try (var tx = storage.beginTransaction(context)) {
            tx.acquireWrite();
            var receipt = tx.getCommandReceipt(receiptKey);
            if (receipt != null) {
                if (!receipt.requestHash().equals(fingerprint)) {
                    throw new IllegalStateException("Reference dataset changed under an existing ID; explicit migration required");
                }
                return;
            }
            var organizations = new HashMap<String, EntityKey>();
            var positions = new HashMap<String, EntityKey>();
            var people = new HashMap<String, EntityKey>();
            var personOrganizations = new HashMap<String, EntityKey>();
            var tags = new HashMap<String, EntityKey>();
            for (var seed : dataset.people()) {
                EntityKey parent = null;
                String path = "";
                for (String segment : seed.organizationPath().split("/")) {
                    path = path.isEmpty() ? segment : path + "/" + segment;
                    EntityKey organization = organizations.get(path);
                    if (organization == null) {
                        organization = key("Organization", stable("org", path));
                        tx.createObject(organization.type(), organization.id(), Map.of("name", segment, "sourceStatus", "ACTIVE"));
                        organizations.put(path, organization);
                        if (parent != null) link(tx, "OrganizationParent", organization, parent);
                    }
                    parent = organization;
                }
                EntityKey person = key("Person", seed.id());
                tx.createObject("Person", person.id(), Map.of("name", seed.name(), "sex", seed.sex(), "personalRankCode", seed.rank()));
                people.put(seed.id(), person);
                personOrganizations.put(seed.id(), parent);
                link(tx, "PersonCurrentOrganization", person, parent);
                EntityKey membership = key("ObjectMembership", seed.id() + "-membership");
                tx.createObject(membership.type(), membership.id(), Map.of("status", seed.membershipStatus(),
                        "decisionCode", "SYNTHETIC_SCENARIO", "decidedBy", ACTOR, "decidedAt", factTime,
                        "note", "合成准入场景，不由 Excel 同步状态推导，不是真实业务决定"));
                link(tx, "MembershipPerson", membership, person);
                link(tx, "MembershipDecisionOrganization", membership, parent);
                EntityKey identity = key("ExternalIdentity", seed.id() + "-source");
                tx.createObject(identity.type(), identity.id(), Map.of("identityKey", dataset.datasetId() + ":" + seed.id(),
                        "sourceSystem", "MOCK_REFERENCE", "kind", "PERSON_SOURCE", "subjectRef", "synthetic:" + seed.id(),
                        "sourceStatus", seed.sourceStatus()));
                link(tx, "IdentityPerson", identity, person);
                EntityKey position = positions.get(seed.position());
                if (position == null) {
                    position = key("Position", stable("position", seed.position()));
                    tx.createObject(position.type(), position.id(), Map.of("name", seed.position(), "sourceStatus", "ACTIVE"));
                    positions.put(seed.position(), position);
                }
                EntityKey appointment = key("Appointment", seed.id() + "-appointment");
                tx.createObject(appointment.type(), appointment.id(), Map.of("status", "CURRENT", "sourceSystem", "MOCK_REFERENCE"));
                link(tx, "AppointmentPerson", appointment, person);
                link(tx, "AppointmentOrganization", appointment, parent);
                link(tx, "AppointmentPosition", appointment, position);
            }
            for (var seed : dataset.tags()) {
                EntityKey tag = key("Tag", seed.id());
                tx.createObject(tag.type(), tag.id(), Map.of("status", seed.status()));
                tags.put(seed.id(), tag);
                EntityKey version = key("TagVersion", seed.id() + "-v1");
                tx.createObject(version.type(), version.id(), Map.of("versionKey", seed.id() + ":1", "revision", 1,
                        "name", seed.name(), "description", seed.description(), "dimension", "PERSON", "duration", "LONG_TERM",
                        "publishedBy", ACTOR, "publishedAt", factTime));
                link(tx, "TagVersionOf", version, tag);
                link(tx, "TagCurrentVersion", tag, version);
            }
            for (var seed : dataset.tags()) {
                if (seed.parent() != null) link(tx, "TagParent", tags.get(seed.id()), tags.get(seed.parent()));
            }
            int index = 0;
            for (var seed : dataset.assignments()) {
                EntityKey person = reference(people, seed.person());
                EntityKey tag = reference(tags, seed.tag());
                String pair = LineageValues.hash(true, List.of(person.id(), tag.id()));
                EntityKey association = key("PersonTag", "person_tag_" + pair);
                var properties = new LinkedHashMap<String, Object>();
                properties.put("pairKey", pair);
                properties.put("suppression", seed.suppression());
                properties.put("note", seed.reason());
                if (seed.suppression().equals("SUPPRESSED")) {
                    properties.put("suppressedBy", ACTOR);
                    properties.put("suppressedAt", now.toString());
                }
                tx.createObject(association.type(), association.id(), properties);
                link(tx, "PersonTagPerson", association, person);
                link(tx, "PersonTagTag", association, tag);
                EntityKey contribution = key("TagContribution", "ref-contribution-" + ++index);
                var evidence = new LinkedHashMap<String, Object>();
                evidence.put("contributionKey", dataset.datasetId() + ":" + index);
                evidence.put("kind", "MANUAL");
                evidence.put("status", seed.status());
                evidence.put("effectiveFrom", factTime);
                evidence.put("actorId", ACTOR);
                evidence.put("reason", seed.reason());
                if (seed.status().equals("ENDED")) evidence.put("effectiveTo", now.toString());
                tx.createObject(contribution.type(), contribution.id(), evidence);
                link(tx, "ContributionForPersonTag", contribution, association);
                link(tx, "ContributionTagVersion", contribution, key("TagVersion", tag.id() + "-v1"));
                link(tx, "ContributionOrganization", contribution, personOrganizations.get(seed.person()));
            }
            var summary = Map.<String, Object>of("dataset", dataset.datasetId(), "synthetic", true,
                    "people", people.size(), "organizations", organizations.size(), "positions", positions.size(),
                    "tags", tags.size(), "assignments", dataset.assignments().size());
            tx.appendAudit(new AuditEntry(UUID.randomUUID().toString(), now, context.tenantId(), ACTOR,
                    "BOOTSTRAP", null, null, null, tx.transactionId(), "SUCCESS", summary));
            tx.putCommandReceipt(new CommandReceipt(receiptKey, ACTOR, "REFERENCE_DEMO_BOOTSTRAP", fingerprint, summary));
            tx.commit();
        }
    }

    private static String stable(String kind, String value) {
        return "ref-" + kind + "-" + LineageValues.hash(true, value).substring(0, 20);
    }

    private static EntityKey key(String type, String id) {
        return new EntityKey(type, id);
    }

    private static EntityKey reference(Map<String, EntityKey> entities, String id) {
        EntityKey entity = entities.get(id);
        if (entity == null) throw new IllegalArgumentException("Unknown demo reference: " + id);
        return entity;
    }

    private static void link(Transaction tx, String type, EntityKey from, EntityKey to) {
        if (from == null || to == null) throw new IllegalArgumentException("Missing demo relationship endpoint");
        String id = "ref-link-" + LineageValues.hash(true, List.of(type, from.type(), from.id(), to.type(), to.id()));
        tx.createLink(type, id, from, to, Map.of());
    }
}
