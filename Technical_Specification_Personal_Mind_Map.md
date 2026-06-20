# Personal Mind Map — Technical Specification

**Version:** 1.0
**Companion to:** Functional Specification v1.0 (Final)
**Platform:** Android (single-user, local-first)
**Audience:** The developer / agentic IDE that will build this app
**Researched:** June 2026. Where a fact is time-sensitive (DeepSeek models/pricing, library versions), verify against the source list in §13 before building.

---

## 1. Architecture in one paragraph

A fully local-first Android app written in Kotlin + Jetpack Compose. Everything that can run on the device does: speech-to-text, all storage, keyword and semantic search, embeddings, and graph traversal. The single external dependency is the **DeepSeek V4 API**, used only for the two jobs that genuinely need a frontier LLM — (a) turning a piece of captured text into structured, filed knowledge, and (b) answering questions over a small, pre-retrieved slice of the user's map. The device never sends the whole map to the cloud; it sends only the minimal context needed for the current operation. This keeps the app fast (search is local and indexed), cheap (tiny payloads to a very cheap model), private (data stays on device except the slice in flight), and resilient (capture works offline and is processed when the network returns).

---

## 2. Guiding technical principles

1. **The graph never leaves the device whole.** Both writing and reading use a local retrieval step to select only the relevant handful of nodes before any LLM call. This is the core scaling decision and it applies to *writes* (duplicate prevention) as much as *reads* (answering).
2. **Context separation is structural, not prompted.** Work/personal isolation is enforced by a `WHERE domain = ?` filter at the SQL and vector-search layer, and by only traversing same-domain edges. The model is never trusted to "be careful" — it simply never receives the irrelevant data.
3. **One local store.** The node/edge graph, full-text index, and vector index all live in a single SQLite database. No second datastore to keep in sync.
4. **Capture is always available; intelligence is deferred when offline.** Recording, transcription, editing, and queueing are 100% local and work with no network. LLM processing runs from a durable queue.
5. **Cheap, fast model for the hot path.** Use DeepSeek V4 Flash (non-thinking) for extraction, classification, and most answers; escalate to thinking-mode or V4 Pro only for genuinely hard relational reasoning.

---

## 3. Recommended tech stack

| Concern | Recommendation | Why | Alternative |
|---|---|---|---|
| Language / UI | Kotlin + Jetpack Compose, Material 3 with a custom theme | Lightest, fastest, native, best speech + lifecycle integration; single-user Android-only means cross-platform overhead buys nothing | Flutter (rejected: heavier, plugin-based STT) |
| Architecture | MVVM + unidirectional data flow; Coroutines + Flow | Standard, testable, reactive UI | — |
| Min / target SDK | minSdk 33 (Android 13), target latest | API 33 guarantees on-device `SpeechRecognizer`; user's device is new | minSdk 31 if Vosk is used for STT |
| Speech-to-text | `SpeechRecognizer.createOnDeviceSpeechRecognizer()` | On-device, free, no audio leaves phone | Vosk (offline OSS) for robust long-form; ML Kit GenAI/Gemini Nano STT once out of alpha |
| Local database | SQLite via **Room** | Embedded, battle-tested, zero deployment, supports raw SQL + recursive CTEs | — |
| Keyword search | SQLite **FTS5** virtual table | Built into SQLite, instant lexical search over names/summaries/items | — |
| Embeddings (on-device) | **ONNX Runtime Mobile** + `all-MiniLM-L6-v2` (contextual, 384-dim, ~80–90 MB) | A real transformer → context-aware embeddings, which is what makes fuzzy recall accurate; proven on Android, fully local & free | `bge-small-en-v1.5` (also contextual, ~130 MB, slightly stronger recall). **Not** `model2vec`: its static lookup embeddings are non-contextual |
| Vector search | **sqlite-vec** or **sqlite-vector** (in-SQLite KNN) | Keeps vectors in the same DB; SIMD/NEON-accelerated; ~30 MB profile | Brute-force cosine in Kotlin (fine at this scale: a few thousand 384-dim vectors search in <5 ms) |
| Graph traversal | Recursive CTEs over an `edges` table | No separate graph DB needed at personal scale; SQL handles tree + lateral links | — |
| Background / offline queue | **WorkManager** with a network constraint | Guaranteed deferred execution, survives process death, runs backlog on reconnect | — |
| LLM API client | Retrofit or Ktor + kotlinx.serialization, OpenAI-compatible REST | DeepSeek exposes an OpenAI-style API | OpenAI Kotlin SDK pointed at DeepSeek base URL |
| LLM | **DeepSeek V4 Flash** (extraction, classification, most answers); **V4 Pro** / thinking-mode (hard reasoning) | Frontier quality, 1M context, structured output + function calling, ~$0.14/M input | — |
| File parsing | `.txt` direct; `.csv` direct; `.xlsx` via a lightweight reader (e.g. fastexcel) → store a text extract | Avoids heavyweight Apache POI; we only need text for retrieval | — |
| Backup | DB + attachments serialized to human-readable JSON archive, written to Downloads via MediaStore | User can open and inspect the whole map (per functional decision; unencrypted in v1) | — |
| Secrets | DeepSeek API key in Android Keystore / EncryptedSharedPreferences | Cheap, sensible even for a personal app | Plain prefs (not recommended) |

