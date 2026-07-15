plugins {
    alias(libs.plugins.spring.boot)
}

description = "Synchronous API: owns the schema, serves reads from cache, writes the outbox"

dependencies {
    implementation(project(":platform"))

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.openapi.webmvc)
    implementation(libs.spring.kafka)
    implementation(libs.flyway.core)

    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
    testImplementation(libs.archunit.junit5)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Fixed name so the shared Dockerfile never has to glob for a versioned artifact.
    archiveFileName = "app.jar"
    layered { enabled = true }
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
