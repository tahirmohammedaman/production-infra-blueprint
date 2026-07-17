package dev.tahir.blueprint.platform.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A single-node Kafka broker for integration tests, configured explicitly rather than via
 * {@code org.testcontainers.kafka.KafkaContainer}.
 *
 * <p>Testcontainers derives the broker's advertised listener from Docker's legacy top-level
 * {@code NetworkSettings.IPAddress} field. Podman leaves that field empty — the address is
 * reported per-network instead — so the advertised listener comes out as {@code 0.0.0.0}
 * and Kafka refuses to start with "advertised.listeners cannot use the nonroutable
 * meta-address". Contributors on rootless podman would simply be unable to run the suite.
 *
 * <p>The fix is to stop deriving the address at all: a free host port is chosen up front,
 * bound to the container's 9092, and advertised as {@code localhost:<port>}. Deterministic,
 * identical on Docker and podman, and it removes a piece of environment-dependent magic
 * from the test setup.
 *
 * <p>The port is chosen from the ephemeral range at construction, so parallel builds on one
 * machine do not collide.
 */
public final class KafkaTestBroker {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/kafka:3.9.0");

    private final GenericContainer<?> container;
    private final int hostPort;

    public KafkaTestBroker() {
        this.hostPort = findFreePort();
        this.container = new GenericContainer<>(IMAGE)
                .withEnv("KAFKA_NODE_ID", "1")
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                // Bare host, not 0.0.0.0. The image's storage-format step validates the
                // listener set before the explicit advertised.listeners below is applied, and
                // rejects the meta-address outright — the container then exits 1 with a
                // message that points at advertised.listeners and not at the real cause.
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + hostPort)
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                // Single broker, so every internal topic must be RF=1; the defaults of 3
                // would leave __consumer_offsets uncreatable and every consumer hanging.
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                // No artificial delay before the first rebalance: tests should not pay the
                // production default of three seconds on every consumer start.
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .waitingFor(
                        Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofSeconds(120)));
        this.container.setPortBindings(List.of(hostPort + ":9092"));
    }

    public void start() {
        container.start();
    }

    public String bootstrapServers() {
        return "localhost:" + hostPort;
    }

    public boolean isRunning() {
        return container.isRunning();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("could not allocate a port for the test broker", ex);
        }
    }
}
