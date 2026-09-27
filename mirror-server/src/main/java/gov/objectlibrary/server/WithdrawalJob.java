package gov.objectlibrary.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "mirror.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class WithdrawalJob {
    private final WithdrawalService withdrawals;

    WithdrawalJob(WithdrawalService withdrawals) { this.withdrawals = withdrawals; }

    @Scheduled(fixedDelayString = "${mirror.withdrawal-interval-ms:10000}", initialDelay = 10000)
    void dispatch() { withdrawals.dispatchPending(); }
}
