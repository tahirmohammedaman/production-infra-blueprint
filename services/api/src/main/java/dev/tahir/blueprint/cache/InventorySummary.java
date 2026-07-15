package dev.tahir.blueprint.cache;

import java.time.Instant;

/**
 * Denormalised read model kept in Redis and maintained by the worker.
 *
 * <p>It crosses a process boundary — the worker writes it, the API reads it — so it is a
 * published contract, not an implementation detail. Adding a field is safe; removing or
 * renaming one requires the same care as an API change. See
 * docs/adr/0006-cache-as-a-read-model.md for why a stale value is acceptable here.
 */
public record InventorySummary(long distinctItems, long totalQuantity, Instant computedAt) {

    /** Redis key. Fixed rather than templated: there is exactly one summary. */
    public static final String CACHE_KEY = "blueprint:inventory:summary";
}
