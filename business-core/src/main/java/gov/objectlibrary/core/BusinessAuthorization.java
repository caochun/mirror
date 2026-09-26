package gov.objectlibrary.core;

import org.openfoundry.foundation.spi.AuditEntry;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Small relationship based policy used by the business application layer. */
public final class BusinessAuthorization {
    public static final String SUPER_ADMIN = "super-admin";
    public static final String AREA_ADMIN = "area-admin";
    public static final String UNIT_ADMIN = "unit-admin";
    public static final String REVIEWER = "reviewer";

    private final StorageProvider storage;

    public BusinessAuthorization(StorageProvider storage) { this.storage = storage; }

    public boolean canReadPerson(RequestContext context, String role, String personId, String organizationId) {
        if (SUPER_ADMIN.equals(role) || REVIEWER.equals(role)) return true;
        ObjectRecord person = storage.getObject(context, "Person", personId);
        if (person == null) return false;
        return reachableOrganization(context, person.key(), organizationId, AREA_ADMIN.equals(role));
    }

    public void requireCanReadPerson(RequestContext context, String role, String personId, String organizationId) {
        if (!canReadPerson(context, role, personId, organizationId)) {
            throw new SecurityException("person is outside the actor organization scope");
        }
    }

    /** Records a sensitive lookup only after authorization has succeeded. */
    public ObjectRecord readSensitivePerson(RequestContext context, String role,
                                             String personId, String organizationId) {
        requireCanReadPerson(context, role, personId, organizationId);
        ObjectRecord person = storage.getObject(context, "Person", personId);
        try (var tx = storage.beginTransaction(context)) {
            tx.appendAudit(new AuditEntry("audit-sensitive-" + UUID.randomUUID(), Instant.now(),
                    context.tenantId(), context.actorId(), "sensitive-read", "Person", personId,
                    "ReadSensitivePerson", tx.transactionId(), "success",
                    java.util.Map.of("organizationId", organizationId, "role", role)));
            tx.commit();
        }
        return person;
    }

    private boolean reachableOrganization(RequestContext context, EntityKey person, String organizationId,
                                          boolean includeDescendants) {
        var current = storage.getLinks(context, person, "PersonBelongsToOrganization",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults());
        if (current.stream().anyMatch(link -> organizationId.equals(link.to().id()))) return true;
        if (!includeDescendants) return false;
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(organizationId);
        while (!queue.isEmpty()) {
            String parent = queue.removeFirst();
            for (var link : storage.getLinks(context, new EntityKey("Organization", parent),
                    "OrganizationParent", StorageProvider.Direction.INBOUND, QueryOptions.defaults())) {
                if (seen.add(link.from().id()) && current.stream().anyMatch(candidate -> candidate.to().id().equals(link.from().id()))) return true;
                if (seen.add(link.from().id())) queue.addLast(link.from().id());
            }
        }
        return false;
    }
}