---

## 4. Data model

A single SQLite database. The structure is a **tree-dominant graph**: a primary parent/child hierarchy (project → sub-topic → item) plus typed lateral edges (a person linked into several projects). Domain tags on nodes, edges, and items are what enforce work/personal separation.

```sql
-- A "thing": project, person, topic, hobby, idea, etc.
CREATE TABLE nodes (
  id           TEXT PRIMARY KEY,          -- UUID
  name         TEXT NOT NULL,
  summary      TEXT NOT NULL,             -- auto-generated 2-line summary
  type         TEXT NOT NULL,             -- person | project | topic | hobby | idea | ...
  domain       TEXT,                      -- work | personal | other | NULL (entities like a person can be cross-domain)
  parent_id    TEXT REFERENCES nodes(id), -- dominant tree edge; NULL for roots
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);

-- Typed lateral relationships. THIS is the heart of context separation.
CREATE TABLE edges (
  id             TEXT PRIMARY KEY,
  source_node_id TEXT NOT NULL REFERENCES nodes(id),
  target_node_id TEXT NOT NULL REFERENCES nodes(id),
  relation_type  TEXT NOT NULL,           -- colleague_on | member_of | related_to | mentioned_in | ...
  domain         TEXT NOT NULL,           -- work | personal | other
  created_at     INTEGER NOT NULL
);

-- Leaf content: facts, tasks, notes, decisions attached to a node.
CREATE TABLE items (
  id              TEXT PRIMARY KEY,
  node_id         TEXT NOT NULL REFERENCES nodes(id),
  content         TEXT NOT NULL,
  kind            TEXT NOT NULL,          -- fact | task | note | decision
  domain          TEXT NOT NULL,          -- work | personal | other  (filtered on at query time)
  status          TEXT,                   -- for tasks: open | done | NULL
  related_node_id TEXT REFERENCES nodes(id), -- e.g. a work task under Project X that involves Cam
  source_capture_id TEXT REFERENCES captures(id),
  created_at      INTEGER NOT NULL
);

-- Vectors for semantic recall (or use a sqlite-vec vec0 virtual table instead of a BLOB column).
CREATE TABLE embeddings (
  owner_type   TEXT NOT NULL,             -- node | item
  owner_id     TEXT NOT NULL,
  vector       BLOB NOT NULL,             -- 384 float32
  model_version TEXT NOT NULL,            -- re-embed if this changes
  PRIMARY KEY (owner_type, owner_id)
);

-- Attachments, always filed with explicit context (never automatic).
CREATE TABLE attachments (
  id            TEXT PRIMARY KEY,
  node_id       TEXT NOT NULL REFERENCES nodes(id),
  file_uri      TEXT NOT NULL,            -- SAF/scoped storage URI
  original_name TEXT NOT NULL,
  mime          TEXT NOT NULL,
  text_extract  TEXT,                     -- parsed text for retrieval
  context_note  TEXT,                     -- user-supplied "what this is"
  created_at    INTEGER NOT NULL
);

-- Durable capture log + offline queue + audit trail.
CREATE TABLE captures (
  id           TEXT PRIMARY KEY,
  raw_text     TEXT NOT NULL,             -- the edited transcript the user approved
  source       TEXT NOT NULL,             -- voice | paste
  status       TEXT NOT NULL,             -- pending | processed | failed
  created_at   INTEGER NOT NULL,          -- drives processing order
  processed_at INTEGER
);

-- Lexical search across the map.
CREATE VIRTUAL TABLE search_fts USING fts5(
  owner_type, owner_id, text, content=''
);
```

