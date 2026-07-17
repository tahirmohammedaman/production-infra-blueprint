package dev.tahir.blueprint.worker;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import dev.tahir.blueprint.platform.testing.KafkaTestBroker;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0"})
@ActiveProfiles("test")
@Testcontainers
public abstract class WorkerIntegrationTestBase {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    static {
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        // A fresh group per JVM run so a rerun reprocesses from the beginning instead of
        // resuming committed offsets from the previous run and seeing nothing.
        registry.add("blueprint.consumer.group-id", () -> "test-worker-" + System.nanoTime());
    }
}
