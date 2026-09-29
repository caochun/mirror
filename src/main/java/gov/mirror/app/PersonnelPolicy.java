package gov.mirror.app;

import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import java.util.*;

/** Mirror policy, evaluated again against the Action's actual transaction, including replay. */
final class PersonnelPolicy implements ActionAuthorizer {
    private final MirrorAccounts accounts;
    private final StorageProvider storage;

    PersonnelPolicy(MirrorAccounts accounts, StorageProvider storage) {
        this.accounts = accounts;
        this.storage = storage;
    }

    @Override
    public boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                           Map<String, Object> parameters) {
        var account = accounts.require(actor.id());
        return context.tenantId().equals(MirrorAccounts.TENANT) && actor.id().equals(context.actorId())
                && switch (definition.name()) {
                    case "RegisterManualPerson" -> has(account, "DIRECTORY");
                    case "DecideObjectMembership" -> has(account, "ADMIN");
                    case "AssignManualTag", "ApplyManualTagContribution", "SuppressPersonTag" -> has(account, "TAG_EDITOR");
                    default -> false;
                };
    }

    private static boolean has(AccountProperties.Account account, String role) {
        return account.roles().contains("ADMIN") || account.roles().contains(role);
    }

    @Override
    public boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                           Map<String, Object> parameters, Transaction transaction) {
        if (!allowed(context, actor, definition, parameters)) return false;
        var account = accounts.require(actor.id());
        for (Object value : parameters.values()) {
            if (value instanceof ObjectRecord object && !visible(account, object.key(), transaction, 0)) return false;
        }
        for (String field : List.of("decisionOrganization", "sourceOrganization")) {
            if (parameters.get(field) instanceof ObjectRecord organization
                    && !organization.id().equals(account.organization())) return false;
        }
        if (parameters.get("personTag") instanceof ObjectRecord personTag) {
            var people = links(context, personTag.key(), "PersonTagPerson", false, transaction);
            if (people.size() != 1) return false;
            var organizations = links(context, people.getFirst().to(), "PersonCurrentOrganization", false, transaction);
            if (organizations.size() != 1) return false;
            var organization = object(context, organizations.getFirst().to(), transaction);
            if (organization == null || !"ACTIVE".equals(organization.properties().get("sourceStatus"))) return false;
        }
        return true;
    }

    @Override
    public boolean allowedChanges(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                  Map<String, Object> parameters, List<EntityKey> affected, Transaction transaction) {
        if (!allowed(context, actor, definition, parameters, transaction)) return false;
        return affected.stream().allMatch(key -> visible(accounts.require(actor.id()), key, transaction, 0));
    }

    @Override
    public boolean allowedEffects(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                  Map<String, Object> parameters, List<ActionEffectAccess> effects, Transaction transaction) {
        return allowedChanges(context, actor, definition, parameters,
                effects.stream().map(ActionEffectAccess::entity).toList(), transaction);
    }

    public boolean visible(AccountProperties.Account account, EntityKey key, Transaction transaction, int depth) {
        if (depth > 6) return false;
        var context = RequestContext.system(MirrorAccounts.TENANT, account.username());
        ObjectRecord record = object(context, key, transaction);
        if (record == null) {
            LinkRecord link = transaction == null ? storage.getLink(context, key.type(), key.id())
                    : transaction.getLink(key.type(), key.id());
            return link != null && visible(account, link.from(), transaction, depth + 1)
                    && visible(account, link.to(), transaction, depth + 1);
        }
        if (record.isDeleted()) return false;
        if (account.roles().contains("ADMIN")) return true;
        if (key.type().equals("Organization")) return account.organizations().contains(key.id());
        if (Set.of("Tag", "TagVersion").contains(key.type())) return true;
        String ownership = switch (key.type()) {
            case "Person" -> "PersonCurrentOrganization";
            case "ObjectMembership" -> "MembershipPerson";
            case "PersonTag" -> "PersonTagPerson";
            case "TagContribution" -> "ContributionForPersonTag";
            default -> null;
        };
        if (ownership == null) return false;
        var edges = links(context, key, ownership, false, transaction);
        return edges.size() == 1 && visible(account, edges.getFirst().to(), transaction, depth + 1);
    }

    private ObjectRecord object(RequestContext context, EntityKey key, Transaction transaction) {
        // JDBC getObject tolerates unknown object type names, including registered link names.
        return transaction == null ? storage.getObject(context, key.type(), key.id())
                : transaction.getObject(key.type(), key.id());
    }

    List<LinkRecord> links(RequestContext context, EntityKey key, String type, boolean inbound, Transaction tx) {
        return tx == null ? storage.getLinks(context, key, type,
                inbound ? StorageProvider.Direction.INBOUND : StorageProvider.Direction.OUTBOUND,
                new QueryOptions(Integer.MAX_VALUE, 0, null, null, false))
                : tx.findLinks(type, inbound ? null : key, inbound ? key : null);
    }
}
