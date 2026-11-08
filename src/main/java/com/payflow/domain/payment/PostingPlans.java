package com.payflow.domain.payment;

import com.payflow.domain.ledger.JournalDraft;

import java.util.UUID;

/**
 * Turns each money movement into a balanced journal entry. This is the single place that decides
 * which accounts move and by how much, so it is the most important class to unit test.
 *
 * <pre>
 * Top-up      : PSP_CLEARING -T, customer +T
 * Authorize   : customer -A, HOLDS +A                      (funds reserved)
 * Capture C   : HOLDS -A, merchant +(C-F), FEES +F, customer +(A-C)   (remainder released)
 * Cancel      : HOLDS -A, customer +A
 * Refund R    : merchant -R, customer +R                   (fee is not returned to the merchant)
 * </pre>
 */
public final class PostingPlans {

    public static final String REF_PAYMENT = "PAYMENT";
    public static final String REF_TOPUP = "TOPUP";
    public static final String REF_REFUND = "REFUND";

    private PostingPlans() {
    }

    public static JournalDraft topup(UUID topupId, UUID pspClearingAccount, UUID customerWallet,
                                     long amount, String currency) {
        return JournalDraft.builder("TOPUP", REF_TOPUP, topupId)
                .description("Wallet top-up")
                .line(pspClearingAccount, -amount, currency)
                .line(customerWallet, amount, currency)
                .build();
    }

    public static JournalDraft authorize(UUID paymentId, UUID customerWallet, UUID holdsAccount,
                                         long amount, String currency) {
        return JournalDraft.builder("PAYMENT_AUTHORIZE", REF_PAYMENT, paymentId)
                .description("Authorize payment")
                .line(customerWallet, -amount, currency)
                .line(holdsAccount, amount, currency)
                .build();
    }

    public static JournalDraft capture(UUID paymentId, UUID holdsAccount, UUID merchantAccount, UUID feesAccount,
                                       UUID customerWallet, long authorized, long captured, long fee,
                                       String currency) {
        if (fee > captured) {
            throw new IllegalArgumentException("fee " + fee + " exceeds captured amount " + captured);
        }
        return JournalDraft.builder("PAYMENT_CAPTURE", REF_PAYMENT, paymentId)
                .description("Capture payment")
                .line(holdsAccount, -authorized, currency)
                .line(merchantAccount, captured - fee, currency)
                .line(feesAccount, fee, currency)
                .line(customerWallet, authorized - captured, currency)
                .build();
    }

    public static JournalDraft cancel(UUID paymentId, UUID holdsAccount, UUID customerWallet,
                                      long authorized, String currency) {
        return JournalDraft.builder("PAYMENT_CANCEL", REF_PAYMENT, paymentId)
                .description("Cancel authorization")
                .line(holdsAccount, -authorized, currency)
                .line(customerWallet, authorized, currency)
                .build();
    }

    public static JournalDraft refund(UUID refundId, UUID merchantAccount, UUID customerWallet,
                                      long amount, String currency) {
        return JournalDraft.builder("PAYMENT_REFUND", REF_REFUND, refundId)
                .description("Refund payment")
                .line(merchantAccount, -amount, currency)
                .line(customerWallet, amount, currency)
                .build();
    }
}
