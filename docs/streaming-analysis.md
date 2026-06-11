# Streaming Architecture Analysis: tapalakbot-v2 + clj-harness

## Executive Summary

The streaming implementation spans two repos with a clean layered architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    tapalakbot-v2                             │
│  ┌─────────────────┐    ┌──────────────────────────────┐   │
│  │   bot.clj        │    │      core.clj                │   │
│  │  (Telegram UX)   │───▶│  (Agent orchestration)       │   │
│  └─────────────────┘    └──────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    clj-harness                               │
│  ┌─────────────────┐    ┌──────────────────────────────┐   │
│  │   core.clj       │    │      stream.clj               │   │
│  │  (Entry point)   │───▶│  (SSE + Agent loop)           │   │
│  └─────────────────┘    └──────────────────────────────┘   │
│  ┌─────────────────┐    ┌──────────────────────────────┐   │
│  │   llm.clj        │    │  telegram/streaming.clj       │   │
│  │  (Non-streaming) │    │  (Telegram progressive edit)  │   │
│  └─────────────────┘    └──────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Layer 1: HTTP/SSE Streaming (clj-harness/stream.clj)

### `http-stream-lines`
- **Thread-based, NOT go-block**: Runs HTTP on raw `Thread` to avoid blocking core.async thread pool
- **SSE parsing**: Reads `data: ` prefixed lines, parses JSON chunks
- **Channel**: Returns `core.async/chan` with 256 buffer size
- **Timeout**: 120s total timeout, 30s connect timeout
- **Protocol**: HTTP/1.1 (required for SSE)

### `llm-stream`
- **Provider support**: DeepSeek, OpenRouter
- **Body**: OpenAI-compatible `{"stream": true, ...}`
- **Returns**: Channel of parsed chunks `{:delta "text"}` / `{:tool-calls [...]}` / `{:done true}`

## Layer 2: Stream Consumer (clj-harness/stream.clj)

### `consume-stream`
- **Blocking loop**: Reads from channel until `:done`
- **Content accumulation**: StringBuilder for text content
- **Tool call chunking**: Reassembles tool_calls from incremental chunks (handles multi-chunk arguments)
- **Stream callback**: Calls `stream-cb` for each text delta

### `stream-agent` - The Core Agent Loop
- **Full agent loop**: Handles tool execution, max-turns, guardrails
- **Status callbacks**: Russian status messages (`"🧠 Анализирую запрос..."`, `"🔧 Выполняю search..."`)
- **Event bus**: Optional `events>` channel for structured events
- **Guardrails integration**: Nudges, step-blocking, retry logic
- **Heap support**: Large tool outputs stored in heap with compact summaries

## Layer 3: Entry Points (clj-harness/core.clj)

### `handle-message-stream!`
```clojure
(defn handle-message-stream!
  [bot user-id text stream-cb & {:keys [status-cb events> abort-signal]}]
  ;; ...
  (stream/stream-agent
    :model (:model (:config bot))
    :messages msgs
    :tool-map (tool-map (:tools bot))
    :tool-schemas (tool-schemas (:tools bot))
    :stream-cb stream-cb
    :status-cb status-cb
    ...))
```

### `handle-message-async`
- **Non-blocking wrapper**: Spawns Thread, returns core.async channel
- **Streaming mode**: `{:stream? true}` returns channel of `{:delta ...}` chunks

## Layer 4: tapalakbot Integration

### `core.clj/ask-stream` - Agent-First Path
```clojure
(defn ask-stream
  [user-id text status-cb]
  (let [cards-atom (atom [])
        stats-atom (atom nil)
        result (binding [*captured-cards* cards-atom
                         *captured-stats* stats-atom]
                 (h/handle-message-stream!
                   @tapalakbot user-id text
                   (fn [chunk] nil)  ;; stream-cb: DUMMY! Doesn't stream to Telegram
                   :status-cb status-cb))
        agent-text (if (map? result) (:content result) (str result))
        cards @cards-atom
        stats @stats-atom]
    {:text agent-text :cards cards :stats stats}))
```

**Key insight**: `ask-stream` uses `handle-message-stream!` but with a **dummy stream-cb** `(fn [chunk] nil)`. It only uses:
- `status-cb` for progress updates (tool execution phases)
- `*captured-cards*` and `*captured-stats*` for deterministic card rendering
- Returns complete result, NOT streaming to Telegram

