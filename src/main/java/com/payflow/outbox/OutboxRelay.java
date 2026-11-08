package com.payflow.outbox;

import com.payflow.config.PayflowProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transactional outbox, read side ("polling publisher").
 * <p>
 * Each poll claims a batch with {@code FOR UPDATE SKIP LOCKED}, so several application instances can run
 * the relay concurrently without publishing the same row twice at the same time and without blocking each
 * other. Rows are marked published only after Kafka acknowledges (acks=all, idempotent producer).
 * <p>
 * Guarantee: at-least-once. If the process dies after Kafka acked but before the UPDATE commits, the event
 * is re-sent. Consumers therefore de-duplicate on the event id (see processed_events).
 * <p>
 * Ordering: events are keyed by aggregate id (same Kafka partition) and published in created_at order;
 * a failure stops the batch so a later event of the same payment cannot overtake an earlier one.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final PayflowProperties props;
    private final Counter published;
    private final Counter failures;
    private final AtomicLong backlog = new AtomicLong();

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       PayflowProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.props = props;
        this.published = Counter.builder("payflow.outbox.published").register(meters);
        this.failures = Counter.builder("payflow.outbox.publish_failures").register(meters);
        Gauge.builder("payflow.outbox.backlog", backlog, AtomicLong::get)
                .description("unpublished outbox events").register(meters);
    }

    private record Row(UUID id, UUID aggregateId, String payload) {
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval}")
    public void relay() {
        Integer sent = tx.execute(status -> publishBatch());
        if (sent != null && sent == props.outbox().batchSize()) {
            log.debug("outbox batch full, more events pending");
        }
        Long pending = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        backlog.set(pending == null ? 0 : pending);
    }

    private int publishBatch() {
        List<Row> rows = jdbc.query("""
                        SELECT id, aggregate_id, payload::text AS payload
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload")),
                props.outbox().batchSize());
        int sent = 0;
        for (Row row : rows) {
            try {
                kafka.send(props.outbox().topic(), row.aggregateId().toString(), row.payload()).get(10, TimeUnit.SECONDS);
                jdbc.update("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = ?",
                        row.id());
                published.increment();
                sent++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.increment();
                log.warn("outbox publish failed for event {}: {}", row.id(), e.toString());
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        truncate(e.toString()), row.id());
                break; // keep ordering: retry this row first on the next poll
            }
        }
        return sent;
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
