# ADR-0004: Transactional outbox with a polling publisher

**Status:** accepted

## Context
"Commit to Postgres, then send to Kafka" fails in two ways: the commit succeeds and the send fails (event lost),
or the send succeeds and the commit rolls back (phantom event). Kafka transactions don't span Postgres.

## Decision
Business code inserts the event into `outbox_events` in the same transaction. `OutboxRelay` polls
(`FOR UPDATE SKIP LOCKED`, batch of 100, every 500 ms), sends with `acks=all` + idempotent producer, and marks rows
published. Events are keyed by aggregate id (per-payment ordering) and a failure stops the batch.

## Alternatives
- **CDC with Debezium** (tail the WAL): lower latency, no polling load, but needs Kafka Connect + Debezium to
  operate. Worth it at higher volume; the outbox table format is already compatible with Debezium's outbox router.
- **Event sourcing**: the ledger is already an append-only log, but making it the only source of truth for all
  state is a much larger change.

## Consequences
- ✅ No lost or phantom events. At-least-once delivery; consumers de-duplicate on event id.
- ⚠️ Up to ~poll-interval latency; the outbox table needs pruning of published rows (not implemented yet; partition
  by month or delete published rows older than N days).
- ⚠️ The relay holds a DB transaction while waiting for Kafka acks (bounded by batch size and a 10s timeout).
