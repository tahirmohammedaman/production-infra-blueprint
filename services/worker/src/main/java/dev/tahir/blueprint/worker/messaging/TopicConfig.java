package dev.tahir.blueprint.worker.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import dev.tahir.blueprint.platform.events.EventTypes;

/**
 * Topic declarations, behind a flag that is off in production.
 *
 * <p>Serving pods should not hold create-topic rights on the cluster; topic creation is a
 * deploy-time concern handled by the migration Job. The flag exists so local and test runs
 * are self-contained.
 */
@Configuration
public class TopicConfig {

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
        return TopicBuilder.name(EventTypes.TOPIC_ITEM_EVENTS_DLT)
                .partitions(1)
                .replicas(replicationFactor)
                .build();
    }
}
