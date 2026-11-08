package com.payflow.payment;

import com.payflow.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "refunds")
public class RefundEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundEntity() {
    }

    public RefundEntity(UUID id, UUID paymentId, String idempotencyKey, long amountMinor, String reason) {
        this.id = id;
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.amountMinor = amountMinor;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getAmountMinor() { return amountMinor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
