package com.payflow.domain.ledger;

import com.payflow.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * One line of a journal entry: the signed change to an account's balance.
 * Positive = balance increases, negative = balance decreases.
 */
public record LedgerLine(UUID accountId, Money amount) {

    public LedgerLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
    }

    public static LedgerLine of(UUID accountId, long minor, String currency) {
        return new LedgerLine(accountId, Money.of(minor, currency));
    }
}
