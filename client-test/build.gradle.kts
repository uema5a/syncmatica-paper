import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2"
}

val minecraftVersion = property("minecraft_version") as String

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
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

// Test hooks that run inside the Paper server (see SyncTestHelper). Built against the Paper API
// only; never shipped.
val helper: SourceSet by sourceSets.creating
dependencies {
    "helperCompileOnly"("io.papermc.paper:paper-api:${property("paper_api_version")}")
}
val helperJar = tasks.register<Jar>("helperJar") {
    from(helper.output)
    archiveFileName.set("SyncTestHelper.jar")
    destinationDirectory.set(layout.buildDirectory.dir("helper"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

fabricApi {
    configureTests {
        createSourceSet = false
        enableGameTests = false
        enableClientGameTests = true
        // Running the client test means accepting the Minecraft EULA (https://aka.ms/MinecraftEULA).
        eula = true
    }
}

// A throwaway Paper server under build/. The jar is cached; worlds and plugin data are reset by
// each test itself.
val serverDir = layout.buildDirectory.dir("paper-server/$minecraftVersion")

val preparePaperServer = tasks.register("preparePaperServer") {
    dependsOn(gradle.includedBuild("syncmatica-paper").task(":shadowJar"))
    dependsOn(helperJar)
    val helperFile = helperJar.flatMap { it.archiveFile }
    val dir = serverDir
    val mc = minecraftVersion
    val paperBuild = project.property("paper_build") as String
    val paperSha256 = project.property("paper_sha256") as String
    // The exact jar the parent build just produced, not whatever else is lying around in build/libs.
    val pluginVersion = Properties()
        .apply { file("../gradle.properties").reader().use { load(it) } }
        .getProperty("version")
    val pluginJar = file("../build/libs/syncmatica-paper-$pluginVersion.jar")
    outputs.upToDateWhen { false }
    doLast {
        val root = dir.get().asFile
        root.mkdirs()

        val paperJar = root.resolve("paper.jar")
        if (!paperJar.exists() || sha256(paperJar) != paperSha256) {
            downloadPaper(mc, paperBuild, paperSha256, paperJar)
        }

        if (!pluginJar.isFile) {
            throw GradleException("Plugin jar not found: ${pluginJar.name}")
        }
        val plugins = root.resolve("plugins").apply { deleteRecursively(); mkdirs() }
        pluginJar.copyTo(plugins.resolve("SyncmaticaPaper.jar"))
        helperFile.get().asFile.copyTo(plugins.resolve("SyncTestHelper.jar"))
        // Keep the test server from reporting usage stats on every run.
        plugins.resolve("bStats").apply { mkdirs() }.resolve("config.yml")
            .writeText("enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000000\nlogFailedRequests: false\n")

        root.resolve("eula.txt").writeText("eula=true\n")
        // Loopback only: the server runs in offline mode with no whitelist while the test is up.
        root.resolve("server.properties").writeText(
            """
            server-ip=127.0.0.1
            online-mode=false
            white-list=false
            enforce-whitelist=false
            level-seed=syncmatica
            gamemode=creative
            difficulty=peaceful
            generate-structures=false
            spawn-protection=0
            view-distance=4
            simulation-distance=4
            motd=syncmatica client test
            """.trimIndent() + "\n"
        )
    }
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

/** Fetches a pinned Paper build and only keeps it if the SHA-256 matches. */
fun downloadPaper(mc: String, build: String, expectedSha256: String, target: File) {
    val api = "https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/$build"
    val part = File(target.parentFile, target.name + ".part")
    var lastError: Exception? = null
    repeat(3) {
        try {
            @Suppress("UNCHECKED_CAST")
            val info = JsonSlurper().parseText(fetch(api).decodeToString()) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val download = (info["downloads"] as Map<String, Map<String, Any>>)["server:default"]
                ?: throw GradleException("No server download for Paper $mc build $build")
            part.writeBytes(fetch(download["url"] as String))
            val actual = sha256(part)
            if (actual != expectedSha256) {
                part.delete()
                throw GradleException("Paper $mc build $build: SHA-256 mismatch (got $actual)")
            }
            part.copyTo(target, overwrite = true)
            part.delete()
            return
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw GradleException("Could not download Paper $mc build $build", lastError)
}

fun fetch(url: String): ByteArray {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = 30_000
    conn.readTimeout = 120_000
    conn.setRequestProperty("User-Agent", "syncmatica-paper-client-test (https://github.com/uema5a/syncmatica-paper)")
    try {
        if (conn.responseCode != 200) {
            throw GradleException("GET $url returned HTTP ${conn.responseCode}")
        }
        return conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
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
