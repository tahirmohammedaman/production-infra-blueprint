package dev.tahir.blueprint.platform.web;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether this instance is on its way out and should stop letting clients reuse connections.
 *
 * <p>A terminating pod leaves the Service's endpoints at once, so no new connection reaches
 * it. Clients keep the ones they already have, though — the gateway's pool, a load generator's
 * keep-alive connections — and go on sending requests over them until the server closes them.
 * When that close crosses a request on the wire, the request fails. A GET is retried by most
 * HTTP clients without anyone noticing; a POST is not, and that is the failure the
 * zero-downtime drill caught.
 *
 * <p>Once draining, every response carries {@code Connection: close}: each client finishes its
 * current request, closes the connection itself, and opens the next one to a pod that is
 * staying. By the time the server shuts down, nobody is holding a connection to it.
 */
public class ConnectionDrain {

    private static final Logger log = LoggerFactory.getLogger(ConnectionDrain.class);

    private final AtomicBoolean draining = new AtomicBoolean();

    public void start() {
        if (draining.compareAndSet(false, true)) {
            log.info("draining: responses now close their connections so clients move to another instance");
        }
    }

    public boolean isDraining() {
        return draining.get();
    }
}
