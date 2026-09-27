package gov.objectlibrary.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "mirror.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class ReadingJob {
    private final ReadingService reading;

    ReadingJob(ReadingService reading) {
        this.reading = reading;
    }

    @Scheduled(fixedDelayString = "${mirror.overdue-interval-ms:30000}", initialDelay = 30000)
    void evaluate() {
        reading.evaluateDue();
    }
}
