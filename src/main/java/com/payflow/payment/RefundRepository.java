package com.payflow.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<RefundEntity, UUID> {

    Optional<RefundEntity> findByPaymentIdAndIdempotencyKey(UUID paymentId, String idempotencyKey);

    List<RefundEntity> findByPaymentIdOrderByCreatedAt(UUID paymentId);
}
