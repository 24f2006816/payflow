package com.payflow.topup;

import com.payflow.idempotency.IdempotencyService;
import com.payflow.security.CurrentClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/topups")
@Tag(name = "Top-ups")
public class TopupController {

    private final TopupService topupService;
    private final IdempotencyService idempotency;

    public TopupController(TopupService topupService, IdempotencyService idempotency) {
        this.topupService = topupService;
        this.idempotency = idempotency;
    }

    public record CreateTopupRequest(@NotNull UUID accountId,
                                     @Positive long amountMinor,
                                     @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record TopupResponse(UUID id, UUID accountId, long amountMinor, String currency, String status,
                                String pspReference, String failureReason, Instant createdAt, Instant updatedAt) {
        static TopupResponse of(TopupEntity t) {
            return new TopupResponse(t.getId(), t.getAccountId(), t.getAmountMinor(), t.getCurrency(),
                    t.getStatus().name(), t.getPspReference(), t.getFailureReason(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** 201 when the outcome is final, 202 when the provider was unavailable and the top-up is still PENDING. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    @Operation(summary = "Add funds to a customer wallet through the payment provider")
    public ResponseEntity<?> create(@RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @Valid @RequestBody CreateTopupRequest body,
                                    @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        CurrentClient client = CurrentClient.from(jwt);
        IdempotencyService.requireValidKey(key);
        return idempotency.execute(client.clientId(), key, idempotency.fingerprint("POST", http.getRequestURI(), body),
                () -> {
                    TopupEntity t = topupService.create(client, key, body.accountId(), body.amountMinor(), body.currency());
                    HttpStatus status = t.getStatus() == TopupEntity.Status.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
                    return ResponseEntity.status(status).body(TopupResponse.of(t));
                });
    }

    @GetMapping("/{topupId}")
    @Operation(summary = "Get a top-up")
    public TopupResponse get(@PathVariable UUID topupId, @AuthenticationPrincipal Jwt jwt) {
        return TopupResponse.of(topupService.get(CurrentClient.from(jwt), topupId));
    }
}
