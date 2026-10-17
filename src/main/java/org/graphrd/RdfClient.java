package org.graphrd;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The low-level client: SPARQL queries and updates, statements in any RDF
 * format, and repository management — everything else in this library is
 * built on it. One instance is safe to share between threads; HTTP
 * connections are kept alive and pooled, requests run on a small owned
 * executor that {@link #close()} shuts down. No dependencies.
 *
 * <pre>{@code
 * RdfClient client = RdfClient.builder()
 *         .baseUrl("http://localhost:7200")
 *         .connectTimeout(Duration.ofSeconds(5))
 *         .requestTimeout(Duration.ofSeconds(120))
 *         .build();
 * }</pre>
 */
public final class RdfClient implements AutoCloseable {

    private static final AtomicInteger POOLS = new AtomicInteger();

    private final HttpClient http;
    private final String base;
    private final Duration requestTimeout;
    private final ExecutorService io; // null when the HttpClient came from outside
    private final boolean ownsExecutor;

    /** A client with defaults: 5 s connect, 60 s request, cached daemon threads. */
    public RdfClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(5), Duration.ofSeconds(60));
    }

    /** A client over a caller-supplied {@link HttpClient} (not closed by {@link #close()}). */
    public RdfClient(String baseUrl, HttpClient http) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.http = http;
        this.requestTimeout = Duration.ofSeconds(60);
        this.io = null;
        this.ownsExecutor = false;
    }

    private RdfClient(String baseUrl, Duration connectTimeout, Duration requestTimeout) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.requestTimeout = requestTimeout;
        this.io = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "graphrd-client-" + POOLS.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.ownsExecutor = true;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(io)
                .build();
    }

    /** Builds a client with explicit connection settings. */
    public static Builder builder() {
        return new Builder();
    }

    /** {@code RdfClient.builder().baseUrl(…).connectTimeout(…).requestTimeout(…).build()}. */
    public static final class Builder {

        private String baseUrl;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(60);

        public Builder baseUrl(String url) {
            this.baseUrl = url;
            return this;
        }

        /** How long the TCP/TLS handshake may take. */
        public Builder connectTimeout(Duration d) {
            this.connectTimeout = d;
            return this;
        }

        /** How long any single request may take end to end. */
        public Builder requestTimeout(Duration d) {
            this.requestTimeout = d;
            return this;
        }

        public RdfClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            return new RdfClient(baseUrl, connectTimeout, requestTimeout);
        }
    }

    // ------------------------------------------------------------ SPARQL

    /** Runs a SELECT; every row is variable name to value (IRIs as strings). */
    public List<Map<String, String>> query(String repo, String sparql) {
        try {
            String body = send("POST", "/repositories/" + enc(repo), sparql,
                    "application/sparql-query", "application/sparql-results+json");
            List<Map<String, String>> rows = new ArrayList<>();
            Map<String, Object> results = Json.obj(Json.obj(Json.parse(body)).get("results"));
            for (Object b : Json.arr(results.get("bindings"))) {
                Map<String, Object> binding = Json.obj(b);
                Map<String, String> row = new LinkedHashMap<>();
                binding.forEach((v, term) -> row.put(v, Json.str(Json.obj(term).get("value"))));
                rows.add(row);
            }
            return rows;
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    /** Runs an ASK query. */
    public boolean ask(String repo, String sparql) {
        try {
            String body = send("POST", "/repositories/" + enc(repo), sparql,
                    "application/sparql-query", "application/sparql-results+json");
            return Boolean.TRUE.equals(Json.obj(Json.parse(body)).get("boolean"));
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    /** Runs a SPARQL update (DELETE/INSERT, INSERT DATA, …). */
    public void update(String repo, String sparql) {
        try {
            send("POST", "/repositories/" + enc(repo) + "/statements", sparql,
                    "application/sparql-update", null);
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    // ------------------------------------------------------------ statements

    /** Adds statements (N-Triples, Turtle, JSON-LD — by content type). */
    public void addStatements(String repo, String rdf, String contentType) {
        try {
            send("POST", "/repositories/" + enc(repo) + "/statements", rdf, contentType, null);
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    /** Reads the default graph back in the asked format (Turtle, JSON-LD, …). */
    public String getStatements(String repo, String accept) {
        try {
            return send("GET", "/repositories/" + enc(repo) + "/statements", null, null, accept);
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    // ------------------------------------------------------------ repositories

    /** Creates a repository; does nothing when it already exists. */
    public void createRepository(String repo) {
        try {
            int code = sendCode("POST", "/rest/repositories",
                    "{\"id\":\"" + repo + "\"}", "application/json");
            if (code != 201 && code != 409) {
                throw new GraphRdException("create repository: HTTP " + code);
            }
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    /** Deletes a repository with all its data. */
    public void deleteRepository(String repo) {
        try {
            send("DELETE", "/rest/repositories/" + enc(repo), null, null, null);
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    /** The ids of every repository on the server. */
    public List<String> listRepositories() {
        try {
            List<String> out = new ArrayList<>();
            for (Object r : Json.arr(Json.parse(send("GET", "/rest/repositories", null, null, null)))) {
                out.add(Json.str(Json.obj(r).get("id")));
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new GraphRdException(e);
        }
    }

    /** Whether the server answers at all. */
    public boolean ping() {
        try {
            return !send("GET", "/protocol", null, null, null).isBlank();
        } catch (GraphRdException e) {
            return false;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Stops the owned request executor; in-flight requests finish, no new ones start. */
    @Override
    public void close() {
        if (ownsExecutor && io != null) {
            io.shutdown();
        }
    }

    // ------------------------------------------------------------ plumbing

    private String send(String method, String path, String body, String contentType, String accept)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(requestTimeout);
        b.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (accept != null) {
            b.header("Accept", accept);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new GraphRdException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private int sendCode(String method, String path, String body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
