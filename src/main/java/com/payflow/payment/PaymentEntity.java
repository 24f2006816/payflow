package com.payflow.payment;

import com.payflow.common.AssignedIdEntity;
import com.payflow.domain.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class PaymentEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "customer_account_id", nullable = false, updatable = false)
    private UUID customerAccountId;

    @Column(name = "merchant_account_id", nullable = false, updatable = false)
    private UUID merchantAccountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "captured_minor", nullable = false)
    private long capturedMinor;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    private String description;

    /** Optimistic lock: a second guard on top of the row lock taken by findByIdForUpdate. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {
    }

    public static PaymentEntity newPayment(UUID id, String clientId, String idempotencyKey, UUID merchantId,
                                           UUID customerAccountId, UUID merchantAccountId, long amountMinor,
                                           String currency, String description) {
        PaymentEntity p = new PaymentEntity();
        p.id = id;
        p.clientId = clientId;
        p.idempotencyKey = idempotencyKey;
        p.merchantId = merchantId;
        p.customerAccountId = customerAccountId;
        p.merchantAccountId = merchantAccountId;
        p.amountMinor = amountMinor;
        p.currency = currency;
        p.description = description;
        p.status = PaymentStatus.AUTHORIZED;
        return p;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    void markCaptured(long captured, long fee) {
        this.status = PaymentStatus.CAPTURED;
        this.capturedMinor = captured;
        this.feeMinor = fee;
    }

    void markCanceled() {
        this.status = PaymentStatus.CANCELED;
    }

    void applyRefund(long amount, PaymentStatus next) {
        this.refundedMinor = Math.addExact(this.refundedMinor, amount);
        this.status = next;
    }

    @Override
    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCustomerAccountId() { return customerAccountId; }
    public UUID getMerchantAccountId() { return merchantAccountId; }
    public long getAmountMinor() { return amountMinor; }
    public long getCapturedMinor() { return capturedMinor; }
    public long getRefundedMinor() { return refundedMinor; }
    public long getFeeMinor() { return feeMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
