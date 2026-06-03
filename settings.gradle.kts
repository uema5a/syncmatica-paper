plugins {
    // Auto-provisions the required JDK (Java 25) via the foojay Disco API,
    // so contributors don't have to install it manually.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "syncmatica-paper"
