package com.payflow.domain.payment;

import com.payflow.domain.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeeCalculatorTest {

    @Test
    void roundsHalfUpToMinorUnit() {
        FeeCalculator twoPercent = new FeeCalculator(200);
        assertEquals(20, twoPercent.feeFor(1_000));
        assertEquals(201, twoPercent.feeFor(10_050));   // 201.0
        assertEquals(1, twoPercent.feeFor(25));         // 0.5 -> 1
        assertEquals(0, twoPercent.feeFor(24));         // 0.48 -> 0
    }

    @Test
    void zeroBpsMeansNoFee() {
        assertEquals(0, new FeeCalculator(0).feeFor(1_000_000));
    }

    @Test
    void rejectsInvalidConfigurationAndAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new FeeCalculator(-1));
        assertThrows(IllegalArgumentException.class, () -> new FeeCalculator(10_001));
        assertThrows(DomainException.InvalidAmount.class, () -> new FeeCalculator(200).feeFor(0));
    }
}
