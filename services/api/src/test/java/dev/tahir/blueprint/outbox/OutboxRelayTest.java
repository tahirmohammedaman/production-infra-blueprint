package dev.tahir.blueprint.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * How the relay behaves when the broker is not there, which the integration suite cannot
 * show without stopping the broker every other test shares.
 *
 * <p>These tests exist because the relay used to count every failed send against the event.
 * With a ten-attempt limit and an eight-second producer timeout, a broker outage of about two
 * minutes used up the attempts of every event written while it lasted: all of them became
 * stuck, none of them would ever be published, and ADR 0007's claim that the outbox absorbs a
 * broker outage was true only for short ones.
 */
class OutboxRelayTest {

    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository repository = mock(OutboxRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, kafka, registry, 100, MAX_ATTEMPTS, 1);
    }

    @Test
    @DisplayName("a broker outage costs no attempts, and ends the batch instead of timing out every event")
    void unreachableBrokerIsNotCountedAgainstTheEvent() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        claims(first, second);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(failed(new TimeoutException("Topic item-events not present in metadata after 8000 ms.")));

        relay.publishPending();

        assertThat(first.getAttempts()).isZero();
        assertThat(first.getLastError()).contains("not present in metadata");
        assertThat(first.getPublishedAt()).isNull();
        // The second event was never sent: the batch stopped at the first sign of an outage.
        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(failures("unreachable")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an outage longer than the attempt limit strands nothing")
    void longOutageLeavesEventsPublishable() {
        OutboxEvent event = event();
        claims(event);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(failed(new NotLeaderOrFollowerException("no leader for item-events-0")));

        // Two and a half times the attempt limit. Before the fix, the event was stuck after ten.
        for (int poll = 0; poll < MAX_ATTEMPTS * 5 / 2; poll++) {
            relay.publishPending();
        }
        assertThat(event.getAttempts()).isZero();

        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult()));
        relay.publishPending();

        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("a record the broker refuses is counted, and the rest of the batch still goes out")
    void refusedRecordIsCountedAndTheBatchContinues() {
        OutboxEvent refused = event();
        OutboxEvent fine = event();
        claims(refused, fine);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(failed(new RecordTooLargeException("The message is 2000000 bytes when serialized")))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));

        relay.publishPending();

        assertThat(refused.getAttempts()).isEqualTo(1);
        assertThat(refused.getPublishedAt()).isNull();
        assertThat(fine.getPublishedAt()).isNotNull();
        assertThat(failures("rejected")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("tells a broker that is away from one that said no, however deeply the cause is wrapped")
    void classifiesFailuresByCause() {
        assertThat(OutboxRelay.isBrokerUnavailable(new KafkaException(new TimeoutException("metadata"))))
                .isTrue();
        assertThat(OutboxRelay.isBrokerUnavailable(new java.util.concurrent.TimeoutException()))
                .isTrue();
        assertThat(OutboxRelay.isBrokerUnavailable(new KafkaException(new RecordTooLargeException("too big"))))
                .isFalse();
        assertThat(OutboxRelay.isBrokerUnavailable(new IllegalStateException("serialiser broke")))
                .isFalse();
    }

    private void claims(OutboxEvent... events) {
        when(repository.claimUnpublished(anyInt(), any(Limit.class))).thenReturn(List.of(events));
    }

    private double failures(String reason) {
        return registry.get("blueprint.outbox.publish.failures")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private static OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID().toString(), "item.created", "corr-1", "{}");
    }

    // Wrapped the way the producer wraps it, so the classification has to look past the top.
    private static CompletableFuture<SendResult<String, String>> failed(Exception cause) {
        return CompletableFuture.failedFuture(new KafkaException("Send failed", cause));
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> sendResult() {
        return mock(SendResult.class);
    }
}
