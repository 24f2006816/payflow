package com.payflow.payment;

import com.payflow.idempotency.IdempotencyService;
import com.payflow.security.CurrentClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    public record CreatePaymentRequest(@NotNull UUID merchantId,
                                       @NotNull UUID customerAccountId,
                                       @Positive long amountMinor,
                                       @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                       @Size(max = 255) String description) {
    }

    public record CaptureRequest(@Positive Long amountMinor) {
    }

    public record RefundRequest(@Positive long amountMinor, @Size(max = 255) String reason) {
    }

    public record PaymentResponse(UUID id, UUID merchantId, UUID customerAccountId, long amountMinor,
                                  long capturedMinor, long refundedMinor, long feeMinor, String currency,
                                  String status, String failureReason, String description,
                                  Instant createdAt, Instant updatedAt) {
        static PaymentResponse of(PaymentEntity p) {
            return new PaymentResponse(p.getId(), p.getMerchantId(), p.getCustomerAccountId(), p.getAmountMinor(),
                    p.getCapturedMinor(), p.getRefundedMinor(), p.getFeeMinor(), p.getCurrency(), p.getStatus().name(),
                    p.getFailureReason(), p.getDescription(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record PaymentPage(List<PaymentResponse> items, int page, int size, long totalItems) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    @Operation(summary = "Authorize a payment from a customer wallet (reserves funds)")
    public ResponseEntity<?> create(@Parameter(required = true) @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @Valid @RequestBody CreatePaymentRequest body,
                                    @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        CurrentClient client = CurrentClient.from(jwt);
        IdempotencyService.requireValidKey(key);
        return idempotency.execute(client.clientId(), key, idempotency.fingerprint("POST", http.getRequestURI(), body),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.of(paymentService.authorize(
                        client, key, new PaymentService.CreatePayment(body.merchantId(), body.customerAccountId(),
                                body.amountMinor(), body.currency(), body.description())))));
    }

    @PostMapping("/{paymentId}/capture")
    @Operation(summary = "Capture an authorized payment (full, or partial with amountMinor)")
    public ResponseEntity<?> capture(@PathVariable UUID paymentId,
                                     @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                     @Valid @RequestBody(required = false) CaptureRequest body,
                                     @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        CurrentClient client = CurrentClient.from(jwt);
        Long amount = body == null ? null : body.amountMinor();
        return idempotency.execute(client.clientId(), key, idempotency.fingerprint("POST", http.getRequestURI(), body),
                () -> ResponseEntity.ok(PaymentResponse.of(paymentService.capture(client, paymentId, amount))));
    }

    @PostMapping("/{paymentId}/cancel")
    @Operation(summary = "Cancel an authorization and release the reserved funds")
    public ResponseEntity<?> cancel(@PathVariable UUID paymentId,
                                    @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        CurrentClient client = CurrentClient.from(jwt);
        return idempotency.execute(client.clientId(), key, idempotency.fingerprint("POST", http.getRequestURI(), null),
                () -> ResponseEntity.ok(PaymentResponse.of(paymentService.cancel(client, paymentId))));
    }

    @PostMapping("/{paymentId}/refunds")
    @Operation(summary = "Refund part or all of a captured payment")
    public ResponseEntity<?> refund(@PathVariable UUID paymentId,
                                    @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @Valid @RequestBody RefundRequest body,
                                    @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        CurrentClient client = CurrentClient.from(jwt);
        IdempotencyService.requireValidKey(key);
        return idempotency.execute(client.clientId(), key, idempotency.fingerprint("POST", http.getRequestURI(), body),
                () -> ResponseEntity.ok(PaymentResponse.of(
                        paymentService.refund(client, paymentId, key, body.amountMinor(), body.reason()))));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment")
    public PaymentResponse get(@PathVariable UUID paymentId, @AuthenticationPrincipal Jwt jwt) {
        return PaymentResponse.of(paymentService.get(CurrentClient.from(jwt), paymentId));
    }

    @GetMapping
    @Operation(summary = "List a merchant's payments, newest first")
    public PaymentPage list(@RequestParam UUID merchantId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @AuthenticationPrincipal Jwt jwt) {
        Page<PaymentEntity> result = paymentService.listForMerchant(CurrentClient.from(jwt), merchantId, page, size);
        return new PaymentPage(result.getContent().stream().map(PaymentResponse::of).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }
}
