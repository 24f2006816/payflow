# ADR-0002: Pessimistic row locks (in primary-key order) for balance changes

**Status:** accepted

## Options considered
| Option | Behaviour under contention | Verdict |
|---|---|---|
| `UPDATE accounts SET balance = balance - x WHERE id = ? AND balance >= x` (atomic conditional update) | Fast, no explicit lock | Good for single-account debits, awkward for multi-line journal entries |
| Optimistic locking (`@Version`) only | Losers get an exception and must retry; retry storms on hot accounts | Rejected as primary mechanism |
| `SERIALIZABLE` isolation | Correct, but serialization failures must be retried by the caller | Rejected: same retry burden, harder to reason about |
| **`SELECT … FOR UPDATE` on all touched accounts, ordered by id** | Concurrent writers queue on the row; no retries needed | **Chosen** |

## Decision
`LedgerService` locks every account in a journal entry with `FOR UPDATE`, in primary-key order, validates,
then inserts postings and updates balances. Payments are locked before accounts (consistent global lock order:
payment → accounts) to avoid deadlocks. `lock_timeout = 5s` turns pile-ups into a retryable 409.
`@Version` remains as a safety net.

## Consequences
- ✅ Verified offline: 30 concurrent spends against a balance of 10 units → exactly 10 succeed, zero drift.
- ⚠️ Hot accounts (the platform fee account receives a posting on every capture) serialise writes.
  Mitigations if it becomes the bottleneck: shard the fee account into N sub-accounts and pick one at random,
  or aggregate fees asynchronously. Measure first.
- Gotcha documented in code: lock order comes from Postgres' uuid ordering, not Java's `UUID.compareTo` (signed).
