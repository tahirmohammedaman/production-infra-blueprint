// Shared build conventions for every service.
//
// Deliberately a `subprojects` block rather than precompiled convention plugins in
// buildSrc. Convention plugins cannot see the version catalog without a generated-accessor
// workaround, and at three modules that indirection costs more clarity than it buys. If a
// fourth service ever appears, this is the thing to revisit.

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "dev.tahir"
    version = providers.environmentVariable("APP_VERSION").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(
                org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES,
            )
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        // Testcontainers needs a container runtime; on rootless podman hosts the socket is
        // not at the docker default. Honour it if the caller exported one.
        providers.environmentVariable("DOCKER_HOST").orNull?.let { environment("DOCKER_HOST", it) }
    }
}

// Formatting is a build gate, not a review topic. `spotlessCheck` runs as part of `check`,
// so CI fails on style drift without a human having to notice it.
spotless {
    java {
        target("*/src/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "")
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint()
    }
    format("misc") {
        target("*/src/**/*.yml", "*/src/**/*.sql")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
