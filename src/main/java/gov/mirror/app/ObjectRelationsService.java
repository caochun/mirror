package gov.mirror.app;

import org.openfoundry.foundation.spi.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.*;

/** Administrative relation inspection using registered link types, independent of ODL navigation fields. */
@Service
public final class ObjectRelationsService {
    private final FoundryRuntime runtime;
    private final MirrorAccounts accounts;
    private final ObjectPresentationService presentations;

    public ObjectRelationsService(FoundryRuntime runtime, MirrorAccounts accounts, ObjectPresentationService presentations) {
        this.runtime = runtime;
        this.accounts = accounts;
        this.presentations = presentations;
    }

    public record Relation(String id, String type, EntityKey from, EntityKey to, String direction,
                           ObjectRecord target, ObjectPresentationService.Presentation targetPresentation,
                           boolean ended, long version, Instant validFrom, Instant validTo) {}
    public record Page(List<Relation> items, int total, int offset, int limit) {}

    public Page list(String type, String id, boolean includeEnded, String relationshipType, int offset, int limit) {
        EntityKey source = requireSource(type, id);
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid relationship pagination");
        var definitions = runtime.pack().ontology().schema().linkTypes();
        if (!relationshipType.isBlank() && definitions.stream().noneMatch(link -> link.name().equals(relationshipType)
                && (link.fromType().equals(type) || link.toType().equals(type)))) {
            throw new IllegalArgumentException("Relationship type does not apply to this object");
        }
        var all = new LinkedHashMap<String, LinkRecord>();
        var options = new QueryOptions(Integer.MAX_VALUE, 0, null, null, includeEnded);
        for (var link : definitions) {
            if (!relationshipType.isBlank() && !link.name().equals(relationshipType)) continue;
            if (link.fromType().equals(type)) {
                runtime.storage().getLinks(accounts.context(), source, link.name(), StorageProvider.Direction.OUTBOUND, options)
                        .forEach(row -> all.put(row.type() + ":" + row.id(), row));
            }
            if (link.toType().equals(type)) {
                runtime.storage().getLinks(accounts.context(), source, link.name(), StorageProvider.Direction.INBOUND, options)
                        .forEach(row -> all.put(row.type() + ":" + row.id(), row));
            }
        }
        var rows = all.values().stream().sorted(Comparator.comparing(LinkRecord::type).thenComparing(LinkRecord::id))
                .skip(offset).limit(limit).map(link -> {
                    boolean outgoing = link.from().equals(source);
                    EntityKey target = outgoing ? link.to() : link.from();
                    var object = runtime.application().getObject(accounts.context(), accounts.principal(), target.type(), target.id());
                    String direction = link.from().equals(link.to()) ? "BOTH" : outgoing ? "OUTBOUND" : "INBOUND";
                    return new Relation(link.id(), link.type(), link.from(), link.to(), direction, object, presentations.describe(object),
                            link.isDeleted(), link.version(), link.validFrom(), link.validTo());
                }).toList();
        runtime.application().requireCurrentSchema(accounts.context(), accounts.principal());
        return new Page(rows, all.size(), offset, limit);
    }

    public List<HistorySnapshot> history(String type, String id, String relationshipType, String relationshipId) {
        EntityKey source = requireSource(type, id);
        if (runtime.pack().ontology().schema().linkTypes().stream().noneMatch(link -> link.name().equals(relationshipType))) {
            throw new IllegalArgumentException("Unknown relationship type");
        }
        LinkRecord link = runtime.storage().getLink(accounts.context(), relationshipType, relationshipId);
        if (link == null || (!source.equals(link.from()) && !source.equals(link.to()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Relationship does not belong to this object");
        }
        var history = runtime.application().history(accounts.context(), accounts.principal(), new EntityKey(relationshipType, relationshipId));
        runtime.application().requireCurrentSchema(accounts.context(), accounts.principal());
        return history;
    }

    private EntityKey requireSource(String type, String id) {
        accounts.requireAdmin();
        if (runtime.pack().ontology().schema().objectTypes().stream().noneMatch(object -> object.name().equals(type))) {
            throw new IllegalArgumentException("Unknown object type");
        }
        if (runtime.application().getObject(accounts.context(), accounts.principal(), type, id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Object is unavailable");
        }
        return new EntityKey(type, id);
    }
}
