package dev.tahir.blueprint.worker.processing;

import java.time.Instant;

/**
 * The read model this worker maintains, mirroring the record the API deserialises.
 *
 * <p>Duplicated deliberately rather than shared through the platform module. The cache
 * payload is a contract between two independently deployed services, and putting it in a
 * shared library makes it trivially easy to change both sides in one commit and ship a
 * worker that writes a shape the running API cannot read. Two declarations force the
 * compatibility question to be asked. See docs/adr/0006-cache-as-a-read-model.md.
 */
public record InventorySummary(long distinctItems, long totalQuantity, Instant computedAt) {

    public static final String CACHE_KEY = "blueprint:inventory:summary";
}
