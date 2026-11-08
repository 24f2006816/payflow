package com.payflow.domain.ledger;

import com.payflow.domain.DomainException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated, not-yet-persisted journal entry.
 * <p>
 * Invariants checked here (and again by a database trigger, defence in depth):
 * <ol>
 *   <li>at least two lines</li>
 *   <li>no zero-amount lines (zero lines are dropped by the builder)</li>
 *   <li>lines sum to zero per currency (money is neither created nor destroyed)</li>
 * </ol>
 */
public final class JournalDraft {

    private final String entryType;
    private final String referenceType;
    private final UUID referenceId;
    private final String description;
    private final List<LedgerLine> lines;

    private JournalDraft(String entryType, String referenceType, UUID referenceId, String description,
                         List<LedgerLine> lines) {
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.referenceType = Objects.requireNonNull(referenceType, "referenceType");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId");
        this.description = description;
        this.lines = List.copyOf(lines);
        validate();
    }

    public static Builder builder(String entryType, String referenceType, UUID referenceId) {
        return new Builder(entryType, referenceType, referenceId);
    }

    private void validate() {
        if (lines.size() < 2) {
            throw new DomainException.UnbalancedEntry(
                    "journal entry " + entryType + " needs at least 2 non-zero lines, got " + lines.size());
        }
        Map<String, Long> sums = new HashMap<>();
        for (LedgerLine line : lines) {
            sums.merge(line.amount().currency(), line.amount().minor(), Math::addExact);
        }
        sums.forEach((currency, sum) -> {
            if (sum != 0) {
                throw new DomainException.UnbalancedEntry(
                        "journal entry " + entryType + " does not balance in " + currency + " (sum=" + sum + ")");
            }
        });
    }

    /** Account ids in a deterministic order: lock them in this order to avoid deadlocks. */
    public List<UUID> accountIdsInLockOrder() {
        List<UUID> ids = new ArrayList<>(lines.stream().map(LedgerLine::accountId).distinct().toList());
        Collections.sort(ids);
        return ids;
    }

    public String entryType() { return entryType; }
    public String referenceType() { return referenceType; }
    public UUID referenceId() { return referenceId; }
    public String description() { return description; }
    public List<LedgerLine> lines() { return lines; }

    public static final class Builder {
        private final String entryType;
        private final String referenceType;
        private final UUID referenceId;
        private String description;
        private final List<LedgerLine> lines = new ArrayList<>();

        private Builder(String entryType, String referenceType, UUID referenceId) {
            this.entryType = entryType;
            this.referenceType = referenceType;
            this.referenceId = referenceId;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Adds a line; zero amounts are skipped (e.g. a zero fee) because postings must be non-zero. */
        public Builder line(UUID accountId, long minor, String currency) {
            if (minor != 0) {
                lines.add(LedgerLine.of(accountId, minor, currency));
            }
            return this;
        }

        public JournalDraft build() {
            return new JournalDraft(entryType, referenceType, referenceId, description, lines);
        }
    }
}
