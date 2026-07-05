package dev.tahir.blueprint.observability;

import dev.tahir.blueprint.domain.ItemService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Business-level gauge exported alongside the JVM and HTTP meters.
 *
 * <p>The value is refreshed on a schedule rather than computed inside the gauge callback:
 * a gauge that queries the database is executed once per scrape per replica, which turns
 * a monitoring system into a load generator.
 */
@Component
public class ItemMetrics {

    private static final Logger log = LoggerFactory.getLogger(ItemMetrics.class);

    private final ItemService itemService;
    private final AtomicLong itemCount = new AtomicLong();

    public ItemMetrics(ItemService itemService, MeterRegistry registry) {
        this.itemService = itemService;
        registry.gauge("blueprint.items.total", itemCount);
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
