package com.payflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes payment events from Kafka and turns merchant-facing ones into webhook deliveries.
 * <p>
 * Kafka delivery is at-least-once, so the consumer is idempotent: the event id is recorded in
 * processed_events in the same DB transaction as the webhook row. A redelivered event finds its id
 * already there and is skipped. Offsets are committed after the listener returns (after the DB commit),
 * so a crash in between causes a redelivery, never a lost event.
 */
@Component
public class PaymentEventConsumer {

    static final String CONSUMER = "webhook-fanout";
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${payflow.outbox.topic}", groupId = "payflow-webhooks")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode event = objectMapper.readTree(record.value());
        UUID eventId = UUID.fromString(event.path("id").asText());

        int fresh = jdbc.update("INSERT INTO processed_events (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                CONSUMER, eventId);
        if (fresh == 0) {
            log.debug("duplicate event {} skipped", eventId);
            return;
        }
        JsonNode merchant = event.path("merchantId");
        if (merchant.isMissingNode() || merchant.isNull()) {
            return; // not a merchant-facing event (e.g. top-ups)
        }
        jdbc.update("""
                INSERT INTO webhook_deliveries (merchant_id, event_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (merchant_id, event_id) DO NOTHING
                """, UUID.fromString(merchant.asText()), eventId, event.path("type").asText(), record.value());
    }
}
