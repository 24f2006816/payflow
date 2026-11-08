# PayFlow learning guide

How to use this guide: for each module, read **Study first**, then read the code in the order listed, run
the matching test, and finally answer the interview questions out loud *without looking*. If you can't explain a
line, you don't own it yet. All links were checked on 2026-11-08.

**Recommended reading order of the code**
1. `db/migration/V1__core_schema.sql` (the data model and the invariants)
2. `domain/` (pure business rules, no Spring) and their tests in `src/test/java/com/payflow/domain`
3. `ledger/LedgerService` → `payment/PaymentService` → `payment/PaymentController`
4. `idempotency/IdempotencyService`
5. `outbox/` → `webhook/` → `reconciliation/`
6. `topup/` (resilience), `security/`, `config/`, then the `*IT` tests

**Background resources for the whole project**
| Resource | Why |
|---|---|
| [Building web applications with Spring Boot 3 (freeCodeCamp, Dan Vega)](https://www.youtube.com/watch?v=31KTdfRH6nY) | Spring Boot 3 fundamentals if Spring is new to you |
| [Hussein Nasser, YouTube](https://www.youtube.com/@hnasr) | Backend fundamentals: Postgres locks, connection pools, proxies |
| [Designing Data-Intensive Applications, 2nd ed.](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781098119058/) | The transactions and stream-processing chapters map directly onto this project |
| [Martin Kleppmann's Distributed Systems lectures](https://www.youtube.com/playlist?list=PLeKd45zvjcDFUEv_ohr_HdUFe97RItdiB) | Theory behind leases, at-least-once delivery, consistency |
| [ByteByteGo system-design-101](https://github.com/ByteByteGoHq/system-design-101) | Quick visual refreshers for interviews |

---

## Module 1: Money and the double-entry ledger
**Code:** `domain/Money`, `domain/ledger/JournalDraft`, `domain/payment/PostingPlans`, `V1__core_schema.sql` (postings, triggers)

**Concept.** Money is stored as integer minor units (paise). Every movement is a *journal entry* with ≥2
*postings* (signed balance changes) that sum to zero per currency. A balance is the sum of an account's postings;
`accounts.balance_minor` is a cached copy maintained in the same transaction.

**Why.** "UPDATE balance = balance - 500" loses history and can't be audited or reconciled. With postings you can
reconstruct any balance at any time and prove money was neither created nor destroyed.

**Study first.**
- [Accounting for Developers (Modern Treasury)](https://www.moderntreasury.com/resources/ebooks/accounting-for-developers), then [Part II: ledger for a wallet app](https://www.moderntreasury.com/journal/accounting-for-developers-part-ii)
- Why `double` is wrong for money (0.1 + 0.2 ≠ 0.3), and Java `Math.addExact` overflow checks

**Key lines to understand.**
- `JournalDraft.validate()` checks balance per currency, and the deferred trigger `trg_postings_balanced` checks it again at COMMIT. Why *deferred*? Postings are inserted one by one; the sum is only zero after the last one.
- `PostingPlans.capture`: why the remainder of a partial capture goes back to the customer and the fee goes to `PLATFORM_FEES`.
- The `PLATFORM_HOLDS` account: authorization reserves money without paying the merchant yet.

**Interview questions.**
1. Why integer minor units? What about currencies with 3 decimals (e.g. KWD)?
2. Why store both postings *and* a balance column? What keeps them consistent?
3. Why is the ledger append-only? How do you correct a wrong entry?
4. Why can PSP_CLEARING go negative but a wallet cannot?

**How to explain it (30 seconds).** "Every money movement is a balanced journal entry, checked in Java and again by a
deferred Postgres trigger. Balances are a cached sum of immutable postings, updated in the same transaction, and a
reconciliation job proves they match. Corrections are new entries, never updates."

---

## Module 2: Concurrency control (no double spending)
**Code:** `ledger/LedgerService`, `AccountRepository.lockAllInOrder`, `PaymentRepository.findByIdForUpdate`, `ConcurrencyIT`, `tools/offline-verification/2_concurrency.sh`, ADR-0002

**Concept.** Two requests reading balance=600 and both spending 500 is a *lost update*. We serialise writers on
the account row with `SELECT … FOR UPDATE`, always locking rows in the same (primary-key) order so two transfers
can't wait on each other forever (deadlock). Payment rows are locked before account rows, everywhere.

**Study first.**
- [PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html) (READ COMMITTED vs REPEATABLE READ vs SERIALIZABLE)
- [PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html) (row locks and the deadlock example)
- [Vlad Mihalcea: PESSIMISTIC_READ vs PESSIMISTIC_WRITE in JPA/Hibernate](https://vladmihalcea.com/hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work/)

**Key lines.** `@Lock(PESSIMISTIC_WRITE)` + `order by a.id`; `connection-init-sql: SET lock_timeout = '5s'`;
`@Transactional(propagation = MANDATORY, noRollbackFor = InsufficientFunds)` on `LedgerService.post`. Know
exactly why both attributes are there.

**Run it.** `ConcurrencyIT.noDoubleSpend`: 50 threads, a 2,000 balance, 100 each → exactly 20 succeed.

**Interview questions.**
1. Pessimistic vs optimistic locking: when would you choose each? (ADR-0002)
2. Why does lock *order* prevent deadlocks? What happens without it?
3. What does READ COMMITTED allow that causes the lost-update bug here?
4. The platform fee account is touched by every capture. What's the problem at 10k TPS, and how would you fix it?
5. Why is `Java UUID.compareTo` a trap for lock ordering?

---

## Module 3: Idempotency
**Code:** `idempotency/IdempotencyService`, unique constraints in V1, `PaymentFlowIT.idempotency`, `ConcurrencyIT.concurrentSameKey`, ADR-0003

**Concept.** A client that times out doesn't know if the payment happened. It retries with the same
`Idempotency-Key`; the server returns the original result instead of charging again.

**Study first.**
- [Stripe: Designing robust and predictable APIs with idempotency](https://stripe.com/blog/idempotency)
- [Brandur Leach: Implementing Stripe-like Idempotency Keys in Postgres](https://brandur.org/idempotency-keys)

**Key lines.** The claim step (`INSERT … ON CONFLICT DO NOTHING` in a `REQUIRES_NEW` transaction), the request
fingerprint (why canonical JSON?), the lease (`locked_until`) and take-over, why the key is deleted on failure, and
why the domain tables also have unique keys.

**Interview questions.**
1. Same key, different body: what should happen and why?
2. Two identical requests arrive at the same millisecond. Walk through what each does.
3. The server crashes after charging but before saving the response. What prevents a double charge on retry?
4. Why not just use a Redis `SETNX` for idempotency?

---

## Module 4: Payment lifecycle as a state machine
**Code:** `domain/payment/PaymentStatus`, `PaymentRules`, `payment/PaymentService`

**Concept.** Allowed transitions are explicit data (`allowedNext()`), and the rules are pure functions tested
without a database. The service: lock row → ask rules → post ledger → update → outbox, in one transaction.

**Interview questions.**
1. Why is "insufficient funds" a FAILED payment (201) and not an HTTP error?
2. Why can't you capture twice? Where is that enforced (two places)?
3. Why isn't the fee refunded? What would change if it were?

---

## Module 5: Calling an unreliable provider (top-ups)
**Code:** `topup/TopupService`, `TopupRecoveryJob`, `topup/psp/*`, resilience4j config in `application.yml`

**Concept.** Never hold a DB transaction open during a network call. Record PENDING (commit) → call provider
→ record outcome (new transaction). Timeouts leave PENDING; a recovery job finishes them. Retries are safe only
because the provider de-duplicates on our reference.

**Study first.**
- [AWS Builders' Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
- [Martin Fowler: Circuit Breaker](https://www.martinfowler.com/bliki/CircuitBreaker.html)
- [Resilience4j Spring Boot 3 demo](https://github.com/resilience4j/resilience4j-spring-boot3-demo)
- [Google SRE book, ch. 22: Addressing Cascading Failures](https://sre.google/sre-book/addressing-cascading-failures/)

**Interview questions.**
1. Why three steps instead of one transaction? What could go wrong with one?
2. When is it unsafe to retry a call? How did you make this one safe?
3. Circuit breaker states (closed / open / half-open): what problem does it solve that retries make worse?
4. The provider returned success but your process died before step 3. Walk through recovery.

---

## Module 6: Transactional outbox and Kafka
**Code:** `outbox/OutboxWriter`, `outbox/OutboxRelay`, `CoreConfig` topics, ADR-0004

**Concept.** Write the event to an `outbox_events` table in the business transaction; a relay publishes it.
No lost or phantom events, without distributed transactions. At-least-once delivery.

**Study first.**
- [microservices.io: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox) and [Transaction log tailing](https://microservices.io/patterns/data/transaction-log-tailing.html)
- [Confluent: Apache Kafka 101](https://developer.confluent.io/courses/apache-kafka/) (topics, partitions, keys, consumer groups, acks)

**Key lines.** `FOR UPDATE SKIP LOCKED` (why several relay instances don't collide), keying by aggregate id
(ordering), `break` on failure (ordering again), producer `acks=all` + `enable.idempotence`.

**Interview questions.**
1. What is the dual-write problem? Give both failure orders.
2. Why is delivery at-least-once and not exactly-once? Where are duplicates handled?
3. Polling vs CDC (Debezium): trade-offs?
4. How do you guarantee events for one payment are consumed in order?

---

## Module 7: Idempotent Kafka consumer, retries and dead letters
**Code:** `webhook/PaymentEventConsumer`, `webhook/KafkaConsumerConfig`

**Concept.** Record `event_id` in `processed_events` in the same DB transaction as the side effect; duplicates
become no-ops. Transient errors retry with backoff; poison messages go to `<topic>.DLT` so one bad message
doesn't block a partition.

**Study first.** [Spring for Apache Kafka: Handling Exceptions (DefaultErrorHandler, DeadLetterPublishingRecoverer)](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)

**Interview questions.**
1. Consumer crashes after the DB commit but before the offset commit: what happens next, and is it OK?
2. Why does the DLT destination use partition `-1`? (bug the default would cause)
3. What's a poison message and why must it not be retried forever?

---

## Module 8: Webhooks (signatures, backoff, leases)
**Code:** `webhook/WebhookDispatcher`, `domain/security/WebhookSigner`, `domain/retry/RetryBackoff`, `WebhookDeliveryIT`

**Concept.** Claim due deliveries with a lease (`UPDATE … WHERE id IN (SELECT … SKIP LOCKED) RETURNING`), send
over HTTP *outside* any transaction, sign with HMAC-SHA256 over `timestamp.payload`, retry with capped
exponential backoff + full jitter, give up as DEAD after N attempts.

**Study first.** The AWS backoff article above (full jitter); Stripe-style webhook signing (the timestamp
prevents replay, constant-time compare prevents timing attacks).

**Interview questions.**
1. Why include a timestamp inside the signed string?
2. Why `MessageDigest.isEqual` instead of `String.equals`?
3. A merchant is down for 6 hours. What happens to their events, and to everyone else's?
4. Why must merchants de-duplicate, and on what?

---

## Module 9: Reconciliation and distributed locking
**Code:** `reconciliation/ReconciliationService`

**Concept.** Checks run in a REPEATABLE READ snapshot (consistent view while payments continue) under
`pg_try_advisory_xact_lock` (only one instance runs it; released automatically on crash).

**Study first.**
- [Martin Kleppmann: How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html) (leases, fencing tokens; why "efficiency" locks and "correctness" locks differ)

**Interview questions.**
1. Why would READ COMMITTED produce false mismatches here?
2. Advisory lock vs Redis lock vs ShedLock: which is right here and why?
3. Reconciliation found a mismatch. What do you do (operationally and in code)?

---

## Module 10: Security
**Code:** `security/*`, `merchant/WebhookUrlValidator`, `CurrentClient.requireAccessToMerchant`, `SecurityIT`

**Concept.** Client-credentials → short-lived HS256 JWT with `roles` and `merchant_id` claims; method security
with `@PreAuthorize`; tenant isolation returns 404 for foreign resources (BOLA, OWASP API1); webhook URLs are
validated against private addresses (SSRF, OWASP API7); BCrypt secrets; constant-time-ish unknown-client path.

**Study first.**
- [Spring Security: OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) and the [OAuth2 overview](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html) (note: Spring Security provides `JwtEncoder`/`NimbusJwtEncoder` for minting tokens)
- [OWASP API Security Top 10 (2023)](https://github.com/OWASP/API-Security/tree/master/editions/2023/en), especially [API1 BOLA](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) and [API7 SSRF](https://owasp.org/API-Security/editions/2023/en/0xa7-server-side-request-forgery/)

**Interview questions.**
1. HS256 vs RS256: when would you switch, and what changes in this code?
2. Why 404 instead of 403 for another merchant's payment?
3. How could a webhook URL be used to attack your infrastructure? What's still not covered (DNS rebinding)?
4. How do you revoke a JWT before it expires?

---

## Module 11: Redis rate limiting and caching
**Code:** `security/RateLimitInterceptor`, `config/RedisCacheConfig`, `merchant/MerchantService`, ADR-0005

**Study first.** [Redis: Build 5 Rate Limiters (fixed window, sliding window, token bucket, leaky bucket)](https://redis.io/tutorials/howtos/ratelimiting/)

**Interview questions.**
1. Why a Lua script instead of INCR then EXPIRE?
2. Fixed window allows 2x bursts at boundaries. Show how; which algorithm fixes it?
3. Redis is down: what happens to rate limiting, caching, and payments? Why is that the right choice?
4. Why are balances never cached?

---

## Module 12: API design and errors
**Code:** `common/ApiErrorHandler`, controllers, `RequestIdFilter`

**Concept.** `/v1` versioning; RFC 9457 problem+json with stable `code`s; 201 vs 202 vs 409 vs 422; keyset
pagination for statements (why it beats OFFSET: see [Use The Index, Luke](https://use-the-index-luke.com/)).

**Interview questions.** 409 vs 422? Why keyset pagination? Why never return exception messages from the database?

---

## Module 13: Observability
**Code:** metrics in services, `observability/`, `application.yml` (management.*)

**Concept.** RED metrics (rate, errors, duration) from Micrometer; business metrics (payments by outcome, outbox
backlog, dead webhooks, reconciliation mismatches); structured logs with request/trace ids; liveness vs readiness probes.

**Interview questions.** Which 3 alerts would page you at 3 a.m.? What's the difference between liveness and readiness,
and what goes wrong if you get them backwards?

---

## Module 14: Testing strategy
**Code:** `src/test/java/com/payflow/domain/*Test`, `*IT`, `support/TestcontainersConfiguration`, `tools/offline-verification`

**Concept.** Pure domain logic → fast unit tests. Anything involving the database, locks, Kafka or Redis →
Testcontainers with the real thing (an H2 database would not test `FOR UPDATE SKIP LOCKED`, triggers or jsonb).

**Study first.** [Spring Boot: Testcontainers and service connections](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)

**Interview questions.** Why not mock the database for the concurrency test? How do you keep integration tests independent
when they share one database? (Answer in `IntegrationTest.newMerchant`.)

---

## Module 15: Containers, CI/CD, deployment
**Code:** `Dockerfile` (multi-stage, layered jar, non-root), `docker-compose.yml`, `.github/workflows/ci.yml`, `deploy/`, `docs/AWS_DEPLOYMENT.md`

**Study first.** [Kubernetes Tutorial for Beginners (TechWorld with Nana)](https://www.youtube.com/watch?v=X48VuDVv0do)

**Interview questions.** Why layered jars? Why OIDC instead of AWS keys in GitHub? ECS vs EKS for this service?
What happens to in-flight requests during a rolling deploy (graceful shutdown, preStop, readiness)?

---

## Your 2-minute project pitch (practise it)
"PayFlow is a payments backend: wallets, authorize/capture/refund, and merchant webhooks. The core is a
double-entry ledger in Postgres: every movement is a balanced journal entry, enforced in Java and by a deferred
database trigger, and a reconciliation job proves balances equal the sum of postings. Concurrency is handled with
row locks taken in a fixed order, and I tested it with 50 concurrent requests against a small balance: exactly the
right number succeed, and nothing overdraws. Every money-moving endpoint is idempotent at two layers, an
Idempotency-Key replay layer and unique constraints. Events go through a transactional outbox to Kafka, and an
idempotent consumer creates HMAC-signed webhooks delivered with exponential backoff and jitter. The payment provider
sits behind retries and a circuit breaker, and network calls never run inside DB transactions. It's a modular
monolith on purpose: money movement needs one ACID transaction, so microservices would have meant sagas for no benefit."

Then be ready to go one level deeper on any sentence. The interview questions above are exactly those deeper levels.
