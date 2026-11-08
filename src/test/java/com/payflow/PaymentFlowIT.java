package com.payflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end money flow through the public API, asserting balances after every step. */
class PaymentFlowIT extends IntegrationTest {

    @Test
    @DisplayName("top-up -> authorize -> partial capture -> refunds; balances exact; refund limited by merchant funds")
    void fullLifecycle() throws Exception {
        String svc = serviceToken();
        TestMerchant merchant = newMerchant(null);
        UUID wallet = fundedWallet(100_000);
        assertThat(balance(wallet)).isEqualTo(100_000);

        // Authorize 10,000: funds move from wallet to holds.
        JsonNode payment = read(postJson("/v1/payments", svc, newKey(), Map.of(
                "merchantId", merchant.id(), "customerAccountId", wallet,
                "amountMinor", 10_000, "currency", "INR", "description", "order #42"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED")));
        String paymentId = payment.get("id").asText();
        assertThat(balance(wallet)).isEqualTo(90_000);

        // Capture 6,000 of 10,000: 2% fee = 120; remaining 4,000 released to the wallet.
        postJson("/v1/payments/" + paymentId + "/capture", svc, newKey(), Map.of("amountMinor", 6_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedMinor").value(6_000))
                .andExpect(jsonPath("$.feeMinor").value(120));
        assertThat(balance(wallet)).isEqualTo(94_000);
        assertThat(balance(merchant.inrAccount())).isEqualTo(5_880);

        // Refund 1,000 then 4,880: the merchant balance is now exactly zero.
        postJson("/v1/payments/" + paymentId + "/refunds", svc, newKey(), Map.of("amountMinor", 1_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));
        postJson("/v1/payments/" + paymentId + "/refunds", svc, newKey(), Map.of("amountMinor", 4_880))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedMinor").value(5_880));
        assertThat(balance(wallet)).isEqualTo(99_880);
        assertThat(balance(merchant.inrAccount())).isZero();

        // 120 is still refundable on the payment, but the merchant has no funds left (the fee was kept).
        postJson("/v1/payments/" + paymentId + "/refunds", svc, newKey(), Map.of("amountMinor", 120))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient_funds"));
        // More than refundable is a different error.
        postJson("/v1/payments/" + paymentId + "/refunds", svc, newKey(), Map.of("amountMinor", 121))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_amount"));

        // The ledger still reconciles.
        postJson("/v1/admin/reconciliation/runs", adminToken(), null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
    }

    @Test
    @DisplayName("insufficient funds -> payment recorded as FAILED, no money moves")
    void insufficientFunds() throws Exception {
        UUID wallet = fundedWallet(500);
        postJson("/v1/payments", serviceToken(), newKey(), Map.of(
                "merchantId", ACME_MERCHANT_ID, "customerAccountId", wallet, "amountMinor", 501, "currency", "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
        assertThat(balance(wallet)).isEqualTo(500);
    }

    @Test
    @DisplayName("cancel releases the full authorization")
    void cancel() throws Exception {
        String svc = serviceToken();
        UUID wallet = fundedWallet(2_000);
        String id = read(postJson("/v1/payments", svc, newKey(), Map.of("merchantId", ACME_MERCHANT_ID,
                "customerAccountId", wallet, "amountMinor", 1_500, "currency", "INR"))).get("id").asText();
        postJson("/v1/payments/" + id + "/cancel", svc, newKey(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(balance(wallet)).isEqualTo(2_000);
        postJson("/v1/payments/" + id + "/capture", svc, newKey(), null).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("idempotent replay returns the identical response; reusing a key for another body is rejected")
    void idempotency() throws Exception {
        String svc = serviceToken();
        UUID wallet = fundedWallet(10_000);
        String key = newKey();
        Map<String, Object> body = Map.of("merchantId", ACME_MERCHANT_ID, "customerAccountId", wallet,
                "amountMinor", 1_000, "currency", "INR");

        String first = postJson("/v1/payments", svc, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postJson("/v1/payments", svc, key, body).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        assertThat(balance(wallet)).isEqualTo(9_000); // charged once

        postJson("/v1/payments", svc, key, Map.of("merchantId", ACME_MERCHANT_ID, "customerAccountId", wallet,
                "amountMinor", 2_000, "currency", "INR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));

        postJson("/v1/payments", svc, null, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency_key_missing"));
    }

    @Test
    @DisplayName("declined top-up (amount ending in 13) is FAILED and leaves the wallet untouched")
    void declinedTopup() throws Exception {
        UUID wallet = fundedWallet(0);
        postJson("/v1/topups", serviceToken(), newKey(), Map.of("accountId", wallet, "amountMinor", 10_013,
                "currency", "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card_declined"));
        assertThat(balance(wallet)).isZero();
    }

    @Test
    @DisplayName("validation errors return problem+json with field details")
    void validation() throws Exception {
        postJson("/v1/payments", serviceToken(), newKey(), Map.of("merchantId", ACME_MERCHANT_ID,
                "customerAccountId", UUID.randomUUID(), "amountMinor", -5, "currency", "inr"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}