**Worked example — "Cam" (the cross-domain case):**
- One `nodes` row: `{type: person, domain: NULL}` — Cam is a single entity.
- `edges`: `Cam --colleague_on--> Project X {domain: work}`, `Cam --colleague_on--> Project Y {domain: work}`.
- Work open item: `items {node_id: ProjectX, content: "confirm API scope", domain: work, related_node_id: Cam}`.
- Personal facts: `items {node_id: Cam, content: "birthday 18 Aug", kind: fact, domain: personal}`, `items {node_id: Cam, content: "likes butterscotch ice cream", domain: personal}`.

A work query about Cam filters `domain = 'work'` and follows only work edges → the birthday row is never selected. A mixed/social query pulls both, tagged by domain, and the answer keeps them in separate sections.

---

## 5. Write pipeline (capture → filed knowledge)

```
[Speak / paste] -> [Live transcript, on-device] -> [User edits] -> [Tap PROCESS]
   -> enqueue capture (status=pending, timestamp)         <-- always local, works offline
   -> (when online, via WorkManager, in timestamp order):
        1. Candidate retrieval (local)
        2. Extraction call (DeepSeek V4 Flash, structured output)
        3. Entity resolution / dedup
        4. Apply to graph in a transaction + embed + index
        5. Hybrid confirmation if uncertain, else silent
   -> mark capture processed
```

**Step 1 — Candidate retrieval (local, no LLM).** Pull salient terms from the capture and run FTS5 + vector KNN over existing node names/summaries to get the top ~10–20 candidate nodes. This is the duplicate-prevention mechanism: instead of sending the whole graph, we send only nodes that could plausibly match.

**Step 2 — Extraction (DeepSeek V4 Flash, non-thinking).** Send a stable, cached system prompt + the capture text + the candidate node list (id, name, summary, domain). Require structured JSON output:

```jsonc
{
  "items": [
    {
      "content": "Confirm API scope with Cam for the XYZ topic",
      "kind": "task",
      "domain": "work",
      "target_node": { "match_id": "node_projX", "confidence": 0.96 },   // existing node
      "links": [ { "entity": "Cam", "relation": "colleague_on" } ]
    },
    {
      "content": "Birthday 18 Aug",
      "kind": "fact",
      "domain": "personal",
      "target_node": { "new": { "name": "Cam", "type": "person", "summary": "..." }, "confidence": 0.55 }
    }
  ]
}
```

A single capture (e.g. a meeting transcript) may return many items spanning several nodes — the schema supports that natively.

**Step 3 — Entity resolution.** For every proposed *new* node, re-check against candidates using normalized-name match + embedding cosine similarity. Strong match → attach to existing node. No match → create. Medium/ambiguous confidence → flag for confirmation (Step 5).

**Step 4 — Apply.** In one DB transaction: upsert nodes/edges/items, generate embeddings on-device for new/changed nodes and items, update the FTS5 index. Set node summaries from the model output.

**Step 5 — Hybrid confirmation.** If any decision was low/medium confidence (new-vs-existing person, ambiguous domain), show one compact confirmation ("New person 'Cam', or the existing Cam?"). Otherwise file silently. Always show a short "captured here" result.

**Offline behavior.** Captures accumulate with timestamps. On reconnect, WorkManager processes them oldest-first so earlier context informs later items.

---

## 6. Read pipeline (question → answer)

```
[Ask: voice/text]
   1. Classify intent + domain (V4 Flash, or local heuristic for obvious cases)
   2. Scoped retrieval (local): entity resolve -> hybrid search filtered by domain -> graph expansion (same-domain edges)
   3. Assemble compact context bundle (summaries + matched items only)
   4. Answer (V4 Flash thinking, or V4 Pro for hard cases)
   5. Render text answer + source-node chips
```

**Step 1 — Intent + domain.** Classify into intent (lookup / keyword / relational / semantic) and domain (work / personal / mixed), plus the entities mentioned. The user's own signals drive it: a project name or the word "work" → work; birthdays, preferences, social plans → personal. Cheap, fast Flash call.

