package gov.objectlibrary.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "mirror.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class ReviewExpiryJob {
    private final ReminderService reminders;

    ReviewExpiryJob(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${mirror.review-expiry-interval-ms:30000}", initialDelay = 30000)
    void expireReviews() {
        reminders.expireDueReviews();
    }
}
