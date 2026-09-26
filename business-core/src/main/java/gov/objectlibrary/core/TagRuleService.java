package gov.objectlibrary.core;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

/** Applies deterministic rule results without erasing manual or AI sourced tags. */
public final class TagRuleService {
    private final StorageProvider storage;
    private final ObjectLibraryService objectLibrary;

    public TagRuleService(StorageProvider storage) {
        this.storage = storage;
        this.objectLibrary = new ObjectLibraryService(storage);
    }

    public ObjectRecord reconcile(RequestContext context, String ruleId, String personId,
                                  String assignmentId, String tagDefinitionId, String tagVersion,
                                  boolean matches) {
        ObjectRecord rule = storage.getObject(context, "TagRule", ruleId);
        if (rule == null || !"ACTIVE".equals(rule.properties().get("status"))) {
            throw new IllegalArgumentException("rule is not active: " + ruleId);
        }
        ObjectRecord assignment = storage.getObject(context, "PersonTagAssignment", assignmentId);
        if (matches) {
            if (assignment != null && Boolean.TRUE.equals(assignment.properties().get("manualSuppressed"))) return assignment;
            if (assignment != null) return assignment;
            return objectLibrary.assignTag(context, assignmentId, personId, tagDefinitionId, tagVersion, "RULE");
        }
        if (assignment == null) return null;
        if (!"RULE".equals(assignment.properties().get("source"))) return assignment;
        return objectLibrary.expireRuleTag(context, assignmentId, "rule no longer matches");
    }

    public int activeRuleTagCount(RequestContext context, String personId) {
        return storage.queryObjects(context, "PersonTagAssignment", QueryOptions.defaults()).stream()
                .filter(record -> "ACTIVE".equals(record.properties().get("state")))
                .filter(record -> storage.getLinks(context, record.key(), "PersonHasTag",
                        StorageProvider.Direction.INBOUND, QueryOptions.defaults()).stream()
                        .anyMatch(link -> personId.equals(link.from().id())))
                .mapToInt(ignored -> 1).sum();
    }
}
