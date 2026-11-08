package com.payflow.topup;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TopupRepository extends JpaRepository<TopupEntity, UUID> {

    Optional<TopupEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopupEntity t where t.id = :id")
    Optional<TopupEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Served by the partial index ix_topups_pending. */
    List<TopupEntity> findByStatusAndCreatedAtBeforeOrderByCreatedAt(TopupEntity.Status status, Instant before,
                                                                      Limit limit);
}
