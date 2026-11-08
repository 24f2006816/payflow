package com.payflow.domain.payment;

import com.payflow.domain.DomainException;

/**
 * Pure business rules for payment operations. The service layer loads the payment under a row lock,
 * asks these rules what is allowed, then persists the result. Keeping them pure makes every edge case
 * unit-testable without a database.
 */
public final class PaymentRules {

    private PaymentRules() {
    }

    /** Resolves the capture amount (null = capture everything authorised) and validates it. */
    public static long resolveCaptureAmount(PaymentStatus status, long authorizedMinor, Long requestedMinor) {
        requireTransition(status, PaymentStatus.CAPTURED, "capture");
        long capture = requestedMinor == null ? authorizedMinor : requestedMinor;
        if (capture <= 0) {
            throw new DomainException.InvalidAmount("capture amount must be > 0");
        }
        if (capture > authorizedMinor) {
            throw new DomainException.InvalidAmount(
                    "capture amount " + capture + " exceeds authorized amount " + authorizedMinor);
        }
        return capture;
    }

    public static void validateCancel(PaymentStatus status) {
        requireTransition(status, PaymentStatus.CANCELED, "cancel");
    }

    /** Validates a refund and returns the status the payment moves to. */
    public static PaymentStatus statusAfterRefund(PaymentStatus status, long capturedMinor, long alreadyRefundedMinor,
                                                  long refundMinor) {
        if (status != PaymentStatus.CAPTURED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new DomainException.InvalidStateTransition("cannot refund a payment in status " + status);
        }
        if (refundMinor <= 0) {
            throw new DomainException.InvalidAmount("refund amount must be > 0");
        }
        long refundable = capturedMinor - alreadyRefundedMinor;
        if (refundMinor > refundable) {
            throw new DomainException.InvalidAmount(
                    "refund amount " + refundMinor + " exceeds refundable amount " + refundable);
        }
        return refundMinor == refundable ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    private static void requireTransition(PaymentStatus from, PaymentStatus to, String operation) {
        if (!from.canTransitionTo(to)) {
            throw new DomainException.InvalidStateTransition("cannot " + operation + " a payment in status " + from);
        }
    }
}
