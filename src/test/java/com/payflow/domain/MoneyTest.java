package com.payflow.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    @DisplayName("adds and subtracts amounts in the same currency")
    void arithmetic() {
        Money a = Money.of(1_050, "INR");
        Money b = Money.of(50, "INR");
        assertEquals(Money.of(1_100, "INR"), a.plus(b));
        assertEquals(Money.of(1_000, "INR"), a.minus(b));
        assertEquals(Money.of(-1_050, "INR"), a.negate());
    }

    @Test
    @DisplayName("refuses to mix currencies")
    void currencyMismatch() {
        assertThrows(DomainException.CurrencyMismatch.class, () -> Money.of(1, "INR").plus(Money.of(1, "USD")));
    }

    @Test
    @DisplayName("rejects invalid currency codes")
    void invalidCurrency() {
        assertThrows(DomainException.InvalidAmount.class, () -> Money.of(1, "inr"));
        assertThrows(DomainException.InvalidAmount.class, () -> Money.of(1, "RUPEE"));
        assertThrows(DomainException.InvalidAmount.class, () -> Money.of(1, null));
    }

    @Test
    @DisplayName("positive() rejects zero and negative amounts")
    void positiveOnly() {
        assertThrows(DomainException.InvalidAmount.class, () -> Money.positive(0, "INR"));
        assertThrows(DomainException.InvalidAmount.class, () -> Money.positive(-5, "INR"));
        assertTrue(Money.positive(1, "INR").isPositive());
    }

    @Test
    @DisplayName("detects long overflow instead of wrapping around")
    void overflow() {
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MAX_VALUE, "INR").plus(Money.of(1, "INR")));
    }
}
