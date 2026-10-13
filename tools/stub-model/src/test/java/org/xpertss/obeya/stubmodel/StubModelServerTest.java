package org.xpertss.obeya.stubmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StubModelServerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static StubModelServer server;

    @BeforeAll
    static void start() throws Exception {
        server = StubModelServer.start(scripts(), 0);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void firstTurnAsOneMessage() throws Exception {
        HttpResponse<String> response = post("fix-test", request(0, false));
        assertEquals(200, response.statusCode());

        JsonNode message = MAPPER.readTree(response.body());
        assertEquals("message", message.path("type").asString());
        assertEquals("assistant", message.path("role").asString());
        assertEquals("claude-test", message.path("model").asString());
        assertEquals("msg_stub_fix-test_0", message.path("id").asString());
        assertEquals("tool_use", message.path("stop_reason").asString());
        assertEquals(scriptTurn(0).path("content"), message.path("content"));
        assertEquals(1024, message.path("usage").path("cache_creation_input_tokens").asInt());
    }

    @Test
    void turnIsTheAssistantMessageCount() throws Exception {
        JsonNode message = MAPPER.readTree(post("fix-test", request(1, false)).body());
        assertEquals("end_turn", message.path("stop_reason").asString());
        assertEquals(1024, message.path("usage").path("cache_read_input_tokens").asInt());
    }

    @Test
    void streamRebuildsTheScriptedTurn() throws Exception {
        HttpResponse<String> response = post("fix-test", request(0, true));
        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElseThrow());

        List<String> names = new ArrayList<>();
        ObjectNode message = null;
        List<ObjectNode> blocks = new ArrayList<>();
        List<StringBuilder> json = new ArrayList<>();
        for (String event : response.body().split("\n\n")) {
            String[] lines = event.split("\n");
            String name = lines[0].substring("event: ".length());
            JsonNode data = MAPPER.readTree(lines[1].substring("data: ".length()));
            assertEquals(name, data.path("type").asString());
            names.add(name);
            switch (name) {
                case "message_start" -> message = (ObjectNode) data.path("message");
                case "content_block_start" -> {
                    blocks.add((ObjectNode) data.path("content_block"));
                    json.add(new StringBuilder());
                }
                case "content_block_delta" -> {
                    int index = data.path("index").asInt();
                    JsonNode delta = data.path("delta");
                    ObjectNode block = blocks.get(index);
                    switch (delta.path("type").asString()) {
                        case "text_delta" -> append(block, "text", delta);
                        case "thinking_delta" -> append(block, "thinking", delta);
                        case "signature_delta" -> append(block, "signature", delta);
                        case "input_json_delta" -> json.get(index).append(delta.path("partial_json").asString());
                        default -> throw new AssertionError("unexpected delta " + delta);
                    }
                }
                case "message_delta" -> {
                    message.put("stop_reason", data.path("delta").path("stop_reason").asString());
                    message.set("usage", data.path("usage"));
                }
                default -> { }
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (!json.get(i).isEmpty()) blocks.get(i).set("input", MAPPER.readTree(json.get(i).toString()));
        }
        message.set("content", MAPPER.valueToTree(blocks));

        assertEquals("message_start", names.getFirst());
        assertEquals("message_stop", names.getLast());
        assertTrue(names.stream().filter("content_block_delta"::equals).count() > blocks.size(),
            "long strings arrive as several deltas");
        assertEquals(MAPPER.readTree(post("fix-test", request(0, false)).body()), message);
    }

    @Test
    void missingHeaderIsRefused() throws Exception {
        assertError(post(null, request(0, false)), 400, "invalid_request_error");
    }

    @Test
    void unknownScriptIsRefused() throws Exception {
        assertError(post("no-such-script", request(0, false)), 400, "invalid_request_error");
    }

    @Test
    void scriptNameCannotLeaveTheDirectory() throws Exception {
        assertError(post("../fix-test", request(0, false)), 400, "invalid_request_error");
    }

    @Test
    void turnPastTheScriptIsRefused() throws Exception {
        assertError(post("fix-test", request(2, false)), 400, "invalid_request_error");
    }

    @Test
    void otherPathsAreNotFound() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(uri("/v1/models")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertError(response, 404, "not_found_error");
    }

    private static void append(ObjectNode block, String field, JsonNode delta) {
        block.put(field, block.path(field).asString("") + delta.path(field).asString());
    }

    private static void assertError(HttpResponse<String> response, int status, String type) {
        assertEquals(status, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("error", body.path("type").asString());
        assertEquals(type, body.path("error").path("type").asString());
    }

    /** A request carrying {@code assistantTurns} earlier assistant messages. */
    private static String request(int assistantTurns, boolean stream) {
        ObjectNode request = MAPPER.createObjectNode().put("model", "claude-test").put("max_tokens", 1024);
        request.put("stream", stream);
        var messages = request.putArray("messages");
        messages.addObject().put("role", "user").put("content", "Make the tests pass.");
        for (int i = 0; i < assistantTurns; i++) {
            messages.addObject().put("role", "assistant").put("content", "turn " + i);
            messages.addObject().put("role", "user").put("content", "ok");
        }
        return MAPPER.writeValueAsString(request);
    }

    private static HttpResponse<String> post(String script, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/v1/messages"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (script != null) request.header(StubModelServer.SCRIPT_HEADER, script);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode scriptTurn(int turn) throws Exception {
        return MAPPER.readTree(scripts().resolve("fix-test.json").toFile()).path("turns").get(turn);
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static Path scripts() throws URISyntaxException {
        return Path.of(StubModelServerTest.class.getResource("scripts").toURI());
    }
}
