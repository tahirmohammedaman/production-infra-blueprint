package dev.tahir.blueprint.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
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
 *
 * <p>Failures come in two kinds and are treated differently. A broker that cannot be reached
 * says nothing about the event, so it costs the event nothing and ends the batch; the outbox
 * holds everything until the broker is back, however long that takes. A broker that answers
 * and refuses the event will refuse it again, so that is counted against the event, which is
 * reported as stuck once it runs out of attempts.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter published;
    private final Counter unreachable;
    private final Counter rejected;
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
        this.unreachable = Counter.builder("blueprint.outbox.publish.failures")
                .description("Outbox publish attempts that failed")
                .tag("reason", "unreachable")
                .register(meterRegistry);
        this.rejected = Counter.builder("blueprint.outbox.publish.failures")
                .description("Outbox publish attempts that failed")
                .tag("reason", "rejected")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${blueprint.outbox.poll-interval:PT1S}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.claimUnpublished(maxAttempts, Limit.of(batchSize));

        for (OutboxEvent event : batch) {
            boolean brokerReachable = CorrelationId.scoped(event.getCorrelationId(), () -> publish(event));
            if (!brokerReachable) {
                // The rest of the batch would fail the same way, each send blocking for the
                // producer's full timeout while this transaction holds row locks on every one
                // of them — a hundred events at eight seconds each is a thirteen-minute
                // transaction. Stop, release the locks, and let the next poll find out whether
                // the broker is back.
                break;
            }
        }
    }

    /** False when the broker could not be reached; true otherwise, including when it refused the event. */
    private boolean publish(OutboxEvent event) {
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
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            event.recordTransientFailure("interrupted");
            unreachable.increment();
            return false;
        } catch (Exception ex) {
            String reason = rootCause(ex);
            if (isBrokerUnavailable(ex)) {
                // Left claimable and not counted: the outage is not this event's fault, and it
                // will be published the moment the broker answers. OutboxRelayStalled pages if
                // that takes too long.
                event.recordTransientFailure(reason);
                unreachable.increment();
                log.warn(
                        "outbox publish deferred, broker unreachable id={} type={} reason={}",
                        event.getId(),
                        event.getEventType(),
                        reason);
                return false;
            }
            // The broker answered and said no. Counted, so a permanently bad event stops
            // consuming broker capacity after max-attempts and shows up in the stuck gauge
            // instead of retrying forever.
            event.recordFailure(reason);
            rejected.increment();
            log.warn(
                    "outbox publish rejected id={} type={} attempt={} reason={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAttempts(),
                    reason);
            return true;
        }
    }

    /**
     * The innermost cause, which is the one that says what happened. The producer wraps it at
     * least twice, and the outer message is "Send failed" — true, and no help to whoever reads
     * {@code last_error} during an incident, which is what the OutboxEventsStuck runbook asks
     * them to do.
     */
    static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * Whether a send failed because the broker could not be reached, as opposed to refusing
     * the record. Kafka marks the first kind as retriable — a timeout, a missing leader, a
     * broker that went away — and a local timeout waiting for the acknowledgement is the same
     * thing seen from this side.
     */
    static boolean isBrokerUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static RecordHeader header(String key, String value) {
        return new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
