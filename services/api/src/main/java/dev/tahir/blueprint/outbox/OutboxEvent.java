package dev.tahir.blueprint.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row in the transactional outbox.
 *
 * <p>The API cannot write to Postgres and publish to Kafka atomically — that is the dual
 * write problem, and doing it naively means either an item exists that no event describes,
 * or an event describes an item that was rolled back. Instead the event is written to this
 * table inside the same transaction as the item, and a relay publishes it afterwards.
 *
 * <p>The consequence is at-least-once, never at-most-once: a relay crash between publish
 * and mark-published republishes the event. That is why every event carries an idempotency
 * key and the worker deduplicates. Trading exactly-once for at-least-once plus consumer
 * idempotency is the deliberate choice here.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Serialised {@code EventEnvelope}. Stored as text so the table stays readable in psql during an incident. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEvent() {
        // required by JPA
    }

    public OutboxEvent(UUID id, String aggregateId, String eventType, String correlationId, String payload) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.correlationId = correlationId;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * A failure that belongs to this event: the broker received it and refused it, so trying
     * again will fail the same way. Counted towards the attempt limit, after which the event
     * is stuck and an operator is told.
     */
    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = truncate(error);
    }

    /**
     * A failure that says nothing about this event: the broker was unreachable or did not
     * answer in time. Recorded for whoever reads the row, but not counted. Counting it meant a
     * broker outage of about two minutes used up the attempts of every event written while it
     * lasted, and stranded all of them — the outage the outbox exists to absorb.
     */
    public void recordTransientFailure(String error) {
        this.lastError = truncate(error);
    }

    // Bounded so a driver stack trace cannot blow up the row or the log line it lands in.
    private static String truncate(String error) {
        return error != null && error.length() > 500 ? error.substring(0, 500) : error;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
