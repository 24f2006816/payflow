package com.payflow.idempotency;

import com.payflow.config.PayflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

/** Keys are kept for a retention window (24h by default, like Stripe), then deleted in small batches. */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final PayflowProperties props;
    private final Clock clock;

    public IdempotencyCleanupJob(JdbcTemplate jdbc, PayflowProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void purgeExpired() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(props.idempotency().retention()));
        int deleted = jdbc.update("""
                DELETE FROM idempotency_keys WHERE ctid IN (
                  SELECT ctid FROM idempotency_keys WHERE created_at < ? LIMIT 5000)
                """, cutoff);
        if (deleted > 0) {
            log.info("purged {} expired idempotency keys", deleted);
        }
    }
}
