# ADR-0001: Modular monolith, not microservices

**Status:** accepted

## Context
A payment changes three things that must agree: the ledger (balances), the payment record (status), and the
event that tells the outside world (outbox). If these lived in different services, keeping them consistent
would need a saga with compensating actions, and every failure in between creates a window where the books are wrong.

## Decision
One deployable Spring Boot application, organised in packages with clear boundaries
(`ledger`, `payment`, `topup`, `idempotency`, `outbox`, `webhook`, `reconciliation`). Only `LedgerService`
may change balances. Cross-module communication that does not need atomicity goes through Kafka.

## Consequences
- ✅ Every money movement is one ACID transaction: no sagas, no compensation logic, no distributed locks.
- ✅ Simple to run, test and debug; one database to back up.
- ⚠️ The API and background workers scale together. Mitigation: the outbox relay, consumer and dispatcher use
  `SKIP LOCKED` claiming, so they can run in a separate "worker" deployment of the same image if needed.
- When to split: when a module has a different scaling profile or team ownership (e.g. webhooks at 100x API
  volume); it would become its own service consuming the same Kafka topic.
