package gov.objectlibrary.core;

import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;

/** Derives operational metrics from immutable recipient and receipt records. */
public final class ReminderStatisticsService {
    private final StorageProvider storage;

    public ReminderStatisticsService(StorageProvider storage) { this.storage = storage; }

    public ReminderStatistics summarize(RequestContext context, String taskVersionId) {
        var recipients = storage.queryObjects(context, "RecipientRecord", QueryOptions.defaults()).stream()
                .filter(record -> taskVersionId.equals(record.properties().get("taskVersionId"))).toList();
        int delivered = 0;
        int read = 0;
        int overdue = 0;
        int failed = 0;
        for (var recipient : recipients) {
            String state = String.valueOf(recipient.properties().get("state"));
            if ("UNREAD".equals(state) || "READ".equals(state) || "OVERDUE".equals(state)) delivered++;
            if ("READ".equals(state)) read++;
            if ("OVERDUE".equals(state)) overdue++;
            if ("DELIVERY_FAILED".equals(state)) failed++;
        }
        return new ReminderStatistics(recipients.size(), delivered, read, failed, overdue);
    }

    public record ReminderStatistics(int recipients, int delivered, int read, int failed, int overdue) {
        public double deliveryRate() { return recipients == 0 ? 0 : (double) delivered / recipients; }
        public double readRate() { return delivered == 0 ? 0 : (double) read / delivered; }
        public double overdueRate() { return delivered == 0 ? 0 : (double) overdue / delivered; }
    }
}
