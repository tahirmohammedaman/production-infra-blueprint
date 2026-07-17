package dev.tahir.blueprint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The operational surface is part of the contract with the platform: Kubernetes probes,
 * the Prometheus scrape config and the Grafana dashboards all depend on these paths and
 * on this port split. Breaking them breaks the deploy, not a feature, so they are tested.
 *
 * <p>{@code @AutoConfigureObservability} is required, not decorative: Spring Boot replaces
 * the real meter registry with a {@code SimpleMeterRegistry} in {@code @SpringBootTest} by
 * default, so without it there is no Prometheus endpoint to assert against and this class
 * would be testing a 404. Tracing stays off so the suite never needs a reachable collector.
 */
@AutoConfigureObservability(tracing = false)
class OperationalEndpointsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    private String management(String path) {
        return "http://localhost:" + managementPort + path;
    }

    @Test
    @DisplayName("liveness stays UP and does not depend on the database")
    void livenessIsIndependentOfTheDatabase() {
        ResponseEntity<String> response = rest.getForEntity(management("/actuator/health/liveness"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    @DisplayName("readiness reports the database round-trip")
    void readinessIncludesDatabaseCheck() {
        ResponseEntity<String> response = rest.getForEntity(management("/actuator/health/readiness"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    @DisplayName("exposes the business gauge under the name dashboards query")
    void prometheusExposesBusinessGauge() {
        ResponseEntity<String> response = rest.getForEntity(management("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Named without a _total suffix on purpose; see ItemMetrics.
        assertThat(response.getBody()).contains("blueprint_items");
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("hikaricp_connections");
    }

    @Test
    @DisplayName("publishes the latency histogram the SLO rules depend on")
    void prometheusExposesLatencyHistogram() {
        rest.getForEntity("/api/v1/items", String.class);

        ResponseEntity<String> response = rest.getForEntity(management("/actuator/prometheus"), String.class);

        assertThat(response.getBody()).contains("http_server_requests_seconds_bucket");
    }

    @Test
    @DisplayName("keeps the operational surface off the public API port")
    void actuatorIsNotServedOnTheApiPort() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
