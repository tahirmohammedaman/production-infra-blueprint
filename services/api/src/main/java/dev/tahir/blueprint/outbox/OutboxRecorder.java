package dev.tahir.blueprint.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.tahir.blueprint.platform.events.EventEnvelope;
import dev.tahir.blueprint.platform.observability.CorrelationId;

/**
 * Writes an event into the outbox table.
 *
 * <p>{@code MANDATORY} propagation is the guard rail that makes the whole pattern work: this
 * method must join the caller's transaction, never start its own. If it ever ran in a
 * separate transaction the event could commit while the entity write rolled back, which is
 * precisely the inconsistency the outbox exists to prevent. Declaring it here means a
 * future caller who forgets {@code @Transactional} fails loudly instead of silently
 * reintroducing the bug.
 */
@Component
public class OutboxRecorder {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventType, String aggregateId, EventEnvelope.ItemChanged payload) {
        UUID eventId = UUID.randomUUID();
        String correlationId = CorrelationId.current();

        EventEnvelope envelope =
                new EventEnvelope(eventId, eventType, aggregateId, Instant.now(), correlationId, payload);

        String serialised;
        try {
            serialised = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            // Serialisation failure means the event contract is broken, which is a bug, not a
            // transient fault. Failing the business transaction is correct: silently dropping
            // the event would leave the API and the worker permanently inconsistent.
            throw new IllegalStateException("could not serialise outbox event " + eventType, ex);
        }

        repository.save(new OutboxEvent(eventId, aggregateId, eventType, correlationId, serialised));
    }
}
