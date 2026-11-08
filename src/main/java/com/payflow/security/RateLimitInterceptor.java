package com.payflow.security;

import com.payflow.common.RateLimitExceededException;
import com.payflow.config.PayflowProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.util.List;

/**
 * Fixed-window rate limiter in Redis: one counter per client per minute, incremented atomically by a
 * Lua script (INCR + EXPIRE in one round trip, so a crash can't leave a counter without a TTL).
 * <p>
 * Trade-off: fixed windows allow up to 2x the limit across a window boundary. Good enough for abuse
 * protection; Gatekeeper (project 2 style) would use a sliding window or token bucket.
 * <p>
 * Fail-open: if Redis is unreachable we allow the request and count the error. Availability of the
 * payments API matters more than perfect throttling during a cache outage.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int limit;
    private final Clock clock;
    private final Counter rejected;
    private final Counter redisErrors;

    public RateLimitInterceptor(StringRedisTemplate redis, PayflowProperties props, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.limit = props.rateLimit().requestsPerMinute();
        this.clock = clock;
        this.rejected = Counter.builder("payflow.ratelimit.rejected").register(meters);
        this.redisErrors = Counter.builder("payflow.ratelimit.redis_errors").register(meters);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long epochSecond = clock.instant().getEpochSecond();
        long window = epochSecond / 60;
        long secondsLeft = 60 - (epochSecond % 60);
        String key = "rl:" + subject(request) + ":" + window;

        Long count;
        try {
            count = redis.execute(INCR_WITH_TTL, List.of(key), "61");
        } catch (DataAccessException e) {
            redisErrors.increment();
            log.warn("rate limiter unavailable, failing open: {}", e.toString());
            return true;
        }
        long used = count == null ? 0 : count;
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        if (used > limit) {
            rejected.increment();
            throw new RateLimitExceededException(secondsLeft);
        }
        return true;
    }

    private static String subject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "client:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
