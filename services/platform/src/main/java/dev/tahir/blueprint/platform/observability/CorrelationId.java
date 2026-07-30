package dev.tahir.blueprint.platform.observability;

import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.MDC;

/**
 * The correlation-id contract, in one place because it crosses process boundaries.
 *
 * <p>HTTP carries it in {@value #HEADER}; Kafka carries it in a record header of the same
 * name; logs carry it under the MDC key {@value #MDC_KEY}. A request that fans out through
 * the API, an outbox row, a Kafka record and the worker is one query in Loki because all
 * four agree on this string.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private CorrelationId() {}

    /** Current correlation id, or a fresh one when running outside a request (schedulers, consumers). */
    public static String current() {
        String existing = MDC.get(MDC_KEY);
        return existing != null ? existing : UUID.randomUUID().toString();
    }

    /** Runs {@code action} with {@code correlationId} in the MDC, restoring the previous value afterwards. */
    public static void scoped(String correlationId, Runnable action) {
        scoped(correlationId, () -> {
            action.run();
            return null;
        });
    }

    /** As {@link #scoped(String, Runnable)}, for an action whose result the caller needs. */
    public static <T> T scoped(String correlationId, Supplier<T> action) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                MDC.put(MDC_KEY, previous);
            } else {
                MDC.remove(MDC_KEY);
            }
        }
    }
}
