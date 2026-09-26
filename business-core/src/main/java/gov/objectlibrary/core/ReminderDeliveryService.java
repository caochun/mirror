package gov.objectlibrary.core;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

import java.util.ArrayList;
import java.util.List;

/** Sends a frozen task version one recipient at a time and records every result. */
public final class ReminderDeliveryService {
    private final StorageProvider storage;
    private final ObjectLibraryService objectLibrary;
    private final LulutongConnector connector;

    public ReminderDeliveryService(StorageProvider storage, LulutongConnector connector) {
        this.storage = storage;
        this.objectLibrary = new ObjectLibraryService(storage);
        this.connector = connector;
    }

    public DeliverySummary send(RequestContext context, String taskVersionId) {
        List<ObjectRecord> recipients = storage.queryObjects(context, "RecipientRecord", QueryOptions.defaults()).stream()
                .filter(record -> taskVersionId.equals(record.properties().get("taskVersionId")))
                .filter(record -> "PENDING".equals(record.properties().get("state"))
                        || "DELIVERY_FAILED".equals(record.properties().get("state")))
                .toList();
        int success = 0;
        int failure = 0;
        List<String> failed = new ArrayList<>();
        for (ObjectRecord recipient : recipients) {
            ObjectRecord version = storage.getObject(context, "ReminderTaskVersion", taskVersionId);
            LulutongConnector.DeliveryResponse response = connector.send(new LulutongConnector.DeliveryRequest(
                    recipient.id(), String.valueOf(recipient.properties().get("personId")),
                    String.valueOf(version.properties().get("titleSnapshot")),
                    String.valueOf(version.properties().get("bodySnapshot"))));
            objectLibrary.recordDeliveryResult(context, recipient.id(), response.success(),
                    response.externalId(), response.errorCode());
            if (response.success()) success++; else { failure++; failed.add(recipient.id()); }
        }
        return new DeliverySummary(recipients.size(), success, failure, failed);
    }

    public record DeliverySummary(int attempted, int succeeded, int failed, List<String> failedRecipientIds) {
        public DeliverySummary { failedRecipientIds = List.copyOf(failedRecipientIds); }
    }
}
