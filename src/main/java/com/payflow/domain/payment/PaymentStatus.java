package com.payflow.domain.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Payment lifecycle as an explicit state machine.
 *
 * <pre>
 *   AUTHORIZED --capture--> CAPTURED --refund--> PARTIALLY_REFUNDED --refund--> REFUNDED
 *       |                       \------------------- refund (full) ----------------^
 *       \--cancel--> CANCELED
 *   FAILED (terminal, e.g. insufficient funds at authorization)
 * </pre>
 */
public enum PaymentStatus {
    AUTHORIZED,
    FAILED,
    CAPTURED,
    CANCELED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case AUTHORIZED -> EnumSet.of(CAPTURED, CANCELED);
            case CAPTURED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
            case FAILED, CANCELED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