**Step 2 — Scoped retrieval (the separation boundary).**
- Resolve mentioned entities to node ids (FTS5 + vector).
- Hybrid search (FTS5 + vector KNN) over nodes/items with `WHERE domain IN (<query domain>)`. For a work query, personal items are excluded at the database level.
- Graph expansion via recursive CTE from matched nodes, following only edges whose `domain` matches the query, 1–2 hops. Pull connected nodes' summaries and the specific relevant items.

```sql
-- Example: expand from a set of seed nodes, work edges only, up to 2 hops.
WITH RECURSIVE reach(node_id, depth) AS (
  SELECT id, 0 FROM nodes WHERE id IN (:seedIds)
  UNION
  SELECT e.target_node_id, r.depth + 1
  FROM edges e JOIN reach r ON e.source_node_id = r.node_id
  WHERE e.domain = :queryDomain AND r.depth < 2
)
SELECT * FROM reach;
```

**Step 3 — Context bundle.** Send only node summaries + the handful of matched items, not full node contents. Keeps the payload tiny (well under the 1M window, but small payloads = low latency + cost).

**Step 4 — Answer.** DeepSeek V4 Flash (thinking) by default; allow escalation to V4 Pro for complex relational questions. System prompt instructs: answer only from the provided context; cite the node ids used; for a mixed query, present work and personal in clearly separated, labelled sections; output text, letting the model choose the fitting format — paragraph, table, or short bullet list (e.g. a table/list for upcoming birthdays or per-project open items, prose for a narrative answer). No spoken read-back in v1.

**Step 5 — Render.** Show the answer with tappable source-node chips that open the node in the browse view.

---

## 7. Speech-to-text details

- Use `createOnDeviceSpeechRecognizer()` (API 33+) with `LANGUAGE_MODEL_FREE_FORM`. Audio stays on device.
- **Long-form (up to ~5 min) handling:** the standard recognizer is tuned for short utterances and stops on silence. For long dictation, accumulate partial + final results and auto-restart the recognition session on `onEndOfSpeech`/`onResults`, stitching segments in order, until the user taps stop. Surface the running text live (this is the "breathing orb + live transcript" moment from the functional spec).
- **Fallback / option:** Vosk (offline, open-source) gives robust continuous long-form transcription independent of the system recognizer, at the cost of bundling a model and slightly lower accuracy. Keep this behind an interface so it can be swapped in.
- Paste/typed input bypasses STT entirely and flows straight into the editable field.

---

## 8. DeepSeek V4 integration

- **Endpoint:** OpenAI-compatible REST (`https://api.deepseek.com`). Use Retrofit/Ktor + kotlinx.serialization.
- **Models:** use explicit ids `deepseek-v4-flash` and `deepseek-v4-pro`. (The legacy aliases `deepseek-chat` / `deepseek-reasoner` are slated for deprecation in mid-2026 — do not rely on them.)
- **Modes:** non-thinking for extraction/classification (fast, cheap); thinking for answering; reserve V4 Pro for hard reasoning. Thinking mode left on for trivial tasks is the main source of surprise cost — gate it.
- **Structured output / function calling:** use the structured-output (JSON schema) capability to force the extraction schema in §5. Validate the JSON before applying; on validation failure, retry once with a corrective instruction, then fall back to flagging the capture as `failed` for manual review.
- **Context caching:** keep the system-prompt prefix byte-for-byte identical across calls so repeated input bills at the cache-hit rate (~98% cheaper). Maintain one canonical extraction system prompt and one canonical answering system prompt.
- **Cost envelope:** with small retrieved payloads and V4 Flash input around $0.14/M (cache hits far less), realistic personal usage costs cents per month. Rate limits are not a concern at single-user, voice-paced volume. New accounts also receive free starter credits.
- **No hosted tools:** DeepSeek is text-only with no built-in web search or vision — all of which this app deliberately does not need.

---

## 9. Files, backup, and storage layout

- **Attachments:** chosen via the Storage Access Framework. On attach, the app **proposes** a destination: it runs the same candidate-retrieval + LLM step (over the filename, any description the user types, and the parsed text extract) to suggest a target node and a short description, then shows that proposal for confirmation. The user accepts, edits the description, reassigns to another node or a broader section, marks it as something new, or gives a free-text instruction — nothing is stored until confirmed (functional FR-6.2–6.3). Never automatic. Once confirmed, store the file under an app folder whose subfolder path mirrors the node's position in the tree, plus an `attachments` row and a parsed `text_extract` for retrieval.
- **Backup (FR-8):** export the entire database and attachments as a single **unencrypted, human-readable** archive (JSON for the graph + the raw files), written to the phone's **Downloads** folder via MediaStore. The user can open it and inspect the whole map. Restore reads the archive back into a fresh DB. (Encryption is explicitly deferred to a later phase.)
- **API key:** store in Android Keystore / EncryptedSharedPreferences.

