package gov.objectlibrary.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mirror.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class MappingJobs {
    private final MappingService mappings;

    MappingJobs(MappingService mappings) {
        this.mappings = mappings;
    }

    @Scheduled(fixedDelayString = "${mirror.rule-work-interval-ms:5000}", initialDelay = 5000)
    void preview() {
        mappings.processPending();
    }
}
