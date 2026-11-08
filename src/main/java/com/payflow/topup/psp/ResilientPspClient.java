package com.payflow.topup.psp;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

/**
 * Wraps the provider with Resilience4j (configured under resilience4j.* in application.yml).
 * <ul>
 *   <li>Retry: up to 3 attempts with exponential backoff, only for PspUnavailableException. Safe ONLY because
 *       the provider call is idempotent by reference.</li>
 *   <li>CircuitBreaker: after 50% failures in the last 20 calls, fail fast for 15s instead of piling up
 *       threads waiting on a dead provider (prevents cascading failure).</li>
 * </ul>
 * Annotation order: Retry is the outer aspect, so each retry attempt is recorded by the circuit breaker.
 */
@Component
public class ResilientPspClient {

    private final PaymentServiceProvider provider;

    public ResilientPspClient(PaymentServiceProvider provider) {
        this.provider = provider;
    }

    @Retry(name = "psp")
    @CircuitBreaker(name = "psp")
    public PaymentServiceProvider.ChargeResult charge(String reference, long amountMinor, String currency) {
        return provider.charge(reference, amountMinor, currency);
    }
}
