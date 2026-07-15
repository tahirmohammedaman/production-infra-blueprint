// Cross-service building blocks. Everything here is used by more than one service; a
// class used by exactly one belongs in that service, not in a shared module that slowly
// becomes a dumping ground.
plugins {
    `java-library`
    // Test fixtures: the container setup below is needed by both services, and copying it
    // into each would guarantee they drift.
    `java-test-fixtures`
}

dependencies {
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.actuator)
    api(libs.micrometer.registry.prometheus)
    api(libs.micrometer.tracing.bridge.otel)
    api(libs.opentelemetry.exporter.otlp)

    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)

    testFixturesApi(libs.testcontainers.junit)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.spring.boot.testcontainers)
}
