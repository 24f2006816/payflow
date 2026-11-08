package com.payflow.domain.payment;

import com.payflow.domain.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentRulesTest {

    @Test
    @DisplayName("full capture when no amount is given")
    void fullCapture() {
        assertEquals(1_000, PaymentRules.resolveCaptureAmount(PaymentStatus.AUTHORIZED, 1_000, null));
    }

    @Test
    @DisplayName("partial capture within the authorized amount")
    void partialCapture() {
        assertEquals(400, PaymentRules.resolveCaptureAmount(PaymentStatus.AUTHORIZED, 1_000, 400L));
    }

    @Test
    @DisplayName("cannot capture more than authorized, zero, or twice")
    void invalidCaptures() {
        assertThrows(DomainException.InvalidAmount.class,
                () -> PaymentRules.resolveCaptureAmount(PaymentStatus.AUTHORIZED, 1_000, 1_001L));
        assertThrows(DomainException.InvalidAmount.class,
                () -> PaymentRules.resolveCaptureAmount(PaymentStatus.AUTHORIZED, 1_000, 0L));
        assertThrows(DomainException.InvalidStateTransition.class,
                () -> PaymentRules.resolveCaptureAmount(PaymentStatus.CAPTURED, 1_000, null));
        assertThrows(DomainException.InvalidStateTransition.class,
                () -> PaymentRules.resolveCaptureAmount(PaymentStatus.CANCELED, 1_000, null));
    }

    @Test
    @DisplayName("cancel only from AUTHORIZED")
    void cancel() {
        PaymentRules.validateCancel(PaymentStatus.AUTHORIZED);
        assertThrows(DomainException.InvalidStateTransition.class,
                () -> PaymentRules.validateCancel(PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("partial then full refund moves PARTIALLY_REFUNDED -> REFUNDED")
    void refunds() {
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED,
                PaymentRules.statusAfterRefund(PaymentStatus.CAPTURED, 1_000, 0, 300));
        assertEquals(PaymentStatus.REFUNDED,
                PaymentRules.statusAfterRefund(PaymentStatus.PARTIALLY_REFUNDED, 1_000, 300, 700));
    }

    @Test
    @DisplayName("cannot refund more than captured minus already refunded")
    void overRefund() {
        assertThrows(DomainException.InvalidAmount.class,
                () -> PaymentRules.statusAfterRefund(PaymentStatus.PARTIALLY_REFUNDED, 1_000, 300, 701));
        assertThrows(DomainException.InvalidStateTransition.class,
                () -> PaymentRules.statusAfterRefund(PaymentStatus.AUTHORIZED, 1_000, 0, 1));
        assertThrows(DomainException.InvalidStateTransition.class,
                () -> PaymentRules.statusAfterRefund(PaymentStatus.REFUNDED, 1_000, 1_000, 1));
    }

    @Test
    @DisplayName("state machine terminal states")
    void stateMachine() {
        assertTrue(PaymentStatus.FAILED.isTerminal());
        assertTrue(PaymentStatus.CANCELED.isTerminal());
        assertTrue(PaymentStatus.REFUNDED.isTerminal());
        assertFalse(PaymentStatus.AUTHORIZED.isTerminal());
        assertFalse(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.REFUNDED));
    }
}
