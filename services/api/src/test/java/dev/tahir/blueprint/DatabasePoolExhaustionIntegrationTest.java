package dev.tahir.blueprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * What a client sees when every pooled connection is busy: a 503 it may retry, not a 500.
 *
 * <p>The slice test in {@code ItemControllerTest} proves the mapping for an exception it
 * constructs itself. This one proves the real pool, the real transaction manager and the real
 * driver raise the exception that mapping is written for, which is the part a mock cannot show.
 * The pool holds one connection, the test holds it, and the timeout is Hikari's minimum of 250 ms
 * where production waits 3 s — the same path, taken quickly.
 *
 * <p>A context of its own because of the pool properties, at the cost of one extra startup. The
 * item-count gauge refreshes hourly here so its background query cannot take the only connection
 * at the moment the test lets go of it.
 */
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=1",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.connection-timeout=250",
            "blueprint.metrics.item-count-refresh=PT1H"
        })
class DatabasePoolExhaustionIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("answers 503 with Retry-After while every pooled connection is busy, and recovers once one is free")
    void exhaustedPoolIsServiceUnavailable() throws Exception {
        try (Connection held = dataSource.getConnection()) {
            ResponseEntity<String> refused = rest.getForEntity("/api/v1/items", String.class);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
            assertThat(refused.getBody()).contains("Database unavailable").doesNotContain("blueprint-pool");
        }

        ResponseEntity<String> served = rest.getForEntity("/api/v1/items", String.class);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
