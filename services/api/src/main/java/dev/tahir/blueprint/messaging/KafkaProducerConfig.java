package dev.tahir.blueprint.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import dev.tahir.blueprint.platform.events.EventTypes;

/**
 * Topics are declared here and created at startup rather than by broker auto-creation.
 *
 * <p>Auto-created topics get the broker defaults for partition count and retention, which
 * are almost never what the workload needs, and a typo in a topic name silently creates a
 * new topic instead of failing. Declaring them makes partition count a reviewable decision.
 *
 * <p>Topic creation is behind a flag because in production the cluster is not necessarily
 * writable by the application's credentials — creating topics is a deploy-time concern
 * there, handled by the migration Job, not something a serving pod should have rights to do.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    @ConditionalOnProperty(name = "blueprint.kafka.create-topics", havingValue = "true")
    NewTopic itemEventsTopic(
            @Value("${blueprint.kafka.partitions:3}") int partitions,
            @Value("${blueprint.kafka.replication-factor:1}") short replicationFactor) {
        return TopicBuilder.name(EventTypes.TOPIC_ITEM_EVENTS)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "blueprint.kafka.create-topics", havingValue = "true")
    NewTopic itemEventsDeadLetterTopic(@Value("${blueprint.kafka.replication-factor:1}") short replicationFactor) {
        // One partition: the DLT is read by a human during a post-mortem, not consumed at
        // volume, and a single partition keeps the events in one readable order.
        return TopicBuilder.name(EventTypes.TOPIC_ITEM_EVENTS_DLT)
                .partitions(1)
                .replicas(replicationFactor)
                .build();
    }
}
