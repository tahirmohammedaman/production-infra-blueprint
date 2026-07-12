package dev.tahir.blueprint;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests run against a real PostgreSQL, not H2.
 *
 * <p>The schema is created by the same Flyway migrations that run in production, which
 * means the migrations are exercised on every build. An in-memory database would accept
 * SQL that Postgres rejects and would never execute the partial and expression indexes
 * this schema relies on.
 *
 * <p>The container is {@code static} so one instance is shared by every subclass for the
 * whole test run rather than started per class.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.flyway.enabled=true",
            "blueprint.metrics.item-count-refresh=PT1S"
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

    static {
        POSTGRES.start();
    }
}
