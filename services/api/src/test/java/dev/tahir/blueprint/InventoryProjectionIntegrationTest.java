package dev.tahir.blueprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import dev.tahir.blueprint.cache.InventoryReconciler;
import dev.tahir.blueprint.cache.InventorySummary;
import dev.tahir.blueprint.domain.Item;
import dev.tahir.blueprint.domain.ItemRepository;
import dev.tahir.blueprint.outbox.OutboxEvent;
import dev.tahir.blueprint.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Covers the read side of the async path: the cache-aside lookup the API serves, and the
 * reconciler that repairs the projection when the event path has lost or double-applied
 * something.
 *
 * <p>Reconciliation is the part of an event-sourced read model that is easiest to leave
 * untested, because everything looks correct as long as no event is ever missed. It is also
 * the only reason the projection converges rather than drifting; a reconciler that has
 * never been executed is a comment, not a control.
 *
 * <p>The reconciler is constructed here rather than injected because the shared test context
 * disables its schedule — see {@link IntegrationTestBase}. Driving it directly is also what
 * makes the assertions deterministic instead of dependent on a timer.
 */
class InventoryProjectionIntegrationTest extends IntegrationTestBase {

    private static final String KEY_DISTINCT = "blueprint:inventory:distinct";
    private static final String KEY_QUANTITY = "blueprint:inventory:quantity";
    private static final int MAX_ATTEMPTS = 10;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ItemRepository items;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private StringRedisTemplate counters;

    @Autowired
    private RedisTemplate<String, InventorySummary> summaries;

    @Autowired
    private MeterRegistry registry;

    private InventoryReconciler reconciler;

    @BeforeEach
    void reset() {
        // The outbox is cleared too: rows left by another test class would look like events in
        // flight and defer every reconciliation here.
        outbox.deleteAll();
        items.deleteAll();
        counters.delete(KEY_DISTINCT);
        counters.delete(KEY_QUANTITY);
        summaries.delete(InventorySummary.CACHE_KEY);
        reconciler = reconciler(true);
    }

    @Test
    @DisplayName("serves the cached summary without touching the database")
    void servesFromCacheWhenWarm() {
        items.save(newItem("cached-widget", 7));
        // Deliberately disagrees with the database: if the endpoint recomputed instead of
        // reading the cache, it would return 1/7 and this assertion would catch it.
        summaries.opsForValue().set(InventorySummary.CACHE_KEY, new InventorySummary(41, 99, Instant.now()));

        InventorySummary body = rest.getForObject("/api/v1/inventory/summary", InventorySummary.class);

        assertThat(body).isNotNull();
        assertThat(body.distinctItems()).isEqualTo(41);
        assertThat(body.totalQuantity()).isEqualTo(99);
        assertThat(counterValue("hit")).isPositive();
    }

    @Test
    @DisplayName("falls back to the database on a cache miss rather than failing the read")
    void computesFromDatabaseOnMiss() {
        items.save(newItem("uncached-widget", 3));
        items.save(newItem("uncached-gadget", 5));

        InventorySummary body = rest.getForObject("/api/v1/inventory/summary", InventorySummary.class);

        assertThat(body).isNotNull();
        assertThat(body.distinctItems()).isEqualTo(2);
        assertThat(body.totalQuantity()).isEqualTo(8);
        assertThat(counterValue("miss")).isPositive();
    }

