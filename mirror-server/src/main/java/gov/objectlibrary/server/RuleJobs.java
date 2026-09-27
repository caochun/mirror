package gov.objectlibrary.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "mirror.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class RuleJobs {
    private final RuleConfigurationService rules;
    private final RuleBatchService batches;
    private final RuleDeactivationService deactivations;

    RuleJobs(RuleConfigurationService rules, RuleBatchService batches, RuleDeactivationService deactivations) {
        this.rules = rules;
        this.batches = batches;
        this.deactivations = deactivations;
    }

    @Scheduled(fixedDelayString = "${mirror.rule-work-interval-ms:5000}", initialDelay = 5000)
    void processPreviews() {
        rules.processPreviews();
    }

    @Scheduled(fixedDelayString = "${mirror.rule-work-interval-ms:5000}", initialDelay = 5000)
    void processBatches() {
        batches.processPending();
    }

    @Scheduled(fixedDelayString = "${mirror.rule-work-interval-ms:5000}", initialDelay = 5000)
    void processDeactivations() {
        deactivations.processPending();
    }

    @Scheduled(fixedDelayString = "${mirror.rule-scan-interval-ms:60000}", initialDelay = 60000)
    void scan() { batches.scheduleChangedInputs(); }
}
