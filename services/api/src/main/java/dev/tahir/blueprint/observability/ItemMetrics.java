package dev.tahir.blueprint.observability;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.tahir.blueprint.domain.ItemService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business-level gauge exported alongside the JVM and HTTP meters.
 *
 * <p>The value is refreshed on a schedule rather than computed inside the gauge callback:
 * a gauge that queries the database is executed once per scrape per replica, which turns
 * a monitoring system into a load generator.
 *
 * <p>The meter is named {@code blueprint.items} and not {@code blueprint.items.total}.
 * Micrometer's Prometheus naming convention reserves the {@code _total} suffix for
 * counters and strips it from gauges without warning, so the "obvious" name would have
 * been published as {@code blueprint_items} anyway — and every dashboard and alert
 * written against the intended name would have silently matched nothing.
 */
@Component
public class ItemMetrics {

    private static final Logger log = LoggerFactory.getLogger(ItemMetrics.class);

    private final ItemService itemService;
    private final AtomicLong itemCount = new AtomicLong();

    public ItemMetrics(ItemService itemService, MeterRegistry registry) {
        this.itemService = itemService;
        Gauge.builder("blueprint.items", itemCount, AtomicLong::doubleValue)
                .description("Number of inventory items currently stored")
                .baseUnit("items")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${blueprint.metrics.item-count-refresh:PT30S}")
    public void refresh() {
        try {
            itemCount.set(itemService.count());
        } catch (RuntimeException ex) {
            // A stale gauge is better than a failed scheduler thread during a database blip.
            log.debug("item count refresh skipped: {}", ex.getMessage());
        }
    }
}
