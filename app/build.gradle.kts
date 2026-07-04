plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "dev.tahir"
version = providers.environmentVariable("APP_VERSION").getOrElse("0.1.0-SNAPSHOT")
description = "Production infrastructure blueprint reference service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.openapi.webmvc)

    // Observability: Prometheus scrape endpoint plus OTLP trace export to Tempo.
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit5)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Layered output is what the Dockerfile splits into cacheable image layers.
    layered {
        enabled = true
    }
}

// The container is built from an exploded, layered JAR rather than the fat JAR, so
// dependency layers stay cached across application-only changes.
tasks.register<Sync>("unpackBootJar") {
    dependsOn(tasks.bootJar)
    from(zipTree(tasks.bootJar.get().archiveFile))
    into(layout.buildDirectory.dir("unpacked"))
}
