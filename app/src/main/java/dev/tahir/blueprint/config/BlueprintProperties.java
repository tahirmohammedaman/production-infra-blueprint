package dev.tahir.blueprint.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-owned configuration. Bound and validated at startup so a bad value fails
 * the deployment at container start rather than at the first request that touches it.
 */
@ConfigurationProperties(prefix = "blueprint")
@Validated
public record BlueprintProperties(@NotNull Metrics metrics) {

    public record Metrics(@NotNull Duration itemCountRefresh) {}
}
