package dev.tahir.blueprint.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Lives in the API's test tree for the same reason as OperationalSpanFilterTest: the platform
 * module has no test source set, and the API is the service whose preStop hook uses this.
 */
class ConnectionDrainTest {

    private final ConnectionDrain drain = new ConnectionDrain();
    private final ConnectionDrainFilter filter = new ConnectionDrainFilter(drain);

    @Test
    @DisplayName("lets clients keep their connections while the instance is staying")
    void keepsConnectionsOpenNormally() throws Exception {
        MockHttpServletResponse response = serve();

        assertThat(response.getHeader("Connection")).isNull();
    }

    @Test
    @DisplayName("tells every client to close its connection once the preStop hook has run")
    void closesConnectionsWhenDraining() throws Exception {
        new DrainEndpoint(drain, Duration.ZERO).drain();

        assertThat(drain.isDraining()).isTrue();
        assertThat(serve().getHeader("Connection")).isEqualTo("close");
        // Every response, not just the first: each client connection has to hear it once.
        assertThat(serve().getHeader("Connection")).isEqualTo("close");
    }

    @Test
    @DisplayName("holds the hook open for the drain period, which is what delays SIGTERM")
    void waitsForTheDrainPeriodBeforeReturning() {
        long started = System.nanoTime();

        new DrainEndpoint(drain, Duration.ofMillis(300)).drain();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    private MockHttpServletResponse serve() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/items"), response, new MockFilterChain());
        return response;
    }
}
