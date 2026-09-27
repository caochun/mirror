package gov.objectlibrary.server;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.StorageProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

import static gov.objectlibrary.server.DeliveryService.text;

/** Current eligibility of a source; stored contribution history is never rewritten by a read. */
@Component
class TagActivity {
    private final StorageProvider storage;
    private final DirectoryService directory;

    TagActivity(StorageProvider storage, DirectoryService directory) {
        this.storage = storage;
        this.directory = directory;
    }

    boolean contributionActive(Accounts.Actor actor, ObjectRecord contribution) {
        if (contribution == null || contribution.isDeleted()) return false;
        return contributionActive(contribution.properties(), ruleId -> {
            var rule = storage.getObject(actor.context(), "TagRule", ruleId);
            return rule == null || rule.isDeleted() ? null : rule.properties();
        });
    }

    static boolean contributionActive(Map<String, Object> contribution, Function<String, Map<String, Object>> rules) {
        if (!"ACTIVE".equals(contribution.get("state"))) return false;
        String ruleId = String.valueOf(contribution.getOrDefault("ruleId", ""));
        if (!"RULE".equals(contribution.get("source")) || ruleId.isBlank()) return true;
        var rule = rules.apply(ruleId);
        return rule != null && "ACTIVE".equals(rule.get("status"))
                && contribution.get("ruleVersionId") != null
                && contribution.get("ruleVersionId").equals(rule.get("currentVersionId"));
    }

    String state(Accounts.Actor actor, ObjectRecord assignment) {
        if (assignment == null) return "ABSENT";
        if (Boolean.TRUE.equals(assignment.properties().get("manualSuppressed"))
                || "REMOVED".equals(text(assignment, "state"))) return "SUPPRESSED";
        String state = text(assignment, "state");
        if (!state.equals("ACTIVE")) return state;
        var links = directory.links(actor, assignment.key(), "AssignmentHasContribution", StorageProvider.Direction.OUTBOUND);
        // Preserve the existing legacy view until its source is explicitly migrated.
        if (links.isEmpty()) return state;
        return links.stream().map(link -> storage.getObject(actor.context(), "TagContribution", link.to().id()))
                .anyMatch(contribution -> contributionActive(actor, contribution)) ? "ACTIVE" : "EXPIRED";
    }
}
