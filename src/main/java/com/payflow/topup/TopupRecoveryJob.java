package com.payflow.topup;

import com.payflow.config.PayflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Resolves top-ups stuck in PENDING (provider timeout, circuit open, or a crash between charge and commit).
 * Re-charging is safe because the provider de-duplicates on our top-up id. Running on several instances at once
 * is also safe: completion locks the top-up row and only a PENDING one is completed.
 */
@Component
public class TopupRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(TopupRecoveryJob.class);

    private final TopupRepository topups;
    private final TopupService topupService;
    private final PayflowProperties props;
    private final Clock clock;

    public TopupRecoveryJob(TopupRepository topups, TopupService topupService, PayflowProperties props, Clock clock) {
        this.topups = topups;
        this.topupService = topupService;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payflow.topups.recovery-interval}")
    public void recoverPending() {
        var stuck = topups.findByStatusAndCreatedAtBeforeOrderByCreatedAt(TopupEntity.Status.PENDING,
                clock.instant().minus(props.topups().recoveryMinAge()), Limit.of(50));
        for (TopupEntity t : stuck) {
            try {
                TopupEntity after = topupService.chargeAndComplete(t.getId(), t.getAmountMinor(), t.getCurrency());
                log.info("recovered top-up {} -> {}", t.getId(), after.getStatus());
            } catch (RuntimeException e) {
                log.warn("top-up {} still unresolved: {}", t.getId(), e.toString());
            }
        }
    }
}
