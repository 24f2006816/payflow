package com.payflow.topup;

import com.payflow.common.AssignedIdEntity;
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
@Table(name = "topups")
public class TopupEntity extends AssignedIdEntity {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "psp_reference")
    private String pspReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TopupEntity() {
    }

    public static TopupEntity pending(String clientId, String idempotencyKey, UUID accountId, long amountMinor,
                                      String currency) {
        TopupEntity t = new TopupEntity();
        t.id = UUID.randomUUID();
        t.clientId = clientId;
        t.idempotencyKey = idempotencyKey;
        t.accountId = accountId;
        t.amountMinor = amountMinor;
        t.currency = currency;
        t.status = Status.PENDING;
        return t;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    void succeed(String pspReference) {
        this.status = Status.SUCCEEDED;
        this.pspReference = pspReference;
    }

    void fail(String pspReference, String reason) {
        this.status = Status.FAILED;
        this.pspReference = pspReference;
        this.failureReason = reason;
    }

    @Override
    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public UUID getAccountId() { return accountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getPspReference() { return pspReference; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
