package dev.tahir.blueprint.api.dto;

import java.time.Instant;
import java.util.UUID;

import dev.tahir.blueprint.domain.Item;

public record ItemResponse(
        UUID id, String name, String description, int quantity, long version, Instant createdAt, Instant updatedAt) {

    public static ItemResponse from(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getQuantity(),
                item.getVersion(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
