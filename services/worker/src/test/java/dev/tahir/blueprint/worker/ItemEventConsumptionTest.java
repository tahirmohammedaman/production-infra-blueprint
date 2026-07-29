package dev.tahir.blueprint.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.tahir.blueprint.platform.events.EventEnvelope;
import dev.tahir.blueprint.platform.events.EventTypes;
import dev.tahir.blueprint.worker.processing.InventorySummary;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * End-to-end consumer behaviour against a real broker: projection updates, redelivery
 * handling, and dead-lettering. These are the properties that only appear once a second
 * service is consuming asynchronously, so they are worth the cost of a real broker.
 */
class ItemEventConsumptionTest extends WorkerIntegrationTestBase {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private RedisTemplate<String, InventorySummary> summaries;

    @Autowired
    private StringRedisTemplate counters;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void resetProjection() {
        counters.delete(List.of("blueprint:inventory:distinct", "blueprint:inventory:quantity"));
        summaries.delete(InventorySummary.CACHE_KEY);
    }

    @Test
    @DisplayName("applies a created event to the projection")
    void createdEventUpdatesProjection() {
        publish(EventTypes.ITEM_CREATED, UUID.randomUUID().toString(), "widget", 5, null);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            InventorySummary summary = summaries.opsForValue().get(InventorySummary.CACHE_KEY);
            assertThat(summary).isNotNull();
            assertThat(summary.distinctItems()).isEqualTo(1);
            assertThat(summary.totalQuantity()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("applies an update as a delta, not as an absolute value")
    void updateEventAppliesDelta() {
        String aggregate = UUID.randomUUID().toString();
        publish(EventTypes.ITEM_CREATED, aggregate, "widget", 5, null);
        await().atMost(Duration.ofSeconds(30)).until(() -> quantity() != null && quantity() == 5);

        publish(EventTypes.ITEM_UPDATED, aggregate, "widget", 8, 5);

        // 5 + (8 - 5) = 8. Applying the absolute value would give 13 and the projection
        // would drift further with every update.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(quantity()).isEqualTo(8));
    }

    @Test
    @DisplayName("a redelivered event is applied exactly once")
    void redeliveredEventIsNotDoubleApplied() {
        UUID eventId = UUID.randomUUID();
        String aggregate = UUID.randomUUID().toString();

        publishWithEventId(eventId, EventTypes.ITEM_CREATED, aggregate, "widget", 3, null);
        await().atMost(Duration.ofSeconds(30)).until(() -> quantity() != null && quantity() == 3);

        // Exactly what the outbox relay does after a crash between publish and mark-published.
        publishWithEventId(eventId, EventTypes.ITEM_CREATED, aggregate, "widget", 3, null);

        // Give the consumer time to have processed it, then assert nothing changed.
        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(quantity()).isEqualTo(3));
    }

    @Test
    @DisplayName("an unparseable record goes to the dead-letter topic instead of stalling the partition")
    void poisonRecordIsDeadLettered() {
        kafka.send(new ProducerRecord<>(
                EventTypes.TOPIC_ITEM_EVENTS, UUID.randomUUID().toString(), "{not json"));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(drain(EventTypes.TOPIC_ITEM_EVENTS_DLT))
                .isNotEmpty());

        // The topic proves the record was routed; the counter is how anyone finds out without
        // reading the topic. It is what the DeadLetterTopicReceiving alert fires on.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(meterRegistry
                        .get("blueprint.events.dead.lettered")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1.0));

        // The partition kept moving: a well-formed event published after the poison record is
        // still processed. Without bounded retry and dead-lettering, this assertion is what
        // fails — the consumer would still be retrying the bad record forever.
        publish(EventTypes.ITEM_CREATED, UUID.randomUUID().toString(), "after-poison", 2, null);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(quantity()).isNotNull());
    }

    private Long quantity() {
        String raw = counters.opsForValue().get("blueprint:inventory:quantity");
        return raw == null ? null : Long.parseLong(raw);
    }

    private void publish(String type, String aggregateId, String name, int qty, Integer previousQty) {
        publishWithEventId(UUID.randomUUID(), type, aggregateId, name, qty, previousQty);
    }

    private void publishWithEventId(
            UUID eventId, String type, String aggregateId, String name, int qty, Integer previousQty) {
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                type,
                aggregateId,
                Instant.now(),
                "test-correlation-id",
                new EventEnvelope.ItemChanged(name, qty, previousQty));
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    EventTypes.TOPIC_ITEM_EVENTS, aggregateId, objectMapper.writeValueAsString(envelope));
            record.headers()
                    .add(new RecordHeader(
                            EventTypes.HEADER_CORRELATION_ID, "test-correlation-id".getBytes(StandardCharsets.UTF_8)));
            kafka.send(record).get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("could not publish test event", ex);
        }
    }

    private List<ConsumerRecord<String, String>> drain(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            java.util.List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
            for (int attempt = 0; attempt < 3; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));
                records.records(topic).forEach(collected::add);
                if (!collected.isEmpty()) {
                    break;
                }
            }
            return collected;
        }
    }
}
