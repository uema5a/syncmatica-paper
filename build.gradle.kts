plugins {
    java
    // Runs a real Paper server straight from Gradle: `./gradlew runServer`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    // Builds a shaded/relocatable fat-jar so bundled libs don't clash with other plugins.
    id("com.gradleup.shadow") version "9.4.2"
}

group = property("group") as String
version = property("version") as String

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // Paper API for MC 26.1.x. Unobfuscated since 26.1 — no remapping needed.
    // New (2026) version scheme: <year>.<drop>.<patch>.build.<n>-<channel>.
    // Pinned to the latest stable build for reproducible builds; bump as needed.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.68-stable")

    // Bundled (shaded + relocated) so the plugin's own JSON handling never clashes
    // with whatever Gson version the server or other plugins ship.
    implementation("com.google.code.gson:gson:2.11.0")

    // Unit testing
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // MC 26.1 servers run on Java 25; compile against the same toolchain.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        // Inject the project version into paper-plugin.yml at build time.
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        // Relocate bundled libraries to avoid runtime clashes with the server/other plugins.
        relocate("com.google.gson", "ch.uemasa.syncmatica.libs.gson")
        archiveClassifier.set("")
    }

    // Disable the thin jar so it can't overwrite the shaded jar (they share a name).
    jar {
        enabled = false
    }

    // `build` produces the shaded jar by default.
    build {
        dependsOn(shadowJar)
    }

    runServer {
        // The Paper version the dev server will download and launch.
        minecraftVersion("26.1.2")
    }
}
