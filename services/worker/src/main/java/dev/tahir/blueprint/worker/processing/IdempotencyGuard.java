package dev.tahir.blueprint.worker.processing;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Deduplicates redelivered events.
 *
 * <p>Kafka gives at-least-once delivery and the outbox relay can republish after a crash,
 * so the same event id will be seen more than once. Anything with a side effect has to be
 * guarded, and "it probably won't happen" is not a guard.
 *
 * <p>Implemented with {@code SET key value NX EX ttl}, which is atomic in Redis — a
 * get-then-set would race between two worker replicas consuming the same partition during a
 * rebalance and let both through.
 *
 * <p>The TTL is a deliberate bound, not laziness. Keys have to expire or the set grows
 * forever; the window only needs to outlive the longest plausible redelivery, which is
 * bounded by the outbox retry budget. Beyond the window a redelivery would be reprocessed,
 * which is why the handler's work is also written to be naturally idempotent — the guard
 * saves the effort, correctness does not depend on it alone.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);
    private static final String KEY_PREFIX = "blueprint:processed:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyGuard(
            StringRedisTemplate redis, @Value("${blueprint.consumer.idempotency-ttl:PT24H}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /**
     * @return true when this event id has not been seen before and processing should proceed
     */
    public boolean claim(UUID eventId) {
        Boolean claimed = redis.opsForValue().setIfAbsent(KEY_PREFIX + eventId, "1", ttl);
        if (Boolean.TRUE.equals(claimed)) {
            return true;
        }
        log.debug("skipping duplicate delivery of event {}", eventId);
        return false;
    }

    /**
     * Releases a claim so a failed event can be retried.
     *
     * <p>Without this, a handler that throws after claiming would have its retry silently
     * skipped as a duplicate and the event would be lost — the guard would have turned
     * at-least-once into at-most-once.
     */
    public void release(UUID eventId) {
        redis.delete(KEY_PREFIX + eventId);
    }
}