---

## 10. Performance and footprint

- **App size:** base app + ONNX Runtime Mobile (a few MB) + the MiniLM embedding model (~80–90 MB). `model2vec` would be far smaller but its embeddings are non-contextual, so it was rejected — contextual recall quality is worth the size on this device.
- **Memory:** SQLite + vector search sit comfortably within the device's 8 GB; the vector extension targets a ~30 MB profile.
- **Latency:** local search/traversal is effectively instant at personal scale (thousands of nodes); perceived latency is the DeepSeek round-trip only, kept low by small payloads.
- **Target device:** Motorola Edge 50 Fusion (Snapdragon 7s Gen 2, 8 GB RAM) — a mid-tier chip. It runs all on-device components (STT, embeddings, vector search, SQLite) comfortably, because the only non-trivial compute (MiniLM embedding inference) happens in the **background during filing via WorkManager, never on the UI hot path**, so the slower-than-flagship CPU is not felt as lag. The int8-quantized MiniLM model (see §10 app size) is recommended here for both smaller size and faster inference on this class of chip.
- **On-device speech note:** `createOnDeviceSpeechRecognizer` works on this device (Google services present), but the offline speech-recognition model may require a one-time download (Settings → System → Languages → on-device speech). Handle the "recognizer unavailable" case gracefully on first run.

---

## 11. Risks and mitigations

| Risk | Mitigation |
|---|---|
| `SpeechRecognizer` cuts off long dictation | Segment + auto-restart and stitch; or Vosk fallback for true long-form |
| Network down during processing | Capture/transcribe/edit/queue are fully offline; WorkManager drains the backlog on reconnect, in timestamp order |
| Cloud privacy: capture text is sent to DeepSeek | Only the capture + minimal candidate/context slice leaves the device — never the whole map; be explicit with the user that processing requires sending the text. (Local-only LLM was ruled out.) |
| Duplicate / drifting nodes | Candidate retrieval + embedding-threshold entity resolution before any node creation; hybrid confirmation on ambiguity; manual merge in the UI |
| Malformed LLM JSON | Structured-output schema + validate; one corrective retry; else mark capture `failed` for review (nothing is silently lost) |
| Thinking-mode cost surprise | Default to non-thinking; enable thinking only for answering/hard cases |
| `.xlsx` parsing bloat | Lightweight reader; store only a text extract for retrieval |
| Embedding model change | Store `model_version` per vector; re-embed lazily when it changes |
| Single-phone data loss | Unencrypted backup/export to Downloads (FR-8); encourage periodic export |
| Backup is unencrypted | Accepted v1 trade-off (user wants to inspect it); encryption planned for a later phase |

---

## 12. Suggested build phasing

1. **Capture + filing core.** STT (on-device) + paste, editable review, Process, candidate retrieval, DeepSeek extraction with structured output, entity resolution, transactional write, on-device embedding + FTS indexing, basic browse of the node tree. Establishes the graph and the write path end to end.
2. **Ask + retrieval.** Intent/domain classification, domain-scoped hybrid retrieval + graph expansion, context-bundle assembly, answering with source chips, mixed-query separation.
3. **Attachments, resilience, backup, polish.** SAF attachments with mandatory context, WorkManager offline queue hardening, unencrypted backup/restore to Downloads, then the visual layer (Ink & Mist theme, breathing-orb capture moment, lightweight motion) per the functional spec's design direction.

---

## 13. Sources to verify before/while building (time-sensitive)

- DeepSeek official API & pricing docs — model ids (`deepseek-v4-flash`, `deepseek-v4-pro`), 1M context / 384K output, structured output & function calling, caching, alias deprecation timing.
- Android `SpeechRecognizer` / `createOnDeviceSpeechRecognizer` (API 33+) documentation; Vosk Android; ML Kit GenAI speech (alpha) if considering Gemini Nano on-device.
- ONNX Runtime Mobile for Android; sentence-transformers models (`bge-small-en-v1.5`, `all-MiniLM-L6-v2`), `model2vec`; reference Android embedding libraries.
- `sqlite-vec` (asg017) and `sqlite-vector` (sqliteai) — Android build/integration, vector formats, KNN syntax.
- AndroidX Room (FTS5 support, raw/recursive queries) and WorkManager guides.

