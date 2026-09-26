package gov.objectlibrary.core;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Read/query facade for an AI assistant; mutating work is represented by a human-confirmed card. */
public final class AiAssistantService {
    private final StorageProvider storage;
    private final ObjectLibraryService objectLibrary;
    private final Map<String, ActionCard> pending = new ConcurrentHashMap<>();

    public AiAssistantService(StorageProvider storage) {
        this.storage = storage;
        this.objectLibrary = new ObjectLibraryService(storage);
    }

    public List<ObjectRecord> findPeopleWithTag(RequestContext context, String tagDefinitionId) {
        return storage.queryObjects(context, "PersonTagAssignment", QueryOptions.defaults()).stream()
                .filter(record -> "ACTIVE".equals(record.properties().get("state")))
                .filter(record -> storage.getLinks(context, record.key(), "TagAssignmentUsesDefinition",
                        StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).stream()
                        .anyMatch(link -> tagDefinitionId.equals(link.to().id())))
                .flatMap(record -> storage.getLinks(context, record.key(), "PersonHasTag",
                        StorageProvider.Direction.INBOUND, QueryOptions.defaults()).stream())
                .map(link -> storage.getObject(context, "Person", link.from().id()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public ActionCard proposeSend(RequestContext context, String taskId, String reason) {
        if (storage.getObject(context, "ReminderTask", taskId) == null) throw new IllegalArgumentException("task not found");
        ActionCard card = new ActionCard("card-" + UUID.randomUUID(), "SendReminder", taskId, reason, false);
        pending.put(card.id(), card);
        return card;
    }

    public ObjectRecord confirmSend(RequestContext context, String cardId) {
        ActionCard card = pending.remove(cardId);
        if (card == null) throw new IllegalArgumentException("action card is missing or already confirmed");
        if (card.confirmed()) throw new IllegalStateException("action card already confirmed");
        return objectLibrary.transitionTask(context, card.targetId(), "SENDING");
    }

    public record ActionCard(String id, String action, String targetId, String reason, boolean confirmed) {}
}