### `bot.clj/process-agent-message` - Live Streaming Path
```clojure
(defn- process-agent-message
  [{:keys [chat-id user-id text]}]
  (let [buf (StringBuilder.)
        stream-cb (fn [delta]
                    (.append buf delta)
                    ;; Throttled Telegram message editing
                    (when (> elapsed 1500)
                      (tg/edit-message chat-id msg-id html)))
        status-cb (fn [status]
                    (.setLength buf 0)  ;; Clear buffer on status change
                    (tg/edit-message chat-id msg-id status))
        result (hc/handle-message-stream! bot uid text stream-cb :status-cb status-cb)]
    ;; Final edit with complete result
    (tg/edit-message chat-id msg-id html)))
```

**This path DOES stream to Telegram** with:
- Live text preview (throttled every 1500ms)
- Status updates for tool execution phases
- Buffer clear on status change (prevents showing partial tool output)

### `bot.clj/handle-orchestrated` - Agent-First with Cards
```clojure
(defn- handle-orchestrated
  [{:keys [chat-id user-id text]}]
  (let [agent-future (future (t/ask-stream uid text status-cb))
        result (deref agent-future 180000 :timeout)]
    ;; Render cards + agent text
    (render/render-reply reply)))
```

**Uses `ask-stream`** (dummy stream-cb), so NO live streaming. Waits for complete result with cards.

## Data Flow Comparison

### Path 1: Live Streaming (process-agent-message)
```
User message
    │
    ▼
handle-message-stream!
    │
    ├─► stream-agent
    │       │
    │       ├─► llm-stream (SSE)
    │       │       │
    │       │       ├─► stream-cb(delta) ──► buf.append ──► Telegram edit
    │       │       └─► status-cb ──► Telegram edit
    │       │
    │       └─► tool execution ──► status-cb ──► Telegram edit
    │
    └─► Final result ──► Telegram edit
```

### Path 2: Agent-First with Cards (handle-orchestrated)
```
User message
    │
    ▼
ask-stream (core.clj)
    │
    ├─► handle-message-stream!
    │       │
    │       ├─► stream-agent
    │       │       │
    │       │       ├─► llm-stream (SSE)
    │       │       │       │
    │       │       │       └─► stream-cb (DUMMY: fn [chunk] nil)
    │       │       │
    │       │       └─► tool execution
    │       │               │
    │       │               ├─► search-execute
    │       │               │       │
    │       │               │       └─► *captured-cards* ◄── atom
    │       │               │
    │       │               └─► status-cb ──► Telegram edit
    │       │
    │       └─► Returns complete result
    │
    └─► {:text :cards :stats}
            │
            ▼
        render/render-reply (deterministic HTML)
            │
            ▼
        Telegram send
```

## Performance Characteristics

### Latency Profile
- **SSE connection**: ~100-300ms (TLS + HTTP/1.1 handshake)
- **First token**: ~500-2000ms (depends on model)
- **Tool execution**: 2-30s per tool (search, research, market_stats)
- **Total agent turn**: 10-90s (depends on tools called)

### Throttling
- **stream-cb**: Called for EVERY token (no throttling in clj-harness)
- **Telegram edit**: Throttled in bot.clj (1500ms for streaming, 800ms in streaming.clj)
- **Status updates**: Immediate (no throttle)

### Memory
- **Buffer**: StringBuilder in bot.clj (unbounded until edit)
- **Cards**: Atom accumulation in core.clj
- **Heap**: Optional for large tool outputs (>2K chars)

## Key Design Decisions

1. **Thread vs go-block for HTTP**: Correct choice - HTTP I/O blocks the thread, go-blocks would starve core.async thread pool

2. **Dummy stream-cb in ask-stream**: By design - agent-first path prioritizes card capture over live streaming. Cards require complete tool results before rendering.

3. **Two streaming paths**: 
   - `process-agent-message`: Live streaming for conversational responses
   - `handle-orchestrated`: Batch rendering for card-heavy responses

4. **Buffer clear on status change**: Prevents showing partial tool output during streaming. Status messages reset the display.

5. **Event bus (events>)**: Optional structured events for observability. Not used in tapalakbot yet.

## Potential Improvements

1. **Hybrid streaming**: Stream agent text live, then render cards when ready
   ```clojure
   ;; In ask-stream:
   stream-cb (fn [delta]
               (when @cards-atom  ;; Cards ready? Stop streaming
                 (render-cards-and-stream))
               (tg/edit-message ...))
   ```

2. **Progressive card rendering**: Show cards as they're found (per-tool), not all at once

3. **Event-driven architecture**: Use `events>` channel for structured observability

4. **Abort signals**: Cancel long-running tool calls on user `/reset`

5. **Heap integration**: Store search results in heap, let agent fetch specific items
