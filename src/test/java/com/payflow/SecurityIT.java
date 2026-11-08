package com.payflow;

import com.payflow.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends IntegrationTest {

    @Test
    @DisplayName("no token -> 401; bad credentials -> 401")
    void authentication() throws Exception {
        mvc.perform(get("/v1/payments/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/payments/" + UUID.randomUUID()).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/auth/token")
                        .contentType("application/json")
                        .content("{\"client_id\":\"admin-cli\",\"client_secret\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("merchant role cannot create payments or merchants (403)")
    void roleChecks() throws Exception {
        String merchant = merchantToken();
        postJson("/v1/payments", merchant, newKey(), Map.of("merchantId", ACME_MERCHANT_ID,
                "customerAccountId", UUID.randomUUID(), "amountMinor", 100, "currency", "INR"))
                .andExpect(status().isForbidden());
        postJson("/v1/merchants", merchant, null, Map.of("name", "x", "currencies", java.util.List.of("INR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("tenant isolation: a merchant sees its own payments and gets 404 for another merchant's")
    void tenantIsolation() throws Exception {
        String admin = adminToken();
        String other = read(postJson("/v1/merchants", admin, null, Map.of("name", "Other Shop",
                "currencies", java.util.List.of("INR"))).andExpect(status().isCreated())).get("id").asText();
        UUID wallet = fundedWallet(5_000);
        String svc = serviceToken();
        String foreignPayment = read(postJson("/v1/payments", svc, newKey(), Map.of("merchantId", other,
                "customerAccountId", wallet, "amountMinor", 100, "currency", "INR"))).get("id").asText();
        String ownPayment = read(postJson("/v1/payments", svc, newKey(), Map.of("merchantId", ACME_MERCHANT_ID,
                "customerAccountId", wallet, "amountMinor", 100, "currency", "INR"))).get("id").asText();

        String acme = merchantToken();
        mvc.perform(get("/v1/payments/" + ownPayment).header("Authorization", bearer(acme)))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/payments/" + foreignPayment).header("Authorization", bearer(acme)))
                .andExpect(status().isNotFound());
        postJson("/v1/payments/" + foreignPayment + "/capture", acme, newKey(), null)
                .andExpect(status().isNotFound());
        // Merchants cannot read customer wallets.
        mvc.perform(get("/v1/accounts/" + wallet).header("Authorization", bearer(acme)))
                .andExpect(status().isNotFound());
        // ...but can capture their own payment.
        postJson("/v1/payments/" + ownPayment + "/capture", acme, newKey(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("X-Request-Id is propagated back to the caller")
    void requestIdEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "abc-123"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Request-Id", "abc-123"));
    }
}
