package com.payflow.topup;

import com.payflow.account.AccountEntity;
import com.payflow.account.AccountRepository;
import com.payflow.common.NotFoundException;
import com.payflow.domain.DomainException;
import com.payflow.domain.Money;
import com.payflow.domain.payment.PostingPlans;
import com.payflow.ledger.LedgerService;
import com.payflow.ledger.SystemAccounts;
import com.payflow.outbox.OutboxWriter;
import com.payflow.security.CurrentClient;
import com.payflow.topup.psp.PaymentServiceProvider;
import com.payflow.topup.psp.ResilientPspClient;
import com.payflow.topup.psp.PspUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Top-up = money entering a wallet from an external provider. Three steps, deliberately NOT one transaction:
 * <ol>
 *   <li>TX1: record a PENDING top-up (commits, so we never "forget" a charge we are about to make).</li>
 *   <li>Call the provider OUTSIDE any transaction (never hold DB locks/connections during network I/O).</li>
 *   <li>TX2: lock the top-up; if still PENDING, post the ledger entry and mark SUCCEEDED (or FAILED).</li>
 * </ol>
 * If the process dies between 2 and 3, or the provider times out, the top-up stays PENDING and
 * {@link TopupRecoveryJob} completes it later by re-charging with the same reference (idempotent at the PSP).
 */
@Service
public class TopupService {

    private static final Logger log = LoggerFactory.getLogger(TopupService.class);

    private final TopupRepository topups;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final SystemAccounts systemAccounts;
    private final OutboxWriter outbox;
    private final ResilientPspClient psp;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public TopupService(TopupRepository topups, AccountRepository accounts, LedgerService ledger,
                        SystemAccounts systemAccounts, OutboxWriter outbox, ResilientPspClient psp,
                        TransactionTemplate tx, MeterRegistry meters) {
        this.topups = topups;
        this.accounts = accounts;
        this.ledger = ledger;
        this.systemAccounts = systemAccounts;
        this.outbox = outbox;
        this.psp = psp;
        this.tx = tx;
        this.meters = meters;
    }

    public TopupEntity create(CurrentClient client, String idempotencyKey, UUID accountId, long amountMinor,
                              String currency) {
        Money amount = Money.positive(amountMinor, currency);
        TopupEntity topup;
        try {
            topup = tx.execute(status -> createPending(client, idempotencyKey, accountId, amount));
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key won the INSERT race. The failed transaction is rolled back,
            // so read the winner's row in a fresh one (never keep using a session after a failed flush).
            topup = tx.execute(status -> topups.findByClientIdAndIdempotencyKey(client.clientId(), idempotencyKey)
                    .orElseThrow(() -> race));
        }
        if (topup.getStatus() != TopupEntity.Status.PENDING) {
            return topup; // duplicate request for a finished top-up
        }
        return chargeAndComplete(topup.getId(), topup.getAmountMinor(), topup.getCurrency());
    }

    /** Used by the recovery job as well. Returns the top-up in its latest state. */
    TopupEntity chargeAndComplete(UUID topupId, long amountMinor, String currency) {
        PaymentServiceProvider.ChargeResult result;
        try {
            result = psp.charge(topupId.toString(), amountMinor, currency);
        } catch (PspUnavailableException | CallNotPermittedException e) {
            log.warn("provider unavailable for top-up {}, leaving PENDING for recovery: {}", topupId, e.toString());
            meters.counter("payflow.topups", "outcome", "PENDING").increment();
            return topups.findById(topupId).orElseThrow();
        }
        return tx.execute(status -> complete(topupId, result));
    }

    private TopupEntity createPending(CurrentClient client, String key, UUID accountId, Money amount) {
        var existing = topups.findByClientIdAndIdempotencyKey(client.clientId(), key);
        if (existing.isPresent()) {
            return existing.get();
        }
        AccountEntity wallet = accounts.findById(accountId)
                .filter(a -> a.getAccountType() == AccountEntity.AccountType.CUSTOMER_WALLET)
                .orElseThrow(() -> new NotFoundException("customer wallet", accountId));
        if (!wallet.getCurrency().equals(amount.currency())) {
            throw new DomainException.CurrencyMismatch("wallet is " + wallet.getCurrency());
        }
        systemAccounts.pspClearing(amount.currency()); // fail fast on unsupported currency
        return topups.saveAndFlush(TopupEntity.pending(client.clientId(), key, accountId, amount.minor(),
                amount.currency()));
    }

    private TopupEntity complete(UUID topupId, PaymentServiceProvider.ChargeResult result) {
        TopupEntity topup = topups.findByIdForUpdate(topupId).orElseThrow();
        if (topup.getStatus() != TopupEntity.Status.PENDING) {
            return topup; // completed concurrently (e.g. by the recovery job)
        }
        if (result.outcome() == PaymentServiceProvider.Outcome.SUCCEEDED) {
            ledger.post(PostingPlans.topup(topup.getId(), systemAccounts.pspClearing(topup.getCurrency()),
                    topup.getAccountId(), topup.getAmountMinor(), topup.getCurrency()));
            topup.succeed(result.pspReference());
        } else {
            topup.fail(result.pspReference(), result.declineReason());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topupId", topup.getId());
        data.put("accountId", topup.getAccountId());
        data.put("amountMinor", topup.getAmountMinor());
        data.put("currency", topup.getCurrency());
        data.put("status", topup.getStatus().name());
        outbox.append("topup", topup.getId(), "topup." + topup.getStatus().name().toLowerCase(), null, data);
        meters.counter("payflow.topups", "outcome", topup.getStatus().name()).increment();
        return topup;
    }

    public TopupEntity get(CurrentClient client, UUID topupId) {
        TopupEntity topup = topups.findById(topupId).orElseThrow(() -> new NotFoundException("topup", topupId));
        if (!client.isPrivileged() && !client.clientId().equals(topup.getClientId())) {
            throw new NotFoundException("topup", topupId);
        }
        return topup;
    }
}
