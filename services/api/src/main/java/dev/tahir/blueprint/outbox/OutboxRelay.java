package dev.tahir.blueprint.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.tahir.blueprint.platform.events.EventTypes;
import dev.tahir.blueprint.platform.observability.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes committed outbox rows to Kafka.
 *
 * <p>Polling rather than change data capture. Debezium would give lower latency and no
 * polling load, at the cost of a Kafka Connect cluster and a replication slot to operate —
 * roughly a gigabyte of extra footprint for a service that publishes a handful of events
 * per second. The trade-off is recorded in docs/adr/0005-outbox-polling-over-cdc.md.
 *
 * <p>This runs in every API replica. Concurrency is safe because the claim query uses
 * {@code FOR UPDATE SKIP LOCKED}, so replicas take disjoint batches.
 *
 * <p>Publishing is synchronous per batch on purpose: the row is marked published only after
 * the broker acknowledges. A fire-and-forget send would mark rows published that the broker
 * never accepted, turning at-least-once into at-most-once without anyone noticing.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter published;
    private final Counter failed;
    private final int batchSize;
    private final int maxAttempts;
    private final long sendTimeoutSeconds;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${blueprint.outbox.batch-size:100}") int batchSize,
            @Value("${blueprint.outbox.max-attempts:10}") int maxAttempts,
            @Value("${blueprint.outbox.send-timeout-seconds:10}") long sendTimeoutSeconds) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.published = Counter.builder("blueprint.outbox.published")
                .description("Outbox events successfully published to Kafka")
                .register(meterRegistry);
        this.failed = Counter.builder("blueprint.outbox.publish.failures")
                .description("Outbox publish attempts that did not reach the broker")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${blueprint.outbox.poll-interval:PT1S}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.claimUnpublished(maxAttempts, Limit.of(batchSize));
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            CorrelationId.scoped(event.getCorrelationId(), () -> publish(event));
        }
    }

    private void publish(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                EventTypes.TOPIC_ITEM_EVENTS,
                // Partition key is the aggregate id, so all events for one item land on one
                // partition and are therefore consumed in the order they were produced.
                // Keying by event id instead would scatter them and lose per-item ordering.
                event.getAggregateId(),
                event.getPayload());

        record.headers().add(header(EventTypes.HEADER_EVENT_ID, event.getId().toString()));
        record.headers().add(header(EventTypes.HEADER_EVENT_TYPE, event.getEventType()));
        if (event.getCorrelationId() != null) {
            record.headers().add(header(EventTypes.HEADER_CORRELATION_ID, event.getCorrelationId()));
        }

        try {
            kafkaTemplate.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            event.markPublished();
            published.increment();
            log.debug("outbox event published id={} type={}", event.getId(), event.getEventType());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            event.recordFailure("interrupted");
            failed.increment();
        } catch (Exception ex) {
            // Left unpublished so the next poll retries it. attempts is bumped so a
            // permanently bad event eventually stops consuming broker capacity and shows up
            // in the stuck gauge instead of retrying forever.
            event.recordFailure(ex.getMessage());
            failed.increment();
            log.warn(
                    "outbox publish failed id={} type={} attempt={} reason={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAttempts(),
                    ex.getMessage());
        }
    }

    private static RecordHeader header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
