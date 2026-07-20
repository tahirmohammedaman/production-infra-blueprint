// Shared build conventions for every service.
//
// Deliberately a `subprojects` block rather than precompiled convention plugins in
// buildSrc. Convention plugins cannot see the version catalog without a generated-accessor
// workaround, and at three modules that indirection costs more clarity than it buys. If a
// fourth service ever appears, this is the thing to revisit.

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.spotless)
}

// Classes whose coverage figure would be noise: Spring configuration, application entry
// points, and DTO/record carriers that have no branches to get wrong.
val coverageExclusions =
    listOf(
        "**/config/**",
        "**/*Config.class",
        "**/*Configuration.class",
        "**/*Application.class",
        "**/dto/**",
    )

allprojects {
    group = "dev.tahir"
    version = providers.environmentVariable("APP_VERSION").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
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
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            // XML is what the CI summary step parses; HTML is what a human opens from the
            // build artifact. CSV exists because two lines of awk beat an XML parser in a
            // shell step.
            xml.required = true
            csv.required = true
            html.required = true
        }
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) { exclude(coverageExclusions) }
                },
            ),
        )
    }

    // Coverage is a gate, not a dashboard. The threshold is set just under the measured
    // figure so a real regression fails the build, and raising it is a deliberate commit
    // rather than a number nobody looks at. Config classes and DTOs are excluded: covering
    // a record's accessors inflates the number without testing anything.
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("jacocoTestReport"))
        classDirectories.setFrom(
            files(
                classDirectories.files.map {
                    fileTree(it) { exclude(coverageExclusions) }
                },
            ),
        )
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.80".toBigDecimal()
                }
            }
            rule {
                // A class with no coverage at all is usually a wiring mistake — something
                // was written and never exercised by any test, unit or integration.
                element = "CLASS"
                limit {
                    counter = "LINE"
                    minimum = "0.25".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.named("jacocoTestCoverageVerification"))
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
