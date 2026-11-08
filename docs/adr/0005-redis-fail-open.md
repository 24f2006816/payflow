# ADR-0005: Redis is an optimisation, never a dependency for correctness

**Status:** accepted

## Decision
Redis is used for (1) per-client rate limiting and (2) caching merchant profiles. Both **fail open**:
- Rate limiter: on any Redis error the request is allowed and `payflow_ratelimit_redis_errors_total` increments.
- Cache: a custom `CacheErrorHandler` logs and falls through to Postgres.

Balances, idempotency keys and locks are **never** stored in Redis; they live in Postgres where they are
transactional and durable.

## Why
A payments API that returns errors because a cache is down converts a minor incident into an outage. The worst
case of failing open is temporarily weaker abuse protection, which is visible in metrics and can be backstopped at
the load balancer / WAF.

## Consequences
- ⚠️ During a Redis outage clients can exceed limits; alert on the error counter.
- ⚠️ Merchant cache entries can be stale for up to 10 minutes if an eviction fails (logged at ERROR).
