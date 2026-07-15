package dev.tahir.blueprint.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ItemRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Min(0) @Max(1_000_000) int quantity) {}
