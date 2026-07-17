package dev.tahir.blueprint;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import dev.tahir.blueprint.platform.testing.KafkaTestBroker;

/**
 * Integration tests run against real Postgres, Redis and Kafka, not against in-memory
 * substitutes.
 *
 * <p>The schema is created by the same Flyway migrations that run in production, so the
 * migrations are exercised on every build; H2 would accept SQL that Postgres rejects and
 * would never execute the partial and expression indexes this schema relies on. Likewise an
 * embedded broker would not exercise the producer's {@code acks=all} path, which is exactly
 * where the outbox relay's correctness lives.
 *
 * <p>Containers are {@code static} so one set is shared across every subclass for the whole
 * test run rather than started per class. Kafka is the expensive one; per-class startup
 * would roughly triple the suite.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.flyway.enabled=true",
            "blueprint.metrics.item-count-refresh=PT1S",
            "blueprint.kafka.create-topics=true",
            // The relay and the reconciler are driven explicitly by the tests that care, so
            // a background timer cannot race an assertion and make the suite flaky.
            "blueprint.outbox.poll-interval=PT1H",
            "blueprint.reconcile.enabled=false"
        })
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blueprint")
            .withUsername("blueprint")
            .withPassword("blueprint")
            .withReuse(true);

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
    }
}
