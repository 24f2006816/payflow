package com.payflow.merchant;

import com.payflow.security.CurrentClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/merchants")
@Tag(name = "Merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    public record CreateMerchantRequest(@NotBlank @Size(max = 200) String name,
                                        @Size(max = 2000) String webhookUrl,
                                        @NotEmpty Set<@Pattern(regexp = "^[A-Z]{3}$") String> currencies) {
    }

    public record UpdateWebhookRequest(@NotBlank @Size(max = 2000) String webhookUrl) {
    }

    /** The webhook secret is shown exactly once, at creation, like an API key. */
    public record CreatedMerchantResponse(UUID id, String name, String webhookUrl, String webhookSecret,
                                          List<Map<String, Object>> accounts) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Onboard a merchant (creates a balance account per currency)")
    public CreatedMerchantResponse create(@Valid @RequestBody CreateMerchantRequest request) {
        var created = merchantService.create(request.name(), request.webhookUrl(), request.currencies());
        var m = created.merchant();
        return new CreatedMerchantResponse(m.getId(), m.getName(), m.getWebhookUrl(), m.getWebhookSecret(),
                created.accounts().stream()
                        .map(a -> Map.<String, Object>of("id", a.getId(), "currency", a.getCurrency()))
                        .toList());
    }

    @GetMapping("/{merchantId}")
    @Operation(summary = "Get a merchant profile")
    public MerchantView get(@PathVariable UUID merchantId, @AuthenticationPrincipal Jwt jwt) {
        CurrentClient.from(jwt).requireAccessToMerchant(merchantId, "merchant", merchantId);
        return merchantService.get(merchantId);
    }

    @PatchMapping("/{merchantId}/webhook")
    @Operation(summary = "Change the webhook URL (admin or the merchant itself)")
    public MerchantView updateWebhook(@PathVariable UUID merchantId, @Valid @RequestBody UpdateWebhookRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        CurrentClient.from(jwt).requireAccessToMerchant(merchantId, "merchant", merchantId);
        return merchantService.updateWebhookUrl(merchantId, request.webhookUrl());
    }
}
