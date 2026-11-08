package com.payflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Typed, validated configuration. The application refuses to start on invalid values
 * (fail fast at boot instead of failing on the first payment).
 */
@Validated
@ConfigurationProperties(prefix = "payflow")
public record PayflowProperties(
        @Valid @NotNull Fees fees,
        @Valid @NotNull Security security,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Idempotency idempotency,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Webhooks webhooks,
        @Valid @NotNull Psp psp,
        @Valid @NotNull Reconciliation reconciliation,
        @Valid @NotNull Topups topups) {

    public record Fees(@Min(0) @Max(10_000) int bps) {
    }

    public record Security(@NotBlank @Size(min = 32, message = "jwt-secret must be at least 32 bytes for HS256")
                           String jwtSecret,
                           @NotBlank String jwtIssuer,
                           @NotNull Duration tokenTtl) {
    }

    public record RateLimit(@Min(1) int requestsPerMinute) {
    }

    public record Idempotency(@NotNull Duration lockTimeout, @NotNull Duration retention) {
    }

    public record Outbox(@NotBlank String topic, @Min(1) int batchSize, @NotNull Duration pollInterval) {
    }

    public record Webhooks(@NotNull Duration pollInterval, @Min(1) int batchSize, @Min(1) int maxAttempts,
                           @NotNull Duration baseBackoff, @NotNull Duration maxBackoff,
                           @NotNull Duration connectTimeout, @NotNull Duration readTimeout,
                           boolean allowInsecureHttp, boolean allowPrivateHosts) {
    }

    public record Psp(@NotNull Duration latency, double transientFailureRate) {
    }

    public record Reconciliation(@NotBlank String cron) {
    }

    public record Topups(@NotNull Duration recoveryInterval, @NotNull Duration recoveryMinAge) {
    }
}