    @Test
    @DisplayName("rewrites the projection and reports the drift when the counters disagree")
    void correctsDriftedCounters() {
        items.save(newItem("drifted-widget", 6));
        // What a lost event looks like from the outside: the projection is simply behind,
        // and nothing in the event path will ever notice.
        counters.opsForValue().set(KEY_DISTINCT, "0");
        counters.opsForValue().set(KEY_QUANTITY, "0");
        double before = driftCorrections();

        reconciler.reconcile();

        assertThat(counters.opsForValue().get(KEY_DISTINCT)).isEqualTo("1");
        assertThat(counters.opsForValue().get(KEY_QUANTITY)).isEqualTo("6");
        assertThat(driftCorrections()).isGreaterThan(before);

        InventorySummary rebuilt = summaries.opsForValue().get(InventorySummary.CACHE_KEY);
        assertThat(rebuilt).isNotNull();
        assertThat(rebuilt.distinctItems()).isEqualTo(1);
        assertThat(rebuilt.totalQuantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("treats an unparseable counter as drift rather than throwing")
    void treatsCorruptCounterAsDrift() {
        items.save(newItem("corrupt-widget", 2));
        counters.opsForValue().set(KEY_DISTINCT, "not-a-number");
        counters.opsForValue().set(KEY_QUANTITY, "1");
        double before = driftCorrections();

        reconciler.reconcile();

        assertThat(driftCorrections()).isGreaterThan(before);
        assertThat(counters.opsForValue().get(KEY_DISTINCT)).isEqualTo("1");
    }

    @Test
    @DisplayName("does not report drift when the projection already agrees with the database")
    void reportsNoDriftWhenProjectionIsCorrect() {
        items.save(newItem("agreeing-widget", 4));
        counters.opsForValue().set(KEY_DISTINCT, "1");
        counters.opsForValue().set(KEY_QUANTITY, "4");
        double before = driftCorrections();

        reconciler.reconcile();

        assertThat(driftCorrections()).isEqualTo(before);
    }

    @Test
    @DisplayName("stands down while an event is still on its way to the projection")
    void defersWhileAnEventIsUnpublished() {
        // The item is committed and its event is in the outbox: the projection is behind by
        // exactly this item, legitimately. Correcting now would count it twice once the event
        // lands on top of the corrected totals.
        items.save(newItem("in-flight-widget", 5));
        outbox.save(newEvent());
        counters.opsForValue().set(KEY_DISTINCT, "0");
        counters.opsForValue().set(KEY_QUANTITY, "0");
        double driftBefore = driftCorrections();
        double deferredBefore = deferrals();

        reconciler.reconcile();

        assertThat(counters.opsForValue().get(KEY_QUANTITY)).isEqualTo("0");
        assertThat(driftCorrections()).isEqualTo(driftBefore);
        assertThat(deferrals()).isGreaterThan(deferredBefore);
    }

    @Test
    @DisplayName("stands down while a just-published event may not have been applied yet")
    void defersWithinTheQuietPeriodAfterAPublish() {
        items.save(newItem("just-published-widget", 5));
        OutboxEvent published = newEvent();
        published.markPublished();
        outbox.save(published);
        counters.opsForValue().set(KEY_QUANTITY, "0");
        double deferredBefore = deferrals();

        reconciler.reconcile();

        assertThat(counters.opsForValue().get(KEY_QUANTITY)).isEqualTo("0");
        assertThat(deferrals()).isGreaterThan(deferredBefore);
    }

    @Test
    @DisplayName("reconciles past stuck events, because they will never arrive")
    void correctsDespiteStuckEvents() {
        // A stuck event is the one case where the projection is behind for good. Deferring on
        // it would switch the reconciler off precisely when it is needed.
        items.save(newItem("stuck-widget", 5));
        OutboxEvent stuck = newEvent();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            stuck.recordFailure("rejected by the broker");
        }
        outbox.save(stuck);
        counters.opsForValue().set(KEY_DISTINCT, "0");
        counters.opsForValue().set(KEY_QUANTITY, "0");

        reconciler.reconcile();

        assertThat(counters.opsForValue().get(KEY_QUANTITY)).isEqualTo("5");
    }

    @Test
    @DisplayName("does nothing at all when reconciliation is switched off")
    void skipsEntirelyWhenDisabled() {
        items.save(newItem("ignored-widget", 9));
        InventoryReconciler disabled = reconciler(false);

        disabled.reconcile();

        // The kill switch has to be real: it is what an operator reaches for when the
        // reconciler itself is the thing misbehaving.
        assertThat(counters.opsForValue().get(KEY_DISTINCT)).isNull();
        assertThat(summaries.opsForValue().get(InventorySummary.CACHE_KEY)).isNull();
    }

    private InventoryReconciler reconciler(boolean enabled) {
        return new InventoryReconciler(
                items, outbox, counters, summaries, registry, enabled, Duration.ofSeconds(30), MAX_ATTEMPTS);
    }

    private double driftCorrections() {
        return registry.get("blueprint.projection.drift.corrections").counter().count();
    }

    private double deferrals() {
        return registry.get("blueprint.projection.reconcile.deferred").counter().count();
    }

    private double counterValue(String result) {
        return registry.get("blueprint.cache.requests")
                .tag("result", result)
                .counter()
                .count();
    }

    private Item newItem(String name, int quantity) {
        return new Item(UUID.randomUUID(), name, "projection test fixture", quantity);
    }

    private static OutboxEvent newEvent() {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID().toString(), "item.created", null, "{}");
    }
}