Versions and exact parameter names move quickly; confirm against current docs at build time rather than hard-coding from memory.

---

## 14. Amendments

Decisions made after v1.0 was first agreed and built against. Additive — earlier text is preserved.

### Amendment T-A1 — 2026-06-20: Theme palette → "Lapis & Linen" (light)

**Companion to:** Functional Spec Amendment A1.

**Affected sections:** §3 (UI row in stack table) and any §6 / §11 mentions of "Ink & Mist".

**Implementation notes.**
- The `CortexTheme` composable now wraps a Material3 `lightColorScheme` (was `darkColorScheme`). The `InkMist` Kotlin object name is retained for code stability, but its token values are repointed:
  - `CanvasTop = #F6F2E9`, `CanvasMid = #F0EBDF`, `CanvasBottom = #ECE6D8` — vertical gradient, warm linen.
  - `PrimaryText = #15171C`, `SecondaryText = #5C6470` — ink + muted slate.
  - `Moonstone` (accent — name preserved, value re-pointed) = `#2657A1` — sapphire. A softer companion `MoonstoneSoft = #5E84BF` is added for the Ask-mode orb wash.
  - `HairlineGlass = #1A1D23 @ 12%` — ink hairline on light glass.
  - Domain hues: `DomainWork = #2657A1`, `DomainPersonal = #B5734A`.
- The `glassSurface(...)` modifier now paints `Color.White @ 58% alpha` + an ink hairline. On a paper canvas this reads as a brighter floating card, not a darker one.
- The `CanvasBackground` composable retains its subtle radial mist but uses a much fainter sapphire wash (`Moonstone @ 4%`) so the canvas feels warm rather than tinted.

**Things to verify when re-running.**
- Status-pill colours: success (sapphire) and failure (terracotta) must both have ≥ AA contrast on the linen canvas. Verified informally during the change; revisit if the canvas ever darkens.
- Source-chip and Node-row backgrounds switch from `Color.White @ 4%` (over dark) to `#15171C @ 4%` (over light) so they remain visible.
- The drawer sheet stops using a dark gradient and now uses the canvas gradient + ink text.

### Amendment T-A2 — 2026-06-20: Home-screen Ask voice path

**Companion to:** Functional Spec Amendment A2 (FR-1.1a–e).

**Affected sections:** §5 (write pipeline diagram — unchanged), §6 (read pipeline — see "Entry points" below).

**Behaviour.**
- The Capture screen exposes a *secondary* mic-icon button under the breathing orb labelled **"Ask anything"**. The orb is still the dominant tap target for capture.
- The single `SpeechManager` instance is shared between Capture and Ask voice paths. The screen tracks a local `voiceMode` flag set when the user taps the Ask mic, so when `SpeechState.Finished(text)` arrives we know whether to route the transcript into the Capture filing flow or into the Ask query flow.
- On `Finished` in Ask mode, the screen navigates to the Ask route with the transcript encoded as a query parameter. The Ask `ViewModel` consumes the parameter exactly once and triggers `ask()` automatically.
- During a voice Ask, the orb's gradient is tinted with `MoonstoneSoft` (paler sapphire) instead of full `Moonstone`, so the owner can tell at a glance which mode they are in. The breathing animation parameters are unchanged.

**Entry points to the read pipeline.** The `QueryService.ask(question)` contract from §6 is unchanged. What changes is that there are now *two* call-sites into it: (a) the existing Ask-screen text input, and (b) the new auto-fire on Ask-screen entry with a pre-filled question from the home-screen voice tap.

**Route shape.** Navigation Compose route becomes `ask?q={q}` with `q` declared as a nullable string argument. The `AskScreen` reads the argument via `SavedStateHandle`, consumes it once, then clears it from the back-stack entry to prevent re-firing on configuration change.

**Glitch fix.** The previous build wrapped the Capture-screen states in `AnimatedContent(targetState = speechState)`. Because `SpeechState.Listening` carries the live `rms` float, *every* RMS update re-keyed AnimatedContent and triggered a crossfade — the orb appeared to flicker on/off while the user was speaking. The fix replaces `AnimatedContent` with a plain `when` over the state class, so the orb subtree composes once per phase and re-uses the same `InfiniteTransition` instance throughout listening.

