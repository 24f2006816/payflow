package com.payflow.domain.ledger;

import com.payflow.domain.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BalanceRulesTest {

    @Test
    void appliesDeltaWithinBalance() {
        assertEquals(0, BalanceRules.applyDelta(100, -100, false, "w"));
        assertEquals(150, BalanceRules.applyDelta(100, 50, false, "w"));
    }

    @Test
    void rejectsOverdraftForNormalAccounts() {
        assertThrows(DomainException.InsufficientFunds.class, () -> BalanceRules.applyDelta(100, -101, false, "w"));
    }

    @Test
    void allowsNegativeForClearingAccounts() {
        assertEquals(-500, BalanceRules.applyDelta(0, -500, true, "psp"));
    }
}
