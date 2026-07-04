pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets the Java 21 toolchain be auto-provisioned on machines that do not already
    // have that JDK installed, so `./gradlew build` works on a clean checkout and in CI.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "blueprint-api"

// Repositories are declared centrally and projects are forbidden from adding their own,
// so the set of hosts this build will fetch code from is auditable in one place.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
