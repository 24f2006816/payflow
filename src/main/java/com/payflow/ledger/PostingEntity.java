package com.payflow.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "postings")
public class PostingEntity {

    @Id
    // SEQUENCE (not IDENTITY): IDENTITY forces an immediate INSERT at persist(), before the parent
    // journal entry is flushed, and disables JDBC batching. postings_id_seq is created by BIGSERIAL.
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "postings_seq")
    @SequenceGenerator(name = "postings_seq", sequenceName = "postings_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PostingEntity() {
    }

    public PostingEntity(UUID journalEntryId, UUID accountId, long amountMinor, String currency, Instant createdAt) {
        this.journalEntryId = journalEntryId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getAccountId() { return accountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
