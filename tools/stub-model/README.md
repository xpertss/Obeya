# tools/stub-model

A scripted Anthropic Messages endpoint (`POST /v1/messages`) for tests and spikes. It is
deterministic and free, and it never calls a real model.

```bash
java -jar target/obeya-stub-model-0.1.0-SNAPSHOT.jar --port 8089 --scripts <dir>
```

In a test, `StubModelServer.start(scriptsDir, 0)` starts it on a free port (`port()`), and
`close()` stops it.

## Selecting a script

Each request names its script in the `X-Stub-Script` header: `X-Stub-Script: fix-test` reads
`<dir>/fix-test.json`. The turn replayed is **the number of assistant messages in the request**,
so the first call gets turn 0, the call after one assistant reply gets turn 1, and so on. The
server keeps no state, so tests can share it and run in parallel.

`"stream": true` replies with server-sent events: `message_start`, `ping`, then per block
`content_block_start`, its deltas (split into 16-char pieces), and `content_block_stop`, then
`message_delta` and `message_stop`. Otherwise it replies with one JSON message.

## Script format

```json
{
  "turns": [
    {
      "content": [
        { "type": "thinking", "thinking": "…", "signature": "…" },
        { "type": "text", "text": "…" },
        { "type": "tool_use", "id": "toolu_01", "name": "read", "input": { "path": "src/a.js" } }
      ],
      "usage": { "input_tokens": 1200, "output_tokens": 85,
                 "cache_creation_input_tokens": 1024, "cache_read_input_tokens": 0 }
    }
  ]
}
```

- `content`: the assistant's blocks, returned as written. A turn can hold any number of
  `tool_use` blocks.
- `usage`: returned as written.
- `stop_reason` (optional): defaults to `tool_use` when the turn has a `tool_use` block, else
  `end_turn`.
- `id` (optional): defaults to `msg_stub_<script>_<turn>`.
- The response's `model` echoes the request's.

Errors use the Messages error shape (`{"type":"error","error":{"type":…,"message":…}}`):
`400 invalid_request_error` for a missing or unknown script, or a turn past the end of the
script; `404 not_found_error` for any path other than `/v1/messages`.
