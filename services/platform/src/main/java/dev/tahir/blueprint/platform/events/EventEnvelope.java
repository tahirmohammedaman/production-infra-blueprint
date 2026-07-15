package dev.tahir.blueprint.platform.events;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire contract between the API and the worker. Shared so that a change to it cannot
 * compile in one service and not the other.
 *
 * <p>{@code eventId} is the idempotency key. Kafka gives at-least-once delivery, so a
 * consumer will see redeliveries after a rebalance or a failed commit; the worker
 * deduplicates on this id rather than pretending each record arrives once.
 *
 * <p>{@code occurredAt} is when the fact happened in the API's transaction, not when the
 * record was published. Those differ by however long the outbox relay took, and using the
 * publish time would make end-to-end latency unmeasurable.
 *
 * @param eventId       unique per event; the consumer's idempotency key
 * @param type          event type, used for routing and for the DLQ post-mortem
 * @param aggregateId   the entity this event is about
 * @param occurredAt    when the originating transaction committed
 * @param correlationId request that caused it, so one Loki query spans all services
 * @param payload       type-specific body
 */
public record EventEnvelope(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("type") String type,
        @JsonProperty("aggregateId") String aggregateId,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("payload") ItemChanged payload) {

    /**
     * Payload for every {@code item.*} event.
     *
     * <p>Carries the values rather than only the id on purpose: a consumer that has to read
     * back from the API to learn what changed reintroduces the coupling the broker was
     * supposed to remove, and races with subsequent writes.
     */
    public record ItemChanged(
            @JsonProperty("name") String name,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("previousQuantity") Integer previousQuantity) {}
}
