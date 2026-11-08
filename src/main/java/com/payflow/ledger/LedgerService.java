package com.payflow.ledger;

import com.payflow.account.AccountEntity;
import com.payflow.account.AccountRepository;
import com.payflow.common.ApiException;
import com.payflow.domain.DomainException;
import com.payflow.domain.ledger.BalanceRules;
import com.payflow.domain.ledger.JournalDraft;
import com.payflow.domain.ledger.LedgerLine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only component allowed to move money.
 * <p>
 * Algorithm (all inside the caller's transaction):
 * <ol>
 *   <li>Lock every affected account row, in primary-key order (deadlock-free).</li>
 *   <li>Validate status/currency and compute new balances with {@link BalanceRules} (no overdraft).</li>
 *   <li>Insert the journal entry + postings; update the materialised balances.</li>
 * </ol>
 * The database independently enforces: entry balances (deferred trigger), currency match (trigger),
 * non-negative balances (CHECK) and immutability (trigger). Two layers, because ledger bugs cost money.
 * <p>
 * {@code MANDATORY}: posting money must be part of the business transaction that records why it moved
 * (payment status, outbox event). {@code noRollbackFor}: callers may catch InsufficientFunds and record a
 * FAILED payment in the same transaction; nothing was written yet when that exception is thrown.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalEntryRepository journalEntries;
    private final PostingRepository postings;
    private final Clock clock;
    private final Timer postTimer;

    public LedgerService(AccountRepository accounts, JournalEntryRepository journalEntries,
                         PostingRepository postings, Clock clock, MeterRegistry meters) {
        this.accounts = accounts;
        this.journalEntries = journalEntries;
        this.postings = postings;
        this.clock = clock;
        this.postTimer = Timer.builder("payflow.ledger.post").description("time to lock, validate and post")
                .publishPercentileHistogram().register(meters);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = DomainException.InsufficientFunds.class)
    public UUID post(JournalDraft draft) {
        return postTimer.record(() -> doPost(draft));
    }

    private UUID doPost(JournalDraft draft) {
        List<UUID> ids = draft.accountIdsInLockOrder();
        Map<UUID, AccountEntity> locked = accounts.lockAllInOrder(ids).stream()
                .collect(Collectors.toMap(AccountEntity::getId, Function.identity()));
        if (locked.size() != ids.size()) {
            throw ApiException.unprocessable("account_not_found", "one or more ledger accounts do not exist");
        }

        // Net change per account (an entry may touch the same account twice).
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (LedgerLine line : draft.lines()) {
            AccountEntity account = locked.get(line.accountId());
            if (!account.getCurrency().equals(line.amount().currency())) {
                throw new DomainException.CurrencyMismatch("account " + account.getId() + " is "
                        + account.getCurrency() + ", line is " + line.amount().currency());
            }
            deltas.merge(line.accountId(), line.amount().minor(), Math::addExact);
        }

        // Validate everything before mutating anything.
        Map<UUID, Long> newBalances = new LinkedHashMap<>();
        for (var e : deltas.entrySet()) {
            AccountEntity account = locked.get(e.getKey());
            if (!account.isActive() && e.getValue() < 0) {
                throw ApiException.unprocessable("account_inactive", "account " + account.getId() + " is "
                        + account.getStatus());
            }
            newBalances.put(account.getId(), BalanceRules.applyDelta(account.getBalanceMinor(), e.getValue(),
                    account.isAllowNegative(), account.getId().toString()));
        }

        Instant now = clock.instant();
        UUID entryId = UUID.randomUUID();
        journalEntries.save(new JournalEntryEntity(entryId, draft.entryType(), draft.referenceType(),
                draft.referenceId(), draft.description(), now));
        List<PostingEntity> rows = new ArrayList<>(draft.lines().size());
        for (LedgerLine line : draft.lines()) {
            rows.add(new PostingEntity(entryId, line.accountId(), line.amount().minor(), line.amount().currency(), now));
        }
        postings.saveAll(rows);
        newBalances.forEach((id, balance) -> locked.get(id).applyLedgerBalance(balance));
        return entryId;
    }
}
