package com.payflow.domain.payment;

import com.payflow.domain.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Platform fee in basis points (1 bp = 0.01%), rounded HALF_UP to the minor unit.
 * Example: 200 bps on 10,050 paise = 201 paise.
 */
public final class FeeCalculator {

    private static final BigDecimal BPS_DIVISOR = BigDecimal.valueOf(10_000);
    private final int feeBps;

    public FeeCalculator(int feeBps) {
        if (feeBps < 0 || feeBps > 10_000) {
            throw new IllegalArgumentException("feeBps must be within [0, 10000], got " + feeBps);
        }
        this.feeBps = feeBps;
    }

    public long feeFor(long amountMinor) {
        if (amountMinor <= 0) {
            throw new DomainException.InvalidAmount("fee base must be > 0, got " + amountMinor);
        }
        return BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(feeBps))
                .divide(BPS_DIVISOR, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public int feeBps() {
        return feeBps;
    }
}
