package gov.objectlibrary.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "mirror.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class RevisionPublicationJob {
    private final ReminderRevisionService revisions;

    RevisionPublicationJob(ReminderRevisionService revisions) {
        this.revisions = revisions;
    }

    @Scheduled(fixedDelayString = "${mirror.publication-interval-ms:5000}", initialDelay = 5000)
    void publish() {
        revisions.publishApproved();
    }
}
