package dev.tahir.blueprint.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Readiness probe for the cache.
 *
 * <p>Included in the readiness group because a pod that starts before Redis is reachable
 * would serve every read from the database and quietly triple the query load — the exact
 * failure the cache exists to prevent, and one that looks fine on the HTTP dashboards.
 *
 * <p>Separate from liveness for the same reason as the database indicator: a dependency
 * outage should remove the pod from rotation, never restart it.
 */
@Component("cacheReadiness")
public class RedisReadinessIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisReadinessIndicator.class);

    private final RedisConnectionFactory connectionFactory;

    public RedisReadinessIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("latencyMs", latencyMs)
                    .withDetail("ping", pong)
                    .build();
        } catch (RuntimeException ex) {
            log.warn("cache readiness check failed: {}", ex.getMessage());
            return Health.down()
                    .withDetail("error", ex.getClass().getSimpleName())
                    .build();
        }
    }
}
