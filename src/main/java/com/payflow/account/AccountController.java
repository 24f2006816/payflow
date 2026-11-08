package com.payflow.account;

import com.payflow.ledger.PostingEntity;
import com.payflow.security.CurrentClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record CreateWalletRequest(@NotNull UUID customerId,
                                      @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record AccountResponse(UUID id, String ownerType, UUID ownerId, String accountType, String currency,
                                  long balanceMinor, String status, Instant createdAt) {
        static AccountResponse of(AccountEntity a) {
            return new AccountResponse(a.getId(), a.getOwnerType().name(), a.getOwnerId(), a.getAccountType().name(),
                    a.getCurrency(), a.getBalanceMinor(), a.getStatus(), a.getCreatedAt());
        }
    }

    public record PostingResponse(long id, UUID journalEntryId, long amountMinor, String currency, Instant createdAt) {
        static PostingResponse of(PostingEntity p) {
            return new PostingResponse(p.getId(), p.getJournalEntryId(), p.getAmountMinor(), p.getCurrency(),
                    p.getCreatedAt());
        }
    }

    public record StatementResponse(List<PostingResponse> items, Long nextCursor) {
    }

    @PostMapping("/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    @Operation(summary = "Open a customer wallet in a currency")
    public AccountResponse openWallet(@Valid @RequestBody CreateWalletRequest request) {
        return AccountResponse.of(accountService.openCustomerWallet(request.customerId(), request.currency()));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account and its current balance")
    public AccountResponse get(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.of(accountService.get(accountId, CurrentClient.from(jwt)));
    }

    @GetMapping("/{accountId}/postings")
    @Operation(summary = "Account statement, newest first (keyset pagination via ?before=<posting id>)")
    public StatementResponse statement(@PathVariable UUID accountId,
                                       @RequestParam(required = false) Long before,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @AuthenticationPrincipal Jwt jwt) {
        List<PostingEntity> page = accountService.statement(accountId, before, limit, CurrentClient.from(jwt));
        Long next = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return new StatementResponse(page.stream().map(PostingResponse::of).toList(), next);
    }
}
