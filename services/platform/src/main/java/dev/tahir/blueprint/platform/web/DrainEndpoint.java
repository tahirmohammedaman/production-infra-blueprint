package dev.tahir.blueprint.platform.web;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/drain}: the Kubernetes preStop hook.
 *
 * <p>It starts draining and then holds the hook open for the drain period, and only when it
 * returns does the kubelet send SIGTERM. Those seconds are when clients see {@code Connection:
 * close} and move their connections elsewhere; the graceful shutdown that follows then has
 * nothing left to close underneath anyone.
 *
 * <p>A GET that changes state, because an {@code httpGet} hook is the only kind a distroless
 * image can run — there is no shell for an {@code exec} one. It is served on the management
 * port, which the NetworkPolicy opens to the monitoring namespace and the kubelet only. Called by
 * mistake, it does not take the instance out of service: readiness is untouched, and the only
 * effect is that this replica stops reusing connections until it restarts.
 */
@Endpoint(id = "drain")
public class DrainEndpoint {

    private final ConnectionDrain drain;
    private final Duration period;

    public DrainEndpoint(ConnectionDrain drain, Duration period) {
        this.drain = drain;
        this.period = period;
    }

    @ReadOperation
    public Map<String, Object> drain() {
        drain.start();
        try {
            Thread.sleep(period.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return Map.of("draining", true, "waited", period.toString());
    }
}
