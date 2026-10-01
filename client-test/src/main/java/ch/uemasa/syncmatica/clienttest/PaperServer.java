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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A real Paper server running the plugin under test, started as a child process for the length of
 * one test. The server directory is prepared by the {@code preparePaperServer} Gradle task.
 */
final class PaperServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("paper");

    // The first start downloads and patches the vanilla server, so give it room.
    private static final long STARTUP_TIMEOUT_SECONDS = 300;

    private final Process process;
    private final int port;
    private final List<String> output = new ArrayList<>();
    private final CountDownLatch ready = new CountDownLatch(1);

    private PaperServer(Process process, int port) {
        this.process = process;
        this.port = port;
        Thread reader = new Thread(this::pump, "paper-output");
        reader.setDaemon(true);
        reader.start();
    }

    static PaperServer start() throws IOException, InterruptedException {
        Path dir = Path.of(System.getProperty("syncmatica.test.serverDir"));
        if (!Files.isRegularFile(dir.resolve("paper.jar"))) {
            throw new IllegalStateException("Paper server not prepared; run through the runClientGameTest task");
        }
        int port = freePort();
        // Same JVM binary as the client, which already runs the Java version this Minecraft needs.
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-Xmx1G", "-jar", "paper.jar", "--nogui", "--port", Integer.toString(port))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        PaperServer server = new PaperServer(process, port);
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
        if (!server.ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            server.close();
            throw new IllegalStateException("Paper did not finish starting within " + STARTUP_TIMEOUT_SECONDS + "s");
        }
        return server;
    }

    int port() {
        return port;
    }

    /** Lines the server has printed so far that match {@code filter}. */
    List<String> lines(Predicate<String> filter) {
        synchronized (output) {
            return output.stream().filter(filter).toList();
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            if (process.waitFor(60, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IOException | InterruptedException ignored) {
            // fall through to a hard kill
        }
        process.destroyForcibly();
    }
}
