package org.xpertss.obeya.stubmodel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One scripted assistant turn, rendered as a Messages API response or as its SSE stream. */
final class Reply {

    /** Streamed strings are split into deltas of this many chars, so clients must accumulate. */
    static final int CHUNK = 16;

    private final ObjectMapper mapper;
    private final JsonNode turn;
    private final String model;
    private final String id;

    Reply(ObjectMapper mapper, JsonNode turn, String model, String defaultId) {
        this.mapper = mapper;
        this.turn = turn;
        this.model = model;
        this.id = turn.path("id").asString(defaultId);
    }

    ObjectNode message() {
        ObjectNode message = header();
        message.set("content", turn.path("content").deepCopy());
        message.put("stop_reason", stopReason());
        message.set("usage", turn.path("usage").deepCopy());
        return message;
    }

    void writeEvents(OutputStream out) throws IOException {
        ObjectNode start = header();
        start.putArray("content");
        start.putNull("stop_reason");
        ObjectNode startUsage = (ObjectNode) turn.path("usage").deepCopy();
        startUsage.put("output_tokens", 0);
        start.set("usage", startUsage);
        event(out, "message_start", mapper.createObjectNode().put("type", "message_start").set("message", start));
        event(out, "ping", mapper.createObjectNode().put("type", "ping"));

        int index = 0;
        for (JsonNode block : turn.path("content")) {
            writeBlock(out, index++, block);
        }

        ObjectNode delta = mapper.createObjectNode().put("type", "message_delta");
        delta.putObject("delta").put("stop_reason", stopReason()).putNull("stop_sequence");
        delta.set("usage", turn.path("usage").deepCopy());
        event(out, "message_delta", delta);
        event(out, "message_stop", mapper.createObjectNode().put("type", "message_stop"));
    }

    private void writeBlock(OutputStream out, int index, JsonNode block) throws IOException {
        String type = block.path("type").asString("");
        ObjectNode start = (ObjectNode) block.deepCopy();
        switch (type) {
            case "text" -> start.put("text", "");
            case "thinking" -> start.put("thinking", "").put("signature", "");
            case "tool_use" -> start.putObject("input");
            default -> { } // e.g. redacted_thinking: sent whole in the start event
        }
        event(out, "content_block_start", mapper.createObjectNode()
            .put("type", "content_block_start").put("index", index).set("content_block", start));

        switch (type) {
            case "text" -> deltas(out, index, "text_delta", "text", block.path("text").asString(""));
            case "thinking" -> {
                deltas(out, index, "thinking_delta", "thinking", block.path("thinking").asString(""));
                deltas(out, index, "signature_delta", "signature", block.path("signature").asString(""));
            }
            case "tool_use" -> deltas(out, index, "input_json_delta", "partial_json",
                mapper.writeValueAsString(block.path("input")));
            default -> { }
        }
        event(out, "content_block_stop", mapper.createObjectNode()
            .put("type", "content_block_stop").put("index", index));
    }

    private void deltas(OutputStream out, int index, String deltaType, String field, String value)
            throws IOException {
        int from = 0;
        while (from < value.length()) {
            int to = Math.min(from + CHUNK, value.length());
            if (to < value.length() && Character.isHighSurrogate(value.charAt(to - 1))) to++;
            ObjectNode event = mapper.createObjectNode().put("type", "content_block_delta").put("index", index);
            event.putObject("delta").put("type", deltaType).put(field, value.substring(from, to));
            event(out, "content_block_delta", event);
            from = to;
        }
    }

    private ObjectNode header() {
        ObjectNode message = mapper.createObjectNode()
            .put("id", id)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", model);
        message.putNull("stop_sequence");
        return message;
    }

    private String stopReason() {
        if (turn.has("stop_reason")) return turn.get("stop_reason").asString();
        for (JsonNode block : turn.path("content")) {
            if (block.path("type").asString("").equals("tool_use")) return "tool_use";
        }
        return "end_turn";
    }

    private void event(OutputStream out, String name, JsonNode data) throws IOException {
        out.write(("event: " + name + "\ndata: " + mapper.writeValueAsString(data) + "\n\n")
            .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
