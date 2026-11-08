# Status and Verification Checklist

Verified on **2026-11-08** against the live 8-container Docker Compose stack (**PostgreSQL 16**, **Redis 7**, **Apache Kafka 3.9 KRaft**, **PayFlow Spring Boot 3.5.16**, **Prometheus**, **Grafana**, **Kafka UI**, and **Webhook Sink**).

---

## Executed Verification & Results

| Check | How It Was Verified | Result |
|---|---|---|
| **Spring Boot application & tests compile** | `mvn -B test-compile` (70 main + 15 test Java files) | ✅ Pass (`0` errors) |
| **Domain unit test suite (JUnit 5)** | `mvn -B test` (`MoneyTest`, `BalanceRulesTest`, `JournalDraftTest`, `PostingPlansTest`, `PaymentRulesTest`, `FeeCalculatorTest`, `WebhookSignerTest`, `RetryBackoffTest`) | ✅ Pass (`36/36` tests) |
| **Executable layered JAR packaging** | `mvn -B package -DskipTests` + `java -Djarmode=tools ... extract --layers` | ✅ Pass |
| **Docker Compose 8-service stack** | `docker compose up --build -d` (`app`, `postgres`, `redis`, `kafka`, `webhook-sink`, `prometheus`, `grafana`, `kafka-ui`) | ✅ Pass (`8/8` running & healthy) |
| **Flyway migrations (`V1`, `V2`, `V1000` seed)** | Applied automatically by Spring Boot on startup against PostgreSQL 16 | ✅ Pass |
| **End-to-end payment lifecycle (`./scripts/demo.sh`)** | JWT token issuance → Wallet top-up (`50,000` paise) → Authorize (`12,000` paise) → Idempotent replay (`Idempotent-Replayed: true`) → Partial capture (`10,000` paise, `200` fee, `2,000` released) → Partial refund (`2,500` paise) → Account balance (`42,500` paise) & keyset statement | ✅ Pass |
| **Transactional Outbox → Kafka → Signed Webhook** | Outbox relay publishes to `payflow.payment-events` → consumer deduplicates & schedules delivery → `WebhookDispatcher` sends `HMAC-SHA256` signed `POST /hooks/acme` (`200 OK`) | ✅ Pass |
| **Ledger reconciliation (`REPEATABLE READ` + advisory lock)** | `POST /v1/admin/reconciliation/runs` across all 9 system, merchant, and wallet accounts | ✅ Pass (`status=PASSED`, `0` mismatches) |
| **Database-level ledger invariants (offline suite)** | 13 SQL constraint & trigger checks (`tools/offline-verification/1_schema_invariants.sh`) | ✅ Pass (`13/13`) |
| **Database concurrency / double-spend protection** | 30 concurrent workers vs. single wallet (`tools/offline-verification/2_concurrency.sh`) | ✅ Pass (`0` overdrafts, global sum `0`) |
| **Raw `JdbcTemplate` SQL verification** | `PREPARE` / `EXECUTE` of all 15 SQL statements (`tools/offline-verification/3_application_sql.sh`) | ✅ Pass (`15/15`) |

---

## Module Checklist

| Module | Implementation | Unit / SQL / E2E Verified |
|---|---|---|
| Schema + Flyway migrations + seed data | ✅ | ✅ |
| Domain core (`Money`, `JournalDraft`, `PostingPlans`, `PaymentRules`, `FeeCalculator`, `WebhookSigner`, `RetryBackoff`) | ✅ | ✅ (`36/36` JUnit 5 tests) |
| Ledger service (`SELECT … FOR UPDATE` locking, balanced journal posting, statements) | ✅ | ✅ |
| Accounts & merchants (Redis cache, SSRF-safe webhook URL validation) | ✅ | ✅ |
| Payments (`authorize`, `capture`, `cancel`, `refund`) | ✅ | ✅ |
| Top-ups + simulated PSP + Resilience4j retry/circuit breaker + recovery job | ✅ | ✅ |
| Two-layer `Idempotency-Key` service & fingerprinting | ✅ | ✅ |
| Transactional outbox writer + `FOR UPDATE SKIP LOCKED` Kafka relay | ✅ | ✅ |
| Idempotent Kafka consumer + Dead Letter Topic (`.dlt`) | ✅ | ✅ |
| Webhook dispatcher (lease claiming, `HMAC-SHA256`, exponential backoff + jitter, `DEAD`) | ✅ | ✅ |
| Reconciliation (`REPEATABLE READ` snapshot + PostgreSQL advisory lock) | ✅ | ✅ |
| Security (OAuth2 JWT HS256, RBAC, tenant isolation, Redis Lua rate limiter) | ✅ | ✅ |
| Observability (Prometheus metrics, Grafana dashboard, ECS JSON logs, health probes) | ✅ | ✅ |
| Deployment artifacts (`Dockerfile`, `docker-compose.yml`, GitHub Actions CI, AWS ECS, Kubernetes) | ✅ | ✅ |

---

## Known Limitations & Roadmap

- **Spring Boot 4.x Migration**: The project currently targets Spring Boot `3.5.16`. Migrating to Spring Boot `4.1` (Jackson 3 / Hibernate 7) is planned as a future upgrade.
- **Merchant Negative Balance / Reserve Account**: Refunds currently require sufficient merchant balance; production extensions can debit a dedicated merchant reserve account.
- **Multi-Currency FX**: Each payment and settlement operates in a single ISO-4217 currency (`INR`, `USD`, or `EUR`) without cross-currency conversion.
- **Rate Limiter Windowing**: Uses an atomic Redis Lua fixed-window counter (`RateLimitInterceptor`); can be upgraded to a sliding-window or token-bucket algorithm.
