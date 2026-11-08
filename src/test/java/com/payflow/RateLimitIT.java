package com.payflow;

import com.payflow.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Separate Spring context (different property) with a tiny limit. */
@TestPropertySource(properties = "payflow.rate-limit.requests-per-minute=5")
class RateLimitIT extends IntegrationTest {

    @Test
    @DisplayName("the request after the per-minute limit gets 429 with Retry-After")
    void rateLimited() throws Exception {
        String token = token("checkout-service", "service-secret-change-me"); // 1 request (keyed by IP)
        String path = "/v1/payments/" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get(path).header("Authorization", bearer(token))).andExpect(status().isNotFound());
        }
        mvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }
}
