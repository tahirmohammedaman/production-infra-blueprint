plugins {
    alias(libs.plugins.spring.boot)
}

description = "Asynchronous consumer: idempotent processing, bounded retry, dead-letter topic"

dependencies {
    implementation(project(":platform"))

    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":platform")))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.awaitility)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "app.jar"
    layered { enabled = true }
}
