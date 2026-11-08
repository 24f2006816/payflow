package com.payflow.topup.psp;

import com.payflow.config.PayflowProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-process stand-in for a real provider, so the whole system runs offline and failure modes can be tested.
 * <ul>
 *   <li>Idempotent by reference: charging the same reference twice returns the first result.</li>
 *   <li>Amounts whose last two minor digits are 13 (e.g. 10013) are DECLINED, like a test card number.</li>
 *   <li>{@code payflow.psp.transient-failure-rate} injects timeouts (PspUnavailableException).</li>
 *   <li>{@code payflow.psp.latency} simulates network latency.</li>
 * </ul>
 * Replace with an HTTP adapter for a real provider; nothing else in the code base changes.
 */
@Component
public class SimulatedPaymentServiceProvider implements PaymentServiceProvider {

    private final PayflowProperties.Psp config;
    private final Map<String, ChargeResult> charges = new ConcurrentHashMap<>();

    public SimulatedPaymentServiceProvider(PayflowProperties props) {
        this.config = props.psp();
    }

    @Override
    public ChargeResult charge(String reference, long amountMinor, String currency) {
        sleep();
        if (ThreadLocalRandom.current().nextDouble() < config.transientFailureRate()) {
            throw new PspUnavailableException("simulated provider timeout for " + reference);
        }
        return charges.computeIfAbsent(reference, ref -> amountMinor % 100 == 13
                ? new ChargeResult(Outcome.DECLINED, "psp_" + UUID.randomUUID(), "card_declined")
                : new ChargeResult(Outcome.SUCCEEDED, "psp_" + UUID.randomUUID(), null));
    }

    private void sleep() {
        long millis = config.latency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PspUnavailableException("interrupted");
        }
    }
}
