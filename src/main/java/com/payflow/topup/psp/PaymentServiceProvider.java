package com.payflow.topup.psp;

/**
 * Port to an external payment provider (Razorpay, Stripe, Adyen...). The reference we pass is OUR id for the
 * charge and doubles as the provider-side idempotency key, which is what makes retries safe.
 */
public interface PaymentServiceProvider {

    enum Outcome { SUCCEEDED, DECLINED }

    record ChargeResult(Outcome outcome, String pspReference, String declineReason) {
    }

    /**
     * @throws PspUnavailableException on timeouts / 5xx: outcome UNKNOWN, safe to retry with the same reference.
     */
    ChargeResult charge(String reference, long amountMinor, String currency);
}
