import groovy.json.JsonSlurper
import java.net.URI

plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2"
}

val minecraftVersion = property("minecraft_version") as String

repositories {
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // The unmodified client stack a player would run. It's on the compile classpath so the tests can
    // drive the same code paths the in-game buttons use and read client state directly.
    implementation("maven.modrinth:syncmatica:${property("syncmatica_version")}")
    implementation("maven.modrinth:litematica:${property("litematica_version")}")
    implementation("maven.modrinth:malilib:${property("malilib_version")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

fabricApi {
    configureTests {
        createSourceSet = false
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

// A throwaway Paper server under build/, rebuilt from a clean world on every run.
val serverDir = layout.buildDirectory.dir("paper-server/$minecraftVersion")

val preparePaperServer = tasks.register("preparePaperServer") {
    val pluginJarTask = gradle.includedBuild("syncmatica-paper").task(":shadowJar")
    dependsOn(pluginJarTask)
    val pluginLibs = file("../build/libs")
    val dir = serverDir
    val mc = minecraftVersion
    outputs.upToDateWhen { false }
    doLast {
        val root = dir.get().asFile
        root.mkdirs()

        // Paper jar, cached across runs.
        val paperJar = root.resolve("paper.jar")
        if (!paperJar.exists()) {
            val api = "https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/latest"
            @Suppress("UNCHECKED_CAST")
            val build = JsonSlurper().parse(URI(api).toURL()) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val download = (build["downloads"] as Map<String, Map<String, Any>>)["server:default"]!!
            URI(download["url"] as String).toURL().openStream().use { input ->
                paperJar.outputStream().use { input.copyTo(it) }
            }
        }

        // Swap in the current plugin build. Worlds and plugin data are reset by each test itself.
        val plugins = root.resolve("plugins").apply { deleteRecursively(); mkdirs() }
        val jar = pluginLibs.listFiles { f -> f.name.endsWith(".jar") }!!.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No plugin jar in ../build/libs")
        jar.copyTo(plugins.resolve("SyncmaticaPaper.jar"), overwrite = true)

        root.resolve("eula.txt").writeText("eula=true\n")
        root.resolve("server.properties").writeText(
            """
            online-mode=false
            white-list=false
            enforce-whitelist=false
            generate-structures=false
            spawn-protection=0
            view-distance=4
            simulation-distance=4
            motd=syncmatica client test
            """.trimIndent() + "\n"
        )
    }
}

tasks.named<JavaExec>("runClientGameTest") {
    dependsOn(preparePaperServer)
    val dir = serverDir
    doFirst {
        systemProperty("syncmatica.test.serverDir", dir.get().asFile.absolutePath)
    }
    // The client talks to an external server, so the gametest network synchronizer (which assumes
    // it owns the server) has to stay out of the way.
    systemProperty("fabric.client.gametest.disableNetworkSynchronizer", "true")
}
