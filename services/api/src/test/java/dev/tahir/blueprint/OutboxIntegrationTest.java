package dev.tahir.blueprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;

import dev.tahir.blueprint.api.dto.ItemRequest;
import dev.tahir.blueprint.api.dto.ItemResponse;
import dev.tahir.blueprint.domain.ItemRepository;
import dev.tahir.blueprint.outbox.OutboxRelay;
import dev.tahir.blueprint.outbox.OutboxRepository;
import dev.tahir.blueprint.platform.events.EventTypes;

/**
 * Proves the write path is a single transaction and that nothing is published until it
 * commits. These are the two properties the outbox exists to guarantee; asserting them is
 * the difference between having implemented the pattern and having named a class after it.
 */
class OutboxIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ItemRepository items;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        items.deleteAll();
    }

    @Test
    @DisplayName("writes the event in the same transaction as the item, and publishes nothing yet")
    void createWritesOutboxRowWithoutPublishing() {
        rest.postForEntity("/api/v1/items", new ItemRequest("widget", "desc", 4), ItemResponse.class);

        var rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(EventTypes.ITEM_CREATED);
        // Unpublished: the HTTP request must not depend on the broker being reachable.
        assertThat(rows.get(0).getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("the relay publishes pending rows to Kafka and marks them published")
    void relayPublishesToKafka() {
        ItemResponse created = rest.postForEntity(
                        "/api/v1/items", new ItemRequest("relayed", "desc", 7), ItemResponse.class)
                .getBody();

        relay.publishPending();

        assertThat(outbox.findAll())
                .allSatisfy(row -> assertThat(row.getPublishedAt()).isNotNull());

        // Filtered by key rather than asserting on the whole topic: the broker is shared
        // across the class, so the topic legitimately holds records from other test methods.
        // A test that assumes it owns the topic passes or fails on method execution order.
        ConsumerRecord<String, String> record = drainTopic(EventTypes.TOPIC_ITEM_EVENTS).stream()
                .filter(r -> created.id().toString().equals(r.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record published for item " + created.id()));

        // Keyed by aggregate id so every event for one item lands on one partition and is
        // therefore consumed in the order it was produced.
        assertThat(record.value()).contains("relayed");
        assertThat(record.headers().lastHeader(EventTypes.HEADER_EVENT_ID)).isNotNull();
        assertThat(record.headers().lastHeader(EventTypes.HEADER_CORRELATION_ID))
                .isNotNull();
    }

    @Test
    @DisplayName("a rejected write leaves no event behind")
    void rejectedWriteProducesNoEvent() {
        rest.postForEntity("/api/v1/items", new ItemRequest("dupe", null, 1), ItemResponse.class);
        outbox.deleteAll();

        rest.postForEntity("/api/v1/items", new ItemRequest("DUPE", null, 1), String.class);

        // The duplicate was rejected, so the transaction rolled back and took the outbox row
        // with it. This is the property a publish-inside-the-service-method implementation
        // would silently violate.
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    @DisplayName("republishing after a crash is at-least-once, never silently lost")
    void unpublishedRowsSurviveForRetry() {
        rest.postForEntity("/api/v1/items", new ItemRequest("pending", null, 2), ItemResponse.class);

        // Simulates a relay that died before marking the row published: the row is still
        // claimable, so the event is redelivered rather than dropped.
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);

        relay.publishPending();
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    private List<ConsumerRecord<String, String>> drainTopic(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            // Poll more than once: the first poll of a fresh group is usually consumed by the
            // join and returns nothing, and a single poll is not guaranteed to return every
            // available record even after that.
            for (int attempt = 0; attempt < 5; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                records.records(topic).forEach(collected::add);
                if (!collected.isEmpty() && records.isEmpty()) {
                    break;
                }
            }
            return collected;
        }
    }
}
