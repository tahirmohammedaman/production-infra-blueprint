package dev.tahir.blueprint.worker.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import dev.tahir.blueprint.platform.events.EventTypes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * Retry policy and dead-letter routing for the listener container.
     *
     * <p>Retries are bounded and backed off. Unbounded retry on a poison message stalls the
     * whole partition forever: the consumer never commits past it, lag grows without limit,
     * and every well-formed event queued behind it is starved. Bounded retry then DLT means
     * one bad record costs one record, not the partition.
     *
     * <p>Deserialisation failures are not retried at all. A record that cannot be parsed
     * will never parse on the third attempt either, so it goes straight to the dead-letter
     * topic where a human can look at it.
     *
     * <p>A dead-letter topic nobody is told about is a place events go to be forgotten, so
     * every routed record is also counted. The topic says <em>what</em> failed; the counter
     * is what the DeadLetterTopicReceiving alert reads to say <em>that</em> something did.
     */
    @Bean
    DefaultErrorHandler errorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry registry,
            @Value("${blueprint.consumer.retry.initial-interval-ms:500}") long initialInterval,
            @Value("${blueprint.consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${blueprint.consumer.retry.max-interval-ms:10000}") long maxInterval,
            @Value("${blueprint.consumer.retry.max-elapsed-ms:60000}") long maxElapsed) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // Send every failure to partition 0 of the DLT rather than mirroring the
                // source partition: the DLT has one partition, and mirroring would target
                // partitions that do not exist.
                (record, exception) -> new TopicPartition(EventTypes.TOPIC_ITEM_EVENTS_DLT, 0));

        Counter deadLettered = Counter.builder("blueprint.events.dead.lettered")
                .description("Records routed to the dead-letter topic after exhausting retries or failing to parse")
                .register(registry);

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval, multiplier);
        backOff.setMaxInterval(maxInterval);
        backOff.setMaxElapsedTime(maxElapsed);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error(
                            "event exhausted retries, routing to dead-letter topic topic={} partition={} offset={} key={}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            record.key(),
                            exception);
                    recoverer.accept((ConsumerRecord<?, ?>) record, exception);
                    // Counted after the publish returns: if the DLT write itself fails, the
                    // record is retried rather than lost, and must not be reported as routed.
                    deadLettered.increment();
                },
                backOff);

        // Retrying a message that could not be deserialised is pure waste; it fails
        // identically every time.
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);

        return handler;
    }
}
