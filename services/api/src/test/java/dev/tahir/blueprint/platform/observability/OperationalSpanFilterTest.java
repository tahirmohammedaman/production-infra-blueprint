package dev.tahir.blueprint.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.tracing.exporter.FinishedSpan;

/**
 * Lives in the API's test tree because the platform module has no test source set of its own;
 * its behaviour is tested where it is consumed. The span names and tags below are copied from
 * spans Tempo actually stored, not from documentation.
 */
class OperationalSpanFilterTest {

    private final OperationalSpanFilter filter = new OperationalSpanFilter();

    @Test
    @DisplayName("drops the outbox relay's scheduled poll")
    void dropsScheduledTasks() {
        FinishedSpan poll = span(
                "task outbox-relay.publish-pending",
                Map.of("code.function", "publishPending", "code.namespace", "dev.tahir.blueprint.outbox.OutboxRelay"));

        assertThat(filter.isExportable(poll)).isFalse();
    }

    @Test
    @DisplayName("drops Prometheus scrapes and health probes")
    void dropsActuatorRequests() {
        FinishedSpan scrape = span("http get /actuator/prometheus", Map.of("uri", "/actuator/prometheus"));
        FinishedSpan probe = span("http get /actuator/health/**", Map.of("uri", "/actuator/health/**"));

        assertThat(filter.isExportable(scrape)).isFalse();
        assertThat(filter.isExportable(probe)).isFalse();
    }

    @Test
    @DisplayName("keeps real requests and the worker's consumer spans")
    void keepsRealWork() {
        FinishedSpan request = span("http post /api/v1/items", Map.of("uri", "/api/v1/items"));
        FinishedSpan consume = span("item-events receive", Map.of("messaging.system", "kafka"));

        assertThat(filter.isExportable(request)).isTrue();
        assertThat(filter.isExportable(consume)).isTrue();
    }

    @Test
    @DisplayName("keeps a span that only happens to be named like a task")
    void keepsSpansNamedLikeTasksWithoutTheTaskTags() {
        FinishedSpan lookalike = span("task-list lookup", Map.of("uri", "/api/v1/items"));

        assertThat(filter.isExportable(lookalike)).isTrue();
    }

    private static FinishedSpan span(String name, Map<String, String> tags) {
        FinishedSpan span = mock(FinishedSpan.class);
        when(span.getName()).thenReturn(name);
        when(span.getTags()).thenReturn(tags);
        return span;
    }
}
