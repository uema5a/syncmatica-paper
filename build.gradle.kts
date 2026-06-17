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
    // One jar serves every supported server. We compile against the 1.21.1 API (the lowest version
    // whose command API is stable, so no experimental opt-in is needed) and ship with api-version '1.20',
    // which loads on 1.20.5+ through 26.x. Paper has been Mojang-mapped at runtime since 1.20.5, so the
    // reflective NMS transport in RawChannel resolves by Mojang names with no remapping; the per-version
    // NMS shape differences (ResourceLocation/Identifier, DiscardedPayload ByteBuf/byte[]) are handled at
    // runtime there. Verified to enable cleanly on Paper/Folia 1.20.6, Paper 1.21.1, and Folia 26.1.2.
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // Bundled (shaded + relocated) so the plugin's own JSON handling never clashes
    // with whatever Gson version the server or other plugins ship.
    implementation("com.google.code.gson:gson:2.11.0")

    // Unit testing
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Target Java 21: it's what 1.20.6/1.21.x servers run on, and a release-21 jar also runs on 26.x's
    // Java 25. (Going higher would lock out the 1.20.6/1.21.x line, which never gets Java 25.)
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
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
        minecraftVersion("1.21.1")
    }
}
