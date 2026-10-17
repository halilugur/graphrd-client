package org.graphrd;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;

/** Boots a throwaway server for the tests (skips them when no binary is built). */
public final class TestServer {

    private static Process process;
    private static int users;

    static final int PORT = 17300;

    public static synchronized String start() throws Exception {
        String bin = System.getProperty("graphrd.bin", "");
        Assumptions.assumeTrue(!bin.isBlank() && new File(bin).canExecute(),
                "no graphrd binary at " + bin + " — run cargo build --release");
        if (users++ == 0) {
            process = new ProcessBuilder(bin, "serve", "--memory", "--port", String.valueOf(PORT), "--quiet")
                    .redirectErrorStream(true)
                    .start();
            HttpClient http = HttpClient.newHttpClient();
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url() + "/protocol"))
                            .timeout(Duration.ofSeconds(1)).GET().build();
                    if (http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                        break;
                    }
                } catch (Exception e) {
                    Thread.sleep(100);
                }
            }
        }
        return url();
    }

    public static synchronized void stop() {
        if (--users <= 0 && process != null) {
            process.destroy();
            process = null;
            users = 0;
        }
    }

    static String url() {
        return "http://localhost:" + PORT;
    }

    private TestServer() {
    }
}
