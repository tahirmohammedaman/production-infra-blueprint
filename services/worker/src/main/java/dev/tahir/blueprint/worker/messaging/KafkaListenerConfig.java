package dev.tahir.blueprint.worker.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

@Configuration
@EnableKafka
public class KafkaListenerConfig {

    /**
     * Listener container wired for correct commit semantics and observability.
     *
     * <p>The injected {@code ConsumerFactory} is Spring Boot's, which means Boot's
     * {@code KafkaMetricsAutoConfiguration} has already attached a
     * {@code MicrometerConsumerListener} to it — that is how the Kafka client's own metrics,
     * consumer lag included, reach Prometheus. Attaching a second one here would register
     * every meter twice, so this class deliberately does not.
     *
     * <p>Lag is the worker's real SLI. Request-rate and error-rate dashboards look perfectly
     * healthy while a stalled consumer falls hours behind; only lag shows it, which is why
     * the alert rules treat it as a first-class signal rather than a curiosity.
     *
     * <p>{@code RECORD} ack mode commits after each successfully handled record. The default
     * batch mode redelivers an entire batch when its last record fails, multiplying the
     * redelivery the idempotency guard has to absorb.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            @Value("${blueprint.consumer.concurrency:3}") int concurrency) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        // Matched to the topic's partition count: more consumers than partitions leaves
        // threads permanently idle; fewer leaves partitions unread by this replica.
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
