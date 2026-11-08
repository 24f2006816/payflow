package com.payflow.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    /**
     * Row lock on the payment: serialises capture / cancel / refund of the same payment.
     * Lock order across the codebase is always: payment row first, then account rows (via LedgerService).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Served by ix_payments_merchant_created (merchant_id, created_at DESC, id DESC). */
    Page<PaymentEntity> findByMerchantId(UUID merchantId, Pageable pageable);
}
