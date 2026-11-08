package com.payflow;

import com.payflow.domain.payment.PaymentStatus;
import com.payflow.payment.PaymentEntity;
import com.payflow.payment.PaymentService;
import com.payflow.reconciliation.ReconciliationService;
import com.payflow.security.CurrentClient;
import com.payflow.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that justify the locking design. They fire truly concurrent requests and assert invariants.
 */
class ConcurrencyIT extends IntegrationTest {

    @Autowired
    PaymentService paymentService;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    TestRestTemplate http;

    private static final CurrentClient SERVICE = new CurrentClient("checkout-service", Set.of("SERVICE"), null);

    @Test
    @DisplayName("50 concurrent 100-paise authorizations against a 2,000-paise wallet: exactly 20 succeed, no overdraft")
    void noDoubleSpend() throws Exception {
        UUID wallet = fundedWallet(2_000);
        int attempts = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<PaymentEntity>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String key = "conc-" + UUID.randomUUID();
            tasks.add(() -> {
                start.await();
                return paymentService.authorize(SERVICE, key,
                        new PaymentService.CreatePayment(ACME_MERCHANT_ID, wallet, 100, "INR", null));
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<PaymentEntity>> futures = new ArrayList<>();
            for (var t : tasks) {
                futures.add(pool.submit(t));
            }
            start.countDown();
            int authorized = 0;
            int failed = 0;
            for (var f : futures) {
                PaymentStatus s = f.get().getStatus();
                if (s == PaymentStatus.AUTHORIZED) {
                    authorized++;
                } else if (s == PaymentStatus.FAILED) {
                    failed++;
                }
            }
            assertThat(authorized).isEqualTo(20);
            assertThat(failed).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(wallet)).isZero();
        assertThat(reconciliation.run()).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("PASSED"));
    }

    @Test
    @DisplayName("10 concurrent HTTP requests with the SAME Idempotency-Key create exactly one payment")
    void concurrentSameKey() throws Exception {
        UUID wallet = fundedWallet(10_000);
        String token = serviceToken();
        String key = newKey();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        var request = new HttpEntity<>(Map.of("merchantId", ACME_MERCHANT_ID, "customerAccountId", wallet,
                "amountMinor", 1_000, "currency", "INR"), headers);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return http.postForEntity("/v1/payments", request, String.class);
                }));
            }
            start.countDown();
            Set<String> paymentIds = new java.util.HashSet<>();
            for (var f : futures) {
                ResponseEntity<String> r = f.get();
                assertThat(r.getStatusCode().value()).isIn(201, 409);
                if (r.getStatusCode().value() == 201) {
                    paymentIds.add(json.readTree(r.getBody()).get("id").asText());
                }
            }
            assertThat(paymentIds).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(wallet)).isEqualTo(9_000);
    }

    @Test
    @DisplayName("concurrent refunds of the same payment never exceed the captured amount or merchant funds")
    void concurrentRefunds() throws Exception {
        TestMerchant merchant = newMerchant(null);
        UUID wallet = fundedWallet(10_000);
        PaymentEntity p = paymentService.authorize(SERVICE, newKey(),
                new PaymentService.CreatePayment(merchant.id(), wallet, 5_000, "INR", null));
        paymentService.capture(SERVICE, p.getId(), null); // merchant receives 5,000 - 100 fee = 4,900

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String key = newKey();
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        paymentService.refund(SERVICE, p.getId(), key, 980, "test");
                        return true;
                    } catch (RuntimeException rejected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long ok = 0;
            for (var f : futures) {
                if (f.get()) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(5); // 5 x 980 = 4,900 = everything the merchant holds
        } finally {
            pool.shutdownNow();
        }
        assertThat(paymentService.get(SERVICE, p.getId()).getRefundedMinor()).isEqualTo(4_900);
        assertThat(balance(merchant.inrAccount())).isZero();
        assertThat(reconciliation.run()).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("PASSED"));
    }
}
