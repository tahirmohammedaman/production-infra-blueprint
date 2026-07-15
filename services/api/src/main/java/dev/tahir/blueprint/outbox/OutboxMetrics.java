package dev.tahir.blueprint.outbox;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Outbox depth and stuck-event count.
 *
 * <p>These are the two numbers that tell you the async path is broken while every HTTP
 * metric still looks perfectly healthy. A growing backlog means the relay cannot reach the
 * broker; a non-zero stuck count means events have exhausted their attempts and will never
 * be delivered without intervention. Both have alerts in
 * observability/prometheus/rules/alerts.yml.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository repository;
    private final int maxAttempts;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();

    public OutboxMetrics(
            OutboxRepository repository,
            MeterRegistry registry,
            @Value("${blueprint.outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;

        Gauge.builder("blueprint.outbox.pending", pending, AtomicLong::doubleValue)
                .description("Outbox events written but not yet published to Kafka")
                .baseUnit("events")
                .register(registry);
        Gauge.builder("blueprint.outbox.stuck", stuck, AtomicLong::doubleValue)
                .description("Outbox events that exhausted their publish attempts")
                .baseUnit("events")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${blueprint.outbox.metrics-interval:PT15S}")
    public void refresh() {
        try {
            pending.set(repository.countByPublishedAtIsNull());
            stuck.set(repository.countStuck(maxAttempts));
        } catch (RuntimeException ex) {
            // A stale gauge beats a dead scheduler thread during a database blip.
            log.debug("outbox metrics refresh skipped: {}", ex.getMessage());
        }
    }
}
