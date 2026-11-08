package com.payflow.domain.ledger;

import com.payflow.domain.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalDraftTest {

    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    @DisplayName("accepts a balanced two-line entry")
    void balanced() {
        JournalDraft draft = JournalDraft.builder("T", "R", UUID.randomUUID())
                .line(a, -100, "INR").line(b, 100, "INR").build();
        assertEquals(2, draft.lines().size());
    }

    @Test
    @DisplayName("rejects an unbalanced entry")
    void unbalanced() {
        assertThrows(DomainException.UnbalancedEntry.class, () -> JournalDraft.builder("T", "R", UUID.randomUUID())
                .line(a, -100, "INR").line(b, 99, "INR").build());
    }

    @Test
    @DisplayName("balances per currency, not across currencies")
    void perCurrency() {
        assertThrows(DomainException.UnbalancedEntry.class, () -> JournalDraft.builder("T", "R", UUID.randomUUID())
                .line(a, -100, "INR").line(b, 100, "USD").build());
    }

    @Test
    @DisplayName("drops zero lines and then requires at least two lines")
    void zeroLinesDropped() {
        assertThrows(DomainException.UnbalancedEntry.class, () -> JournalDraft.builder("T", "R", UUID.randomUUID())
                .line(a, 0, "INR").line(b, 0, "INR").build());
        JournalDraft draft = JournalDraft.builder("T", "R", UUID.randomUUID())
                .line(a, -10, "INR").line(b, 10, "INR").line(c, 0, "INR").build();
        assertEquals(2, draft.lines().size());
    }

    @Test
    @DisplayName("returns distinct account ids in a deterministic (sorted) lock order")
    void lockOrder() {
        JournalDraft draft = JournalDraft.builder("T", "R", UUID.randomUUID())
                .line(c, -10, "INR").line(a, 5, "INR").line(b, 5, "INR").line(a, 0, "INR").build();
        List<UUID> order = draft.accountIdsInLockOrder();
        assertEquals(List.of(a, b, c), order);
        assertTrue(order.get(0).compareTo(order.get(1)) < 0);
    }
}
