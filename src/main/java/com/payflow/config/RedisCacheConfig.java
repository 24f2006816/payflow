package com.payflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.merchant.MerchantView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis cache for read-mostly reference data (merchant profiles).
 * Balances are NEVER cached: they must be read under a row lock from Postgres.
 * <p>
 * The cache is fail-open: if Redis is down we log and fall through to the database,
 * so a cache outage degrades latency, not availability.
 */
@Configuration(proxyBeanMethods = false)
public class RedisCacheConfig implements CachingConfigurer {

    public static final String MERCHANTS = "merchants";
    private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

    @Bean
    RedisCacheManagerBuilderCustomizer merchantCacheCustomizer(ObjectMapper objectMapper) {
        var serializer = new Jackson2JsonRedisSerializer<>(objectMapper, MerchantView.class);
        return builder -> builder.withCacheConfiguration(MERCHANTS,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(10))
                        .disableCachingNullValues()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("cache get failed (falling back to DB) cache={} key={}: {}", cache.getName(), key, e.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("cache put failed cache={} key={}: {}", cache.getName(), key, e.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("cache evict failed cache={} key={} (entry may be stale until TTL): {}",
                        cache.getName(), key, e.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("cache clear failed cache={}: {}", cache.getName(), e.toString());
            }
        };
    }
}
