package com.payflow.account;

import com.payflow.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity extends AssignedIdEntity {

    public enum OwnerType { CUSTOMER, MERCHANT, PLATFORM }

    public enum AccountType { CUSTOMER_WALLET, MERCHANT_BALANCE, PLATFORM_FEES, PLATFORM_HOLDS, PSP_CLEARING }

    @Id
    private UUID id;

    @Column(name = "owner_type", nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private OwnerType ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "account_type", nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Column(nullable = false)
    private String status;

    /** Materialised balance; only LedgerService may change it, always under a row lock. */
    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    public static AccountEntity open(OwnerType ownerType, UUID ownerId, AccountType type, String currency) {
        AccountEntity a = new AccountEntity();
        a.id = UUID.randomUUID();
        a.ownerType = ownerType;
        a.ownerId = ownerId;
        a.accountType = type;
        a.currency = currency;
        a.allowNegative = false;
        a.status = "ACTIVE";
        return a;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Only LedgerService may call this, while holding the row lock and inside the transaction that
     * inserts the matching postings. (Public only because the ledger lives in another package.)
     */
    public void applyLedgerBalance(long newBalanceMinor) {
        this.balanceMinor = newBalanceMinor;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    @Override
    public UUID getId() { return id; }
    public OwnerType getOwnerType() { return ownerType; }
    public UUID getOwnerId() { return ownerId; }
    public AccountType getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public boolean isAllowNegative() { return allowNegative; }
    public String getStatus() { return status; }
    public long getBalanceMinor() { return balanceMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
