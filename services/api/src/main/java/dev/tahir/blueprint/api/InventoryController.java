package dev.tahir.blueprint.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.tahir.blueprint.cache.InventorySummary;
import dev.tahir.blueprint.cache.InventorySummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The read side of the async path. What this endpoint returns is maintained by the worker
 * in response to item events, so it is the visible proof that the whole chain — outbox,
 * relay, Kafka, consumer, cache — is working end to end.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Read model maintained asynchronously by the worker")
public class InventoryController {

    private final InventorySummaryService summaries;

    public InventoryController(InventorySummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Current inventory summary",
            description = "Served from Redis when warm, computed from the database on a miss."
                    + " Eventually consistent with /api/v1/items by design.")
    public InventorySummary summary() {
        return summaries.get();
    }
}
