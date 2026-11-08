package com.payflow.domain.ledger;

import com.payflow.domain.DomainException;

/**
 * Pure rule applied to each locked account before its balance changes.
 * The database CHECK constraint is the last line of defence; this gives a clean business error first.
 */
public final class BalanceRules {

    private BalanceRules() {
    }

    public static long applyDelta(long currentBalance, long delta, boolean allowNegative, String accountLabel) {
        long next = Math.addExact(currentBalance, delta);
        if (next < 0 && !allowNegative) {
            throw new DomainException.InsufficientFunds(
                    "account " + accountLabel + " has insufficient funds (balance=" + currentBalance
                            + ", requested change=" + delta + ")");
        }
        return next;
    }
}
