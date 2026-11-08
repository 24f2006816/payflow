package com.payflow.domain.payment;

import com.payflow.domain.ledger.JournalDraft;
import com.payflow.domain.ledger.LedgerLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostingPlansTest {

    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private final UUID holds = UUID.randomUUID();
    private final UUID fees = UUID.randomUUID();
    private final UUID clearing = UUID.randomUUID();
    private final UUID ref = UUID.randomUUID();

    private static Map<UUID, Long> deltas(JournalDraft draft) {
        Map<UUID, Long> m = new HashMap<>();
        for (LedgerLine l : draft.lines()) {
            m.merge(l.accountId(), l.amount().minor(), Long::sum);
        }
        return m;
    }

    @Test
    @DisplayName("top-up moves money from PSP clearing into the wallet")
    void topup() {
        Map<UUID, Long> d = deltas(PostingPlans.topup(ref, clearing, customer, 5_000, "INR"));
        assertEquals(-5_000L, d.get(clearing));
        assertEquals(5_000L, d.get(customer));
    }

    @Test
    @DisplayName("authorize reserves funds in the holds account")
    void authorize() {
        Map<UUID, Long> d = deltas(PostingPlans.authorize(ref, customer, holds, 1_000, "INR"));
        assertEquals(-1_000L, d.get(customer));
        assertEquals(1_000L, d.get(holds));
    }

    @Test
    @DisplayName("full capture with 2% fee: merchant gets amount minus fee, platform gets fee")
    void fullCapture() {
        long fee = new FeeCalculator(200).feeFor(1_000);
        Map<UUID, Long> d = deltas(PostingPlans.capture(ref, holds, merchant, fees, customer, 1_000, 1_000, fee, "INR"));
        assertEquals(-1_000L, d.get(holds));
        assertEquals(980L, d.get(merchant));
        assertEquals(20L, d.get(fees));
        assertFalse(d.containsKey(customer), "nothing released on full capture");
    }

    @Test
    @DisplayName("partial capture releases the remainder back to the customer")
    void partialCapture() {
        Map<UUID, Long> d = deltas(PostingPlans.capture(ref, holds, merchant, fees, customer, 1_000, 600, 12, "INR"));
        assertEquals(-1_000L, d.get(holds));
        assertEquals(588L, d.get(merchant));
        assertEquals(12L, d.get(fees));
        assertEquals(400L, d.get(customer));
    }

    @Test
    @DisplayName("zero fee produces no fee posting")
    void zeroFee() {
        JournalDraft draft = PostingPlans.capture(ref, holds, merchant, fees, customer, 1_000, 1_000, 0, "INR");
        assertEquals(2, draft.lines().size());
    }

    @Test
    @DisplayName("fee larger than captured amount is a programming error")
    void feeTooLarge() {
        assertThrows(IllegalArgumentException.class,
                () -> PostingPlans.capture(ref, holds, merchant, fees, customer, 1_000, 10, 11, "INR"));
    }

    @Test
    @DisplayName("cancel and refund move money back to the customer")
    void cancelAndRefund() {
        Map<UUID, Long> cancel = deltas(PostingPlans.cancel(ref, holds, customer, 1_000, "INR"));
        assertEquals(-1_000L, cancel.get(holds));
        assertEquals(1_000L, cancel.get(customer));
        Map<UUID, Long> refund = deltas(PostingPlans.refund(ref, merchant, customer, 300, "INR"));
        assertEquals(-300L, refund.get(merchant));
        assertEquals(300L, refund.get(customer));
    }
}
