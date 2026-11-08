package com.payflow.ledger;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Both statement queries are served by index ix_postings_account_id_desc (account_id, id DESC). */
public interface PostingRepository extends JpaRepository<PostingEntity, Long> {

    List<PostingEntity> findByAccountIdOrderByIdDesc(UUID accountId, Limit limit);

    List<PostingEntity> findByAccountIdAndIdLessThanOrderByIdDesc(UUID accountId, Long id, Limit limit);

    List<PostingEntity> findByJournalEntryId(UUID journalEntryId);
}
