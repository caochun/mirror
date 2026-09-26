package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes constrained Action effects in one Storage SPI transaction. */
public final class ActionExecutor {
    private final ExpressionEvaluator evaluator;

    public ActionExecutor() {
        this(ExpressionEvaluator.simple());
    }

    public ActionExecutor(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public ActionResult execute(ActionManifest manifest, RequestContext context,
                                ActionActor actor, Map<String, Object> parameters,
                                StorageProvider storage) {
        String actionId = "act_" + UUID.randomUUID();
        for (ActionManifest.Precondition precondition : manifest.preconditions()) {
            if (!evaluator.evaluate(precondition.expression(), parameters, actor)) {
                return new ActionResult(false, actionId, List.of());
            }
        }

        List<EntityKey> affected = new ArrayList<>();
        try (Transaction transaction = storage.beginTransaction(context)) {
            for (ActionManifest.ActionEffect effect : manifest.effects()) {
                if (effect instanceof ActionManifest.UpdateObject update) {
                    ObjectRecord target = object(parameters, update.target());
                    Map<String, Object> values = resolveMap(update.set(), parameters);
                    transaction.updateObject(target.type(), target.id(), values, target.version());
                    affected.add(target.key());
                } else if (effect instanceof ActionManifest.CreateObject create) {
                    Map<String, Object> values = resolveMap(create.properties(), parameters);
                    ObjectRecord created = transaction.createObject(create.objectType(), resolveId(create.target(), parameters), values);
                    affected.add(created.key());
                } else if (effect instanceof ActionManifest.CreateLink create) {
                    EntityKey from = entity(parameters, create.from());
                    EntityKey to = entity(parameters, create.to());
                    LinkRecord link = transaction.createLink(create.linkType(), resolveId(create.linkType(), parameters), from, to,
                            resolveMap(create.properties(), parameters));
                    affected.add(new EntityKey(link.type(), link.id()));
                } else if (effect instanceof ActionManifest.DeleteLink delete) {
                    transaction.deleteLink(delete.linkType(), delete.linkId(), currentLinkVersion(storage, context, delete));
                    affected.add(new EntityKey(delete.linkType(), delete.linkId()));
                }
            }
            transaction.commit();
        }
        return new ActionResult(true, actionId, affected);
    }

    private static long currentLinkVersion(StorageProvider storage, RequestContext context, ActionManifest.DeleteLink effect) {
        LinkRecord link = storage.getLink(context, effect.linkType(), effect.linkId());
        if (link == null) throw new IllegalArgumentException("link not found: " + effect.linkType() + ":" + effect.linkId());
        return link.version();
    }

    private static ObjectRecord object(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (!(value instanceof ObjectRecord object)) throw new IllegalArgumentException("Action parameter is not an object: " + name);
        return object;
    }

    private static EntityKey entity(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof ObjectRecord object) return object.key();
        if (value instanceof EntityKey key) return key;
        throw new IllegalArgumentException("Action endpoint is not an object: " + name);
    }

    private static String resolveId(String reference, Map<String, Object> parameters) {
        Object value = parameters.get(reference);
        if (value instanceof ObjectRecord object) return object.id();
        if (value instanceof EntityKey key) return key.id();
        return reference.startsWith("params.") ? String.valueOf(value) : reference + "-" + Instant.now().toEpochMilli();
    }

    private static Map<String, Object> resolveMap(Map<String, String> source, Map<String, Object> parameters) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, resolveValue(value, parameters)));
        return result;
    }

    private static Object resolveValue(String value, Map<String, Object> parameters) {
        if (value.startsWith("params.")) return parameters.get(value.substring("params.".length()));
        int dot = value.indexOf('.');
        if (dot > 0 && parameters.containsKey(value.substring(0, dot))) {
            Object current = parameters.get(value.substring(0, dot));
            String field = value.substring(dot + 1);
            if (current instanceof ObjectRecord object && field.equals("id")) return object.id();
            if (current instanceof ObjectRecord object) return object.properties().get(field);
        }
        return value;
    }
}
