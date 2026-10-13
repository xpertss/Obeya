package org.xpertss.obeya.stubmodel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A scripted Anthropic Messages endpoint ({@code POST /v1/messages}) for tests and spikes.
 *
 * <p>The {@code X-Stub-Script} request header names a script file, {@code <scripts>/<name>.json}.
 * A script is {@code {"turns": [...]}}; each turn holds the assistant {@code content} blocks, the
 * {@code usage}, and optionally {@code id} and {@code stop_reason}. The turn replayed is the
 * number of assistant messages in the request, so the server keeps no state between requests.
 * {@code "stream": true} replies as server-sent events, otherwise as one JSON message.
 */
public final class StubModelServer implements AutoCloseable {

    static final String SCRIPT_HEADER = "X-Stub-Script";

    private static final Pattern SCRIPT_NAME = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Path scripts;
    private final HttpServer http;

    private StubModelServer(Path scripts, int port) throws IOException {
        this.scripts = scripts;
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.http.createContext("/", this::handle);
        this.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Starts a server on {@code port} (0 picks a free one) that reads scripts from {@code scripts}. */
    public static StubModelServer start(Path scripts, int port) throws IOException {
        StubModelServer server = new StubModelServer(scripts, port);
        server.http.start();
        return server;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/v1/messages")) {
                sendError(exchange, 404, "not_found_error", "only /v1/messages is served");
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                sendError(exchange, 405, "invalid_request_error", "use POST");
                return;
            }
            String name = exchange.getRequestHeaders().getFirst(SCRIPT_HEADER);
            if (name == null || !SCRIPT_NAME.matcher(name).matches()) {
                sendError(exchange, 400, "invalid_request_error", SCRIPT_HEADER + " must name a script");
                return;
            }
            Path file = scripts.resolve(name + ".json");
            if (!Files.isRegularFile(file)) {
                sendError(exchange, 400, "invalid_request_error", "no script " + file);
                return;
            }

            JsonNode request;
            JsonNode script;
            try {
                request = mapper.readTree(exchange.getRequestBody());
                script = mapper.readTree(file.toFile());
            } catch (JacksonException e) {
                sendError(exchange, 400, "invalid_request_error", e.getOriginalMessage());
                return;
            }

            int turn = 0;
            for (JsonNode message : request.path("messages")) {
                if (message.path("role").asString("").equals("assistant")) turn++;
            }
            JsonNode turns = script.path("turns");
            if (turn >= turns.size()) {
                sendError(exchange, 400, "invalid_request_error",
                    "script " + name + " has " + turns.size() + " turns; the request asks for turn " + turn);
                return;
            }

            Reply reply = new Reply(mapper, turns.get(turn), request.path("model").asString("stub"),
                "msg_stub_" + name + "_" + turn);
            if (request.path("stream").asBoolean(false)) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    reply.writeEvents(out);
                }
            } else {
                send(exchange, 200, mapper.writeValueAsBytes(reply.message()));
            }
        }
    }

    private void sendError(HttpExchange exchange, int status, String type, String message) throws IOException {
        ObjectNode body = mapper.createObjectNode().put("type", "error");
        body.putObject("error").put("type", type).put("message", message);
        send(exchange, status, mapper.writeValueAsBytes(body));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** {@code java -jar obeya-stub-model.jar --port <port> --scripts <dir>} */
    public static void main(String[] args) throws IOException {
        Integer port = null;
        Path scripts = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--port")) port = Integer.valueOf(args[i + 1]);
            if (args[i].equals("--scripts")) scripts = Path.of(args[i + 1]);
        }
        if (args.length != 4 || port == null || scripts == null) {
            System.err.println("usage: obeya-stub-model --port <port> --scripts <dir>");
            System.exit(2);
        }
        StubModelServer server = start(scripts, port);
        System.out.println("stub model on http://127.0.0.1:" + server.port() + "/v1/messages, scripts in "
            + scripts.toAbsolutePath());
    }
}
