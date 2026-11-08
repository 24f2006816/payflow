package com.payflow.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox, write side. The event row is inserted in the SAME database transaction as the
 * business change, so "payment captured" is recorded if and only if the capture commits. A separate
 * relay publishes it to Kafka (at-least-once). This avoids the dual-write problem
 * (DB commit succeeds, Kafka send fails, or vice versa) without distributed transactions.
 */
@Component
public class OutboxWriter {

    public static final int SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Event envelope (consumed by webhook delivery here and by the analytics platform in project 3):
     * {"id","type","schemaVersion","occurredAt","aggregateType","aggregateId","merchantId","data":{...}}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, UUID aggregateId, String eventType, UUID merchantId,
                       Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", eventType);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", now.toString());
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("merchantId", merchantId);
        envelope.put("data", data);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize outbox event " + eventType, e);
        }
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, eventId, aggregateType, aggregateId, eventType, json, java.sql.Timestamp.from(now));
        return eventId;
    }
}
