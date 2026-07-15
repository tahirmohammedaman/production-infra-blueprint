package dev.tahir.blueprint.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Readiness for a service with no HTTP traffic of its own.
 *
 * <p>A worker that is "up" but whose listener container has stopped is the classic silent
 * failure: the process answers every probe, the pod stays in the deployment, and nothing is
 * being consumed. Readiness therefore asserts that the containers are actually running,
 * which is the only thing that makes this pod useful.
 */
@Component("consumerReadiness")
public class WorkerHealthConfig implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(WorkerHealthConfig.class);

    private final KafkaListenerEndpointRegistry registry;

    public WorkerHealthConfig(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        var containers = registry.getListenerContainers();
        if (containers.isEmpty()) {
            return Health.down()
                    .withDetail("reason", "no listener containers registered")
                    .build();
        }

        long running =
                containers.stream().filter(MessageListenerContainer::isRunning).count();
        if (running < containers.size()) {
            log.warn("only {} of {} listener containers are running", running, containers.size());
            return Health.down()
                    .withDetail("running", running)
                    .withDetail("expected", containers.size())
                    .build();
        }

        return Health.up().withDetail("listenerContainers", containers.size()).build();
    }
}
