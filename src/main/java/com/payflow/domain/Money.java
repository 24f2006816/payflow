package com.payflow.domain;

import java.util.regex.Pattern;

/**
 * An amount of money in minor units (paise, cents) of an ISO-4217 currency.
 * <p>
 * Why {@code long} minor units and not {@code double} or {@code BigDecimal}? Floating point cannot
 * represent 0.10 exactly, and BigDecimal invites accidental scale bugs. Integer minor units are what
 * Stripe, Razorpay and most ledgers use. All arithmetic is overflow-checked.
 */
public record Money(long minor, String currency) {

    private static final Pattern ISO_CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Money {
        if (currency == null || !ISO_CURRENCY.matcher(currency).matches()) {
            throw new DomainException.InvalidAmount("currency must be a 3-letter ISO-4217 code, got: " + currency);
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    /** A strictly positive amount, as required for every request amount. */
    public static Money positive(long minor, String currency) {
        if (minor <= 0) {
            throw new DomainException.InvalidAmount("amount must be > 0, got: " + minor);
        }
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean greaterThan(Money other) {
        requireSameCurrency(other);
        return minor > other.minor;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new DomainException.CurrencyMismatch(currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return minor + " " + currency;
    }
}
