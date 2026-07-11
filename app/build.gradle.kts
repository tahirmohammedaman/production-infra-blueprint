plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
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

// Formatting is a build gate, not a review topic. `spotlessCheck` runs as part of
// `check`, so CI fails on style drift without a human having to notice it.
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
    format("misc") {
        target("src/**/*.yml", "src/**/*.sql", "*.md")
        trimTrailingWhitespace()
        endWithNewline()
    }
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
    // Fixed name so the Dockerfile never has to glob for a versioned artifact.
    archiveFileName = "app.jar"
    layered {
        enabled = true
    }
}

// Prints the module set the container's jlink runtime is built from, so the curated list
// in the Dockerfile can be diffed against what jdeps actually observes after a dependency
// bump. jdeps under-reports reflective and ServiceLoader use, so its output is a review
// aid, not the source of truth.
tasks.register<Exec>("printJdepsModules") {
    group = "verification"
    description = "Report JDK modules reachable from the runtime classpath"
    dependsOn(tasks.bootJar)
    val javaHome =
        javaToolchains
            .launcherFor(java.toolchain)
            .get()
            .metadata.installationPath
    commandLine(
        "$javaHome/bin/jdeps",
        "--ignore-missing-deps",
        "--print-module-deps",
        "--multi-release",
        "21",
        "--recursive",
        tasks.bootJar
            .get()
            .archiveFile
            .get()
            .asFile.absolutePath,
    )
}
