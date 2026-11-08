# Status and verification checklist

Written and verified on 2026-11-08 in an environment **without access to Maven Central or Docker Hub**.
Nothing below says "verified" unless it was actually executed. Re-run everything with `mvn verify` on a
machine with internet + Docker before relying on it.

## What was executed, and the results

| Check | How | Result |
|---|---|---|
| Flyway migrations V1, V2 + seed apply cleanly | psql against PostgreSQL 16.15 | ✅ pass |
| Ledger invariants enforced by the database (balanced entries, ≥2 postings, currency match, append-only, no overdraft, idempotency uniqueness, refund ≤ capture, merchant role constraint) | 13 SQL checks, `tools/offline-verification/1_schema_invariants.sh` | ✅ 13/13 |
| Locking pattern prevents double-spend | 30 concurrent transactions vs. a 10,000 balance, same `FOR UPDATE` order as `LedgerService` | ✅ exactly 10 succeed, 0 drift, global sum 0 |
| Every raw SQL statement in `JdbcTemplate` code (outbox, idempotency, consumer, webhook claim/updates, reconciliation) | PREPARE/EXECUTE with typed parameters | ✅ 15/15 |
| Domain core (`com.payflow.domain`) compiles with `-Xlint:all -Werror` | javac 21 | ✅ |
| Domain unit tests (Money, JournalDraft, BalanceRules, PostingPlans, PaymentRules, FeeCalculator, WebhookSigner, RequestFingerprint, RetryBackoff) | real test files run against a **minimal local JUnit-5 API shim** (not the real JUnit engine) | ✅ 36/36 |
| All 85 Java files are syntactically valid | javac parse-only (`5_syntax_check.sh`) | ✅ 0 errors |
| docker-compose.yml | `docker compose config` | ✅ valid, 8 services |
| YAML / JSON (app config, k8s, CI, Grafana dashboard, ECS task definition, Postman) | parsed with Python | ✅ |
| `scripts/demo.sh` | `bash -n` | ✅ |

## NOT verified (blocked by the environment)

| Item | Why | What to do |
|---|---|---|
| Spring Boot application compiles | Maven Central blocked | `mvn -B compile`; most likely fixes are small API/version mismatches |
| Application starts (bean wiring, Flyway via Spring, JPA mappings vs schema) | needs dependencies | `docker compose up` or `mvn spring-boot:run` |
| Integration/e2e tests (`*IT`: PaymentFlowIT, ConcurrencyIT, WebhookDeliveryIT, SecurityIT, RateLimitIT) | Testcontainers needs Docker Hub images | `mvn verify` |
| Docker image build | base images not pullable | `docker build .` |
| Dependency versions (Boot 3.5.16, springdoc 2.8.9, resilience4j 2.3.0, images `apache/kafka:3.9.1`, `prom/prometheus:v3.5.0`, `grafana/grafana:12.1.0`) | could not query registries | bump if a version does not resolve |
| Kubernetes manifests / ECS task definition against a real cluster | no cluster | `kubectl apply --dry-run=server`, `aws ecs register-task-definition` |

Highest-risk spots to look at first if `mvn verify` fails (reviewed by hand, but uncompiled):
1. `RedisCacheConfig`: `Jackson2JsonRedisSerializer(ObjectMapper, Class)` constructor.
2. `KafkaConsumerConfig`: `DeadLetterPublishingRecoverer` destination-resolver lambda typing.
3. `ApiErrorHandler`: pattern-matching `switch` over `DomainException` subclasses.
4. `TestcontainersConfiguration`: `org.testcontainers.kafka.KafkaContainer` with `@ServiceConnection`.
5. JPA mappings vs schema: `ddl-auto=none`; set `validate` temporarily to let Hibernate check them.

## Module checklist

| Module | Code | Tests written | Executed |
|---|---|---|---|
| Schema + migrations + seed | ✅ | SQL suite | ✅ |
| Domain core (money, ledger rules, posting plans, state machine, fees, signatures, backoff) | ✅ | 9 unit test classes | ✅ (shim) |
| Ledger service (locking, posting, balances) | ✅ | ConcurrencyIT, PaymentFlowIT | ⚠️ SQL pattern only |
| Accounts / merchants (+ Redis cache, SSRF-safe webhook URLs) | ✅ | PaymentFlowIT, SecurityIT, WebhookDeliveryIT | ❌ |
| Payments: authorize / capture / cancel / refund | ✅ | PaymentFlowIT, ConcurrencyIT | ❌ |
| Top-ups + simulated PSP + retry/circuit breaker + recovery job | ✅ | PaymentFlowIT (success + decline) | ❌ |
| Idempotency-Key layer | ✅ | PaymentFlowIT, ConcurrencyIT | ⚠️ SQL only |
| Transactional outbox + Kafka relay | ✅ | WebhookDeliveryIT | ⚠️ SQL only |
| Kafka consumer (dedup) + DLT | ✅ | WebhookDeliveryIT | ⚠️ SQL only |
| Webhook dispatcher (lease, HMAC, backoff, DEAD) | ✅ | WebhookDeliveryIT | ⚠️ SQL + signer only |
| Reconciliation (snapshot + advisory lock) | ✅ | PaymentFlowIT, ConcurrencyIT | ⚠️ SQL only |
| Security: JWT, roles, tenant isolation, rate limit | ✅ | SecurityIT, RateLimitIT | ❌ |
| Errors (problem+json), request ids, OpenAPI | ✅ | PaymentFlowIT, SecurityIT | ❌ |
| Observability: metrics, Grafana dashboard, structured logs | ✅ | n/a | config validated |
| Docker, Compose, CI, k8s, ECS, Postman, demo script | ✅ | n/a | config validated |
| Docs: README, ADRs, AWS guide, learning guide | ✅ | n/a | n/a |

## Known limitations / next steps
- **Spring Boot 3.5 is out of open-source support (ended June 2026; 3.5.16 is the final OSS release).**
  The project targets 3.5.16 because Boot 4.x could not be compiled or verified offline and has many breaking
  changes (Jackson 3, Hibernate 7, modular starters). Migrating to Boot 4.1 is the first follow-up once the build runs.
- Top-up PENDING responses are replayed as 202 for the same key; clients poll `GET /v1/topups/{id}`.
- Refunds require merchant balance; production systems debit a reserve account or create a negative balance with collections.
- Fixed-window rate limiting allows short 2x bursts at window edges (Project 2 implements sliding window / token bucket).
- No multi-currency FX; each payment is single-currency.
- Webhook DNS-rebinding protection would need an egress proxy.
- Load-test scripts (k6) and recorded benchmark numbers: **not done yet**. No performance numbers are claimed anywhere.
