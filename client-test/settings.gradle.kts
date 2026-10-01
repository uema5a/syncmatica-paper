pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "syncmatica-client-test"

// The plugin under test is built from the parent project, so the client test always runs
// against the current working tree.
includeBuild("..")
