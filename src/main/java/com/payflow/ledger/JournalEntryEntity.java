package com.payflow.ledger;

import com.payflow.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntryEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JournalEntryEntity() {
    }

    public JournalEntryEntity(UUID id, String entryType, String referenceType, UUID referenceId, String description,
                              Instant createdAt) {
        this.id = id;
        this.entryType = entryType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.description = description;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() { return id; }
    public String getEntryType() { return entryType; }
    public String getReferenceType() { return referenceType; }
    public UUID getReferenceId() { return referenceId; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
