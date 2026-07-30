package dev.tahir.blueprint.cache;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.tahir.blueprint.domain.ItemRepository;
import dev.tahir.blueprint.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Corrects drift in the event-sourced inventory projection.
 *
 * <p>The worker maintains the projection incrementally from event deltas, which is fast and
 * decoupled but not self-correcting: a lost event, a redelivery outside the idempotency
 * window, or a manual database fix leaves the counters permanently wrong, and nothing in
 * the event path will ever notice. Incremental projections that are never reconciled do not
 * converge — they diverge slowly enough that nobody sees it until the number is absurd.
 *
 * <p>This runs in the API because the API owns the database and can therefore compute the
 * truth. It writes the corrected values back to the same counters the worker increments, so
 * the two paths meet rather than fight: events give freshness, reconciliation gives
 * correctness.
 *
 * <p>It only corrects when nothing is in flight. The projection is legitimately behind the
 * database by every event committed but not yet applied, and correcting it then writes the
 * database's totals over counters those events are about to increment: each of them is then
 * counted twice, and the next run reports as drift an error the previous one created. Under
 * steady writes that would happen on almost every run. A run that finds events in flight is
 * deferred, and counted, and the next run tries again.
 *
 * <p>The drift counter is exported deliberately. A reconciler that silently fixes things
 * hides a bug in the event path; one that reports how much it had to fix turns that bug
 * into an alert.
 */
@Component
public class InventoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciler.class);

    static final String KEY_DISTINCT = "blueprint:inventory:distinct";
    static final String KEY_QUANTITY = "blueprint:inventory:quantity";

    private final ItemRepository items;
    private final OutboxRepository outbox;
    private final StringRedisTemplate counters;
    private final RedisTemplate<String, InventorySummary> summaries;
    private final Counter driftCorrections;
    private final Counter deferrals;
    private final boolean enabled;
    private final Duration quietPeriod;
    private final int maxAttempts;

    public InventoryReconciler(
            ItemRepository items,
            OutboxRepository outbox,
            StringRedisTemplate counters,
            RedisTemplate<String, InventorySummary> summaries,
            MeterRegistry registry,
            @Value("${blueprint.reconcile.enabled:true}") boolean enabled,
            @Value("${blueprint.reconcile.quiet-period:PT30S}") Duration quietPeriod,
            @Value("${blueprint.outbox.max-attempts:10}") int maxAttempts) {
        this.items = items;
        this.outbox = outbox;
        this.counters = counters;
        this.summaries = summaries;
        this.enabled = enabled;
        this.quietPeriod = quietPeriod;
        this.maxAttempts = maxAttempts;
        this.driftCorrections = Counter.builder("blueprint.projection.drift.corrections")
                .description("Reconciliation runs that found the projection disagreeing with the database")
                .register(registry);
        this.deferrals = Counter.builder("blueprint.projection.reconcile.deferred")
                .description("Reconciliation runs skipped because events were still on their way to the projection")
                .register(registry);
    }

    @Scheduled(
            initialDelayString = "${blueprint.reconcile.initial-delay:PT30S}",
            fixedDelayString = "${blueprint.reconcile.interval:PT5M}")
    @Transactional(readOnly = true)
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            long actualDistinct = items.count();
            long actualQuantity = items.sumQuantity();

            // Checked after reading the totals, not before. Any event committed before that
            // read is then still in the outbox or was published within the quiet period, and
            // either way this run stands down. What is left is an event that commits after the
            // read and is applied before the write below: a window of milliseconds, which the
            // next run repairs.
            if (outbox.countInFlight(maxAttempts, Instant.now().minus(quietPeriod)) > 0) {
                deferrals.increment();
                log.debug("reconciliation deferred, events are still on their way to the projection");
                return;
            }

            Long projectedDistinct = readCounter(KEY_DISTINCT);
            Long projectedQuantity = readCounter(KEY_QUANTITY);

            boolean drifted = projectedDistinct == null
                    || projectedQuantity == null
                    || projectedDistinct != actualDistinct
                    || projectedQuantity != actualQuantity;

            if (drifted) {
                driftCorrections.increment();
                log.warn(
                        "projection drift corrected distinct={}->{} quantity={}->{}",
                        projectedDistinct,
                        actualDistinct,
                        projectedQuantity,
                        actualQuantity);
            }

            counters.opsForValue().set(KEY_DISTINCT, Long.toString(actualDistinct));
            counters.opsForValue().set(KEY_QUANTITY, Long.toString(actualQuantity));
            summaries
                    .opsForValue()
                    .set(
                            InventorySummary.CACHE_KEY,
                            new InventorySummary(actualDistinct, actualQuantity, Instant.now()));
        } catch (DataAccessException ex) {
            // Redis unreachable. The next run will reconcile; failing loudly here would only
            // add noise to an outage the readiness probe is already reporting.
            log.warn("reconciliation skipped, cache unavailable: {}", ex.getMessage());
        }
    }

    private Long readCounter(String key) {
        String raw = counters.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
