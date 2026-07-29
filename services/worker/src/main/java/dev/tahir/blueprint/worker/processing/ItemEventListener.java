package dev.tahir.blueprint.worker.processing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.tahir.blueprint.platform.events.EventEnvelope;
import dev.tahir.blueprint.platform.events.EventTypes;
import dev.tahir.blueprint.platform.observability.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Consumes item events and updates the inventory read model.
 *
 * <p>Order of operations matters and is deliberate: parse, claim, apply, and release the
 * claim if applying failed. Claiming before doing the work is what makes a redelivery a
 * no-op; releasing on failure is what stops the guard from swallowing a legitimate retry.
 */
@Component
public class ItemEventListener {

    private static final Logger log = LoggerFactory.getLogger(ItemEventListener.class);

    private final InventoryProjection projection;
    private final IdempotencyGuard idempotency;
    private final Counter processed;
    private final Counter duplicates;
    private final Timer latency;

    public ItemEventListener(InventoryProjection projection, IdempotencyGuard idempotency, MeterRegistry registry) {
        this.projection = projection;
        this.idempotency = idempotency;
        this.processed = Counter.builder("blueprint.events.processed")
                .description("Item events successfully applied to the projection")
                .register(registry);
        this.duplicates = Counter.builder("blueprint.events.duplicates")
                .description("Redelivered events skipped by the idempotency guard")
                .register(registry);
        // Bucket boundaries come from management.metrics.distribution.slo in application.yml,
        // where they sit next to the objective they serve rather than in code.
        this.latency = Timer.builder("blueprint.events.end.to.end")
                .description("Time from the originating transaction commit to projection update")
                .register(registry);
    }

    @KafkaListener(
            topics = EventTypes.TOPIC_ITEM_EVENTS,
            groupId = "${blueprint.consumer.group-id:blueprint-worker}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onItemEvent(ConsumerRecord<String, String> record) {
        String correlationId = headerValue(record, EventTypes.HEADER_CORRELATION_ID);

        CorrelationId.scoped(correlationId != null ? correlationId : CorrelationId.current(), () -> {
            EventEnvelope event = projection.deserialise(record.value());
            UUID eventId = event.eventId();

            if (!idempotency.claim(eventId)) {
                duplicates.increment();
                return;
            }

            try {
                projection.apply(event);
                processed.increment();
                // Measured from the originating transaction, not from when the record was
                // produced. This is the number that answers "how stale is the read model",
                // and it includes outbox relay latency, which publish-time timing hides.
                latency.record(java.time.Duration.between(event.occurredAt(), java.time.Instant.now()));
            } catch (RuntimeException ex) {
                idempotency.release(eventId);
                log.warn(
                        "event processing failed, releasing idempotency claim eventId={} type={} reason={}",
                        eventId,
                        event.type(),
                        ex.getMessage());
                throw ex;
            }
        });
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
