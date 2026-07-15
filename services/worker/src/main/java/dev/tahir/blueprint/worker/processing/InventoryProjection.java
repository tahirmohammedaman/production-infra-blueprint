package dev.tahir.blueprint.worker.processing;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.tahir.blueprint.platform.events.EventEnvelope;
import dev.tahir.blueprint.platform.events.EventTypes;

/**
 * Maintains the inventory read model from the event stream.
 *
 * <p>The worker never reads the API's database. Schema ownership is the boundary that makes
 * these two services independently deployable, and a worker with a SELECT on {@code items}
 * is a distributed monolith with extra steps. The projection is therefore built from the
 * deltas the events carry, which is why {@code ItemChanged} includes
 * {@code previousQuantity} — without it an update event cannot be applied incrementally.
 *
 * <p>Counters are mutated with Redis {@code INCRBY}, which is atomic. Read-modify-write
 * would race between worker replicas consuming different partitions concurrently and lose
 * updates silently.
 *
 * <p>Incremental projections drift — a lost event or an out-of-window redelivery leaves the
 * counters slightly wrong forever. That is accepted here and corrected by a periodic
 * reconciler on the API side, which owns the database and can recompute the truth. Fast
 * path from events, slow path from the source of record.
 */
@Component
public class InventoryProjection {

    private static final Logger log = LoggerFactory.getLogger(InventoryProjection.class);

    static final String KEY_DISTINCT = "blueprint:inventory:distinct";
    static final String KEY_QUANTITY = "blueprint:inventory:quantity";

    private final StringRedisTemplate counters;
    private final RedisTemplate<String, InventorySummary> summaries;
    private final ObjectMapper objectMapper;

    public InventoryProjection(
            StringRedisTemplate counters,
            RedisTemplate<String, InventorySummary> summaries,
            ObjectMapper objectMapper) {
        this.counters = counters;
        this.summaries = summaries;
        this.objectMapper = objectMapper;
    }

    public void apply(EventEnvelope event) {
        EventEnvelope.ItemChanged payload = event.payload();
        long distinctDelta;
        long quantityDelta;

        switch (event.type()) {
            case EventTypes.ITEM_CREATED -> {
                distinctDelta = 1;
                quantityDelta = payload.quantity();
            }
            case EventTypes.ITEM_UPDATED -> {
                distinctDelta = 0;
                // previousQuantity is required for updates; without it the delta is unknowable
                // and applying the absolute value would double-count.
                if (payload.previousQuantity() == null) {
                    throw new IllegalArgumentException(
                            "item.updated event " + event.eventId() + " has no previousQuantity");
                }
                quantityDelta = (long) payload.quantity() - payload.previousQuantity();
            }
            case EventTypes.ITEM_DELETED -> {
                distinctDelta = -1;
                quantityDelta = payload.previousQuantity() == null ? 0 : -payload.previousQuantity();
            }
            default -> throw new IllegalArgumentException("unknown event type: " + event.type());
        }

        long distinct = increment(KEY_DISTINCT, distinctDelta);
        long quantity = increment(KEY_QUANTITY, quantityDelta);

        summaries
                .opsForValue()
                .set(InventorySummary.CACHE_KEY, new InventorySummary(distinct, quantity, Instant.now()));

        log.info(
                "projection applied type={} aggregate={} distinct={} quantity={}",
                event.type(),
                event.aggregateId(),
                distinct,
                quantity);
    }

    private long increment(String key, long delta) {
        if (delta == 0) {
            Long current = parse(counters.opsForValue().get(key));
            return current == null ? 0 : current;
        }
        Long updated = counters.opsForValue().increment(key, delta);
        return updated == null ? 0 : updated;
    }

    private static Long parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Exposed for the deserialisation step in the listener so the mapper stays configured in one place. */
    public EventEnvelope deserialise(String payload) {
        try {
            return objectMapper.readValue(payload, EventEnvelope.class);
        } catch (Exception ex) {
            // Not retryable: a record that will not parse now will not parse later either.
            throw new IllegalArgumentException("malformed event payload", ex);
        }
    }
}
