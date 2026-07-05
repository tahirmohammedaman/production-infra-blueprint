package dev.tahir.blueprint.observability;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness probe for the database dependency.
 *
 * <p>Deliberately separate from liveness: a database outage must take the pod out of the
 * load-balancer rotation, but it must not get the pod killed and restarted — restarting
 * an application because its database is down only adds a cold start to the incident.
 */
@Component("databaseReadiness")
public class DatabaseReadinessIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReadinessIndicator.class);
    private static final int QUERY_TIMEOUT_SECONDS = 2;
    private static final String VALIDATION_QUERY = "SELECT 1";

    private final DataSource dataSource;

    public DatabaseReadinessIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute(VALIDATION_QUERY);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("latencyMs", latencyMs)
                    .withDetail("validationQuery", VALIDATION_QUERY)
                    .build();
        } catch (SQLException ex) {
            log.warn("database readiness check failed: {}", ex.getMessage());
            return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
        }
    }
}
