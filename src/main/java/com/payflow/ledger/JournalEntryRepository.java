package com.payflow.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, UUID> {

    List<JournalEntryEntity> findByReferenceTypeAndReferenceIdOrderByCreatedAt(String referenceType, UUID referenceId);
}
