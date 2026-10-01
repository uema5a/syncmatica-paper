package ch.uemasa.syncmatica.clienttest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A real Paper server running the plugin under test, started as a child process. The server
 * directory is prepared by the {@code preparePaperServer} Gradle task.
 */
final class PaperServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("paper");

    // The first start downloads and patches the vanilla server, so give it room.
    private static final long STARTUP_TIMEOUT_SECONDS = 300;
    private static final int BIND_ATTEMPTS = 3;

    private final Process process;
    private final Path dir;
    private final int port;
    private final Thread reader;
    private final Thread killOnExit;
    private final List<String> output = new ArrayList<>();
    private final CountDownLatch ready = new CountDownLatch(1);

    private PaperServer(Process process, Path dir, int port) {
        this.process = process;
        this.dir = dir;
        this.port = port;
        // Registered before anything else can throw, so a failed start never leaves a server behind.
        this.killOnExit = new Thread(() -> kill(process));
        Runtime.getRuntime().addShutdownHook(killOnExit);
        this.reader = new Thread(this::pump, "paper-output");
        reader.setDaemon(true);
        reader.start();
    }

    /** Starts a server on a fresh world with empty plugin data. */
    static PaperServer start() throws IOException, InterruptedException {
        return start(true);
    }

    /**
     * Stops this server and starts it again on the same world and plugin data, so whatever the
     * plugin saved to disk has to be loaded back.
     */
    PaperServer restart() throws IOException, InterruptedException {
        close();
        return start(false);
    }

    private static PaperServer start(boolean fresh) throws IOException, InterruptedException {
        Path dir = Path.of(System.getProperty("syncmatica.test.serverDir"));
        if (!Files.isRegularFile(dir.resolve("paper.jar"))) {
            throw new IllegalStateException("Paper server not prepared; run through the runClientGameTest task");
        }
        if (fresh) {
            for (String name : List.of("world", "world_nether", "world_the_end", "plugins/SyncmaticaPaper")) {
                deleteRecursively(dir.resolve(name));
            }
        }
        // The port is only reserved until the probe socket closes, so retry if someone grabs it first.
        for (int attempt = 1; ; attempt++) {
            PaperServer server = launch(dir, freePort());
            try {
                server.awaitReady();
                return server;
            } catch (IllegalStateException e) {
                server.close();
                boolean bindFailure = !server.lines(l -> l.contains("FAILED TO BIND")).isEmpty();
                if (!bindFailure || attempt == BIND_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static PaperServer launch(Path dir, int port) throws IOException {
        // Same JVM binary as the client, which already runs the Java version this Minecraft needs.
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-Xmx1G", "-jar", "paper.jar", "--nogui", "--port", Integer.toString(port))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        return new PaperServer(process, dir, port);
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_TIMEOUT_SECONDS);
        while (!ready.await(1, TimeUnit.SECONDS)) {
            if (!process.isAlive()) {
                reader.join(5_000);
                throw new IllegalStateException("Paper exited during startup (code " + process.exitValue() + "):\n" + tail(50));
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Paper did not finish starting within " + STARTUP_TIMEOUT_SECONDS + "s:\n" + tail(50));
            }
        }
    }

    int port() {
        return port;
    }

    /** The plugin's data folder on the server. */
    Path pluginData() {
        return dir.resolve("plugins/SyncmaticaPaper");
    }

    /** Runs a command on the server console. */
    void command(String command) throws IOException {
        OutputStream stdin = process.getOutputStream();
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Lines the server has printed so far that match {@code filter}. */
    List<String> lines(Predicate<String> filter) {
        synchronized (output) {
            return output.stream().filter(filter).toList();
        }
    }

    private String tail(int count) {
        synchronized (output) {
            return String.join("\n", output.subList(Math.max(0, output.size() - count), output.size()));
        }
    }

    private void pump() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                LOGGER.info(line);
                synchronized (output) {
                    output.add(line);
                }
                if (line.contains("Done (")) {
                    ready.countDown();
                }
            }
        } catch (IOException ignored) {
            // process went away
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Stops the server gracefully, falling back to a hard kill, and waits for its last log lines. */
    @Override
    public void close() {
        try {
            if (process.isAlive()) {
                try {
                    OutputStream stdin = process.getOutputStream();
                    stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (IOException ignored) {
                    // stdin already closed; the kill below takes care of it
                }
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    kill(process);
                }
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(killOnExit);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down; the hook runs anyway
            }
        }
    }
}
