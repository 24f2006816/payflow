# ADR-0003: Two layers of idempotency

**Status:** accepted

## Context
Clients retry when they time out. Without protection, a retried `POST /v1/payments` charges twice.

## Decision
1. **HTTP layer** (`IdempotencyService`): `Idempotency-Key` header, scoped per client. The key is claimed in its
   own committed transaction with a request fingerprint (SHA-256 of method + path + canonical JSON). A completed key
   replays the stored status and body (`Idempotent-Replayed: true`); a different body with the same key → 422; an
   in-flight duplicate → 409. On failure the key is released so the client can retry.
2. **Domain layer**: `UNIQUE (client_id, idempotency_key)` on payments and top-ups,
   `UNIQUE (payment_id, idempotency_key)` on refunds. Services look up the existing row first.

## Why both
The HTTP layer commits the key and the business result in separate transactions (the operation may itself span
several transactions, as top-ups do). If the process crashes after the business commit but before the key is
marked COMPLETED, the lease expires and a retry re-executes the operation, and the domain uniqueness check then
returns the existing payment instead of charging again.

## Consequences
- ✅ Retries are safe at every step; verified constraint behaviour in SQL; concurrency covered by `ConcurrencyIT`.
- ⚠️ Keys are kept for 24h (`payflow.idempotency.retention`), the same as Stripe; a retry after that is a new request.
