package dev.tahir.blueprint.cache;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tahir.blueprint.domain.ItemRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cache-aside read of the inventory summary.
 *
 * <p>The worker keeps the cached value fresh in response to item events. This service reads
 * it, and falls back to computing it from Postgres on a miss.
 *
 * <p>The fallback is not optional politeness — it is what makes Redis a cache rather than a
 * second system of record. A Redis outage degrades this endpoint to a slower database query
 * instead of failing it, which is why Redis gates readiness at startup but a mid-flight
 * Redis failure does not take the pod out of rotation.
 */
@Service
public class InventorySummaryService {

    private static final Logger log = LoggerFactory.getLogger(InventorySummaryService.class);

    private final RedisTemplate<String, InventorySummary> redis;
    private final ItemRepository items;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public InventorySummaryService(
            RedisTemplate<String, InventorySummary> redis, ItemRepository items, MeterRegistry registry) {
        this.redis = redis;
        this.items = items;
        this.hits = Counter.builder("blueprint.cache.requests")
                .tag("result", "hit")
                .description("Inventory summary reads served from Redis")
                .register(registry);
        this.misses = Counter.builder("blueprint.cache.requests")
                .tag("result", "miss")
                .description("Inventory summary reads that fell back to the database")
                .register(registry);
        this.errors = Counter.builder("blueprint.cache.requests")
                .tag("result", "error")
                .description("Inventory summary reads where Redis was unreachable")
                .register(registry);
    }

    @Transactional(readOnly = true)
    public InventorySummary get() {
        try {
            InventorySummary cached = redis.opsForValue().get(InventorySummary.CACHE_KEY);
            if (cached != null) {
                hits.increment();
                return cached;
            }
            misses.increment();
        } catch (DataAccessException ex) {
            // Redis is down. Degrade to the database rather than propagating: this endpoint
            // is a read, and a slow correct answer beats a 500.
            errors.increment();
            log.warn("inventory summary cache unavailable, computing from database: {}", ex.getMessage());
        }
        return computeFromDatabase();
    }

    private InventorySummary computeFromDatabase() {
        long distinct = items.count();
        long total = items.sumQuantity();
        return new InventorySummary(distinct, total, Instant.now());
    }
}
