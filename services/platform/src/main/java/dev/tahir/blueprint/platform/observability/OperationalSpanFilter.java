package dev.tahir.blueprint.platform.observability;

import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;

/**
 * Exports spans that describe work someone asked for, and drops the ones that describe the
 * system looking after itself.
 *
 * <p>Measured on the local stack before this existed: of 198 traces Tempo stored in ten
 * minutes, 176 were scheduled tasks — mostly the outbox relay polling every second with nothing
 * to publish — or Prometheus scraping and probes checking the actuator endpoints. The 22 real
 * requests had to be searched for among them, and every one of the rest was stored and paid for.
 *
 * <p>Filtered at export rather than by switching the observations off, because the same
 * observations produce the {@code tasks_scheduled_execution} and {@code http_server_requests}
 * metrics, and those are worth keeping.
 */
public class OperationalSpanFilter implements SpanExportingPredicate {

    @Override
    public boolean isExportable(FinishedSpan span) {
        String uri = span.getTags().get("uri");
        if (uri != null && uri.startsWith("/actuator")) {
            return false;
        }
        // Spring's @Scheduled observation: named "task <bean>.<method>" and tagged with the
        // method it ran. Matching both keeps a span that merely starts with "task " exportable.
        return !(span.getName().startsWith("task ") && span.getTags().containsKey("code.function"));
    }
}
