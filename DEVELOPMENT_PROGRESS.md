# Cortex Development Progress & Handoff

This file acts as a persistent memory and handoff document for the development of the **Cortex** Android app. It tracks what has been completed, how it was implemented, and what the immediate next steps are. Any agent working on this repository must read this file to understand the current context and must update it before concluding their turn.

## 1. What is Done (Phase 1: Foundation & Capture Core)

We have successfully established the foundational Android architecture, the local database schema, and the skeleton of the extraction pipeline. The app currently successfully compiles to an APK.

**Completed Milestones:**
- **Environment & Build Configuration:**
  - Configured an Android Gradle project with `minSdk 33` and `targetSdk 34`.
  - Added dependencies: Jetpack Compose, Room, WorkManager, Retrofit, Kotlinx Serialization.
  - **Crucial Workaround:** The build initially failed with a `JdkImageTransform` error on Windows when using JDK 21. This was resolved by downloading and configuring **OpenJDK 17** for the build execution (`JAVA_HOME`).
- **Database Layer (Room):**
  - Created `AppDatabase.kt`, `Entities.kt`, and `Daos.kt`.
  - Configured the schema for `nodes`, `edges`, `items`, `captures`, and an `embeddings` table.
  - Implemented a `search_fts` lexical search mechanism using `LIKE` queries (FTS5 was initially attempted but caused KSP missing type errors due to a known Room-FTS bug, so we fell back to a stable standard `LIKE` search for Candidate Retrieval).
- **Capture UI & STT:**
  - Implemented `CaptureScreen.kt` featuring a dark "Ink & Mist" theme.
  - Integrated `SpeechManager.kt` using `createOnDeviceSpeechRecognizer()` for offline, on-device audio transcription.
- **DeepSeek Extraction Pipeline (Skeleton):**
  - Configured `DeepSeekClient.kt` via Retrofit to communicate with the DeepSeek V4 Flash API (`https://api.deepseek.com`).
  - Added a `CaptureProcessorWorker.kt` (WorkManager) designed to pick up queued transcriptions and process them when the network is available.

## 2. How it is Done (Technical Implementation Details)

- **Architecture:** MVVM with Jetpack Compose.
- **API Key Management:** The `DEEPSEEK_API_KEY` is loaded from a `local.properties` file during the Gradle build and injected into the app via `BuildConfig`.
- **Database Access:** All DB access happens through `CortexDao`. The entities define parent/child and lateral relationships representing the graph.
- **Extraction Logic:** The `CaptureProcessorWorker` is triggered. It will eventually pull the text, ask DeepSeek to extract structured JSON (Entities/Nodes/Items), and save them to Room.
- **Build Command:** To build the app, run `$env:JAVA_HOME="C:\Users\Lenovo\jdk-17"; $env:ANDROID_HOME="C:\Android\sdk"; .\gradlew assembleDebug`. (Do not use JDK 21 on Windows to build this specific project due to AGP `jlink.exe` bugs).

## 3. Next Steps (Phase 4: Polish, Attachments, Backup, Long-Form STT)

The read path is now live: Ask, Browse and Node Detail screens all render via the Ink & Mist theme and the read pipeline (classify → scoped retrieve → graph expansion → DeepSeek answer with source-node ids). The remaining gaps are the confirmation-UI ergonomics, attachments, backup, and visual/motion polish.

**Immediate Next Tasks (To be picked up by the next agent):**
1. **Hybrid confirmation UI (FR-2.6).** `ExtractionApplier` already counts medium-confidence resolutions in `confirmationsNeeded` and the field is plumbed through. Surface them as a notification chip on the Capture screen ("1 item needs a quick confirmation") that opens an inbox of compact "New person 'Cam', or the existing Cam?" prompts, then call into a new merge DAO method.
2. **Bundle the real MiniLM ONNX model.** Drop `model.onnx` (int8-quantized `all-MiniLM-L6-v2`) and `vocab.txt` into `app/src/main/assets/embeddings/`. Settings already shows whether the contextual model or the fallback is in use; the service will hot-swap automatically.
3. **File attachments (FR-6, Tech Spec §9).** SAF picker for `.txt`/`.csv`/`.xlsx`. Parse to a text extract (fastexcel for xlsx). MUST propose a destination node + description first, owner confirms — never silent. Mirror the node tree on disk under app folder.
4. **Backup and restore (FR-8).** Serialize the entire graph + attachments to a single human-readable JSON archive written to Downloads via MediaStore; restore reads it back into a fresh DB.
5. **Long-form STT auto-restart (Tech Spec §7).** Current recognizer stops on first silence; for 5-minute dictation, accumulate partial + final results and restart on `onEndOfSpeech` until the user taps stop. Surface the running text live.
6. **Map curation (FR-9).** Rename, edit summary, move under different parent, merge duplicate nodes, delete, re-assign domain. Without these the map can't self-correct over time.
7. **Visual polish.** Real Geist Sans / Fraunces / Geist Mono via downloadable fonts; gentle cross-fades on screen transitions; respect "reduce motion"; pulled the warm off-white correctly throughout. Switch model id from `deepseek-chat` to `deepseek-v4-flash` once verified live.
8. **Quiet the Room FK index warnings.** Add `@Entity(indices = [...])` to `NodeEntity`, `EdgeEntity`, `ItemEntity` for the foreign-key columns flagged by KSP.

## 3a. Earlier Next Steps (Phase 3 — completed)

The full write pipeline (capture → candidate retrieval → DeepSeek extraction → entity resolution → transactional graph write) is now in place and the APK builds. The next phase opens the read path and completes the ergonomic loose ends.

**Immediate Next Tasks (To be picked up by the next agent):**
1. **Bundle the real MiniLM ONNX model.** `EmbeddingService` already wires up ONNX Runtime + a WordPiece tokenizer, but ships in *fallback* mode (deterministic hashed bag) when assets are missing. Drop `model.onnx` (int8-quantized `all-MiniLM-L6-v2`) and `vocab.txt` into `app/src/main/assets/embeddings/` and re-run to switch to the real, contextual embeddings. No code changes required — `EmbeddingService.modelVersion` will flip from `fallback-hash-v1` to `minilm-l6-v2-q-onnx` automatically.
2. **Read pipeline (Tech Spec §6).** Implement intent + domain classification, domain-scoped hybrid retrieval (LIKE + vector KNN), recursive-CTE graph expansion limited to same-domain edges, and a DeepSeek answering call that cites source-node ids. Build an "Ask" screen that renders the answer with tappable source chips.
3. **Hybrid confirmation UI.** `ExtractionApplier` already counts `confirmationsNeeded` cases (medium-confidence entity matches). Surface them as an inbox of compact "New person 'Cam', or the existing Cam?" prompts so the user can merge before the data ossifies.
4. **Browse the map.** Drill-in node screen showing name, summary, items, lateral links and (later) attached files, per FR-5.
5. **Migrate to `deepseek-v4-flash`.** Tech Spec §8 calls for the explicit id; we currently still send `deepseek-chat`. Switch once we've confirmed the new id is live on the account.
6. **Quiet the Room FK index warnings.** KSP currently warns about missing indices on `parent_id`, `source_node_id`, `target_node_id`, `node_id`, `related_node_id`. Add `@Entity(indices = [...])` declarations to avoid full-table scans as the graph grows.

## 4. Chronological Log of Work Completed

*(Agents: Append your session summaries below this line. Do NOT overwrite historical entries.)*

**Session 1: Project Scaffolding & Capture Core (Phase 1)**
- Configured Android Gradle project (`minSdk 33`, `targetSdk 34`) with Compose, Room, WorkManager, Retrofit.
- **Workaround applied:** Bypassed Windows JDK 21 `JdkImageTransform` AGP bug by explicitly downloading and setting OpenJDK 17 for `JAVA_HOME` during builds.
- Created local SQLite schema (`nodes`, `edges`, `items`, `captures`, `embeddings`) via Room.
- Switched from `FTS5` to standard `LIKE` queries for Candidate Retrieval to bypass a persistent Room/KSP code generation bug with virtual tables.
- Built `CaptureScreen.kt` UI ("Ink & Mist" theme) and wired `SpeechManager.kt` for offline on-device transcription.
- Scaffolded `DeepSeekClient.kt` and `CaptureProcessorWorker.kt` to prepare for Phase 2 extraction.
- Successfully built and installed APK on the target device via ADB.

**Session 2: Phase 2 — Entity Resolution & Graph Writing**
- **Candidate retrieval (Tech Spec §5 Step 1):** Added `pipeline/CandidateRetrieval.kt`. Pulls salient terms from the capture (capitalized phrases + significant lowercase words, minus a stopword list), queries `nodes` via case-insensitive `LIKE` per term, and pads with the most-recently-updated nodes so the LLM always sees usable context even on a near-empty graph.
- **DeepSeek extraction schema (Tech Spec §5 Step 2):**
  - Added `api/ExtractionSchema.kt` modelling the required JSON: `items[*].{content, kind, domain, status, target_node{match_id|new, confidence}, links[]}`.
  - Added `api/ExtractionPrompt.kt` with a stable, byte-identical SYSTEM_PROMPT (so DeepSeek's prompt cache hits — ~98% cheaper per §8) and a `buildUserMessage` that serializes the candidate set + capture text.
  - Rewrote `api/DeepSeekClient.kt` to use the canonical `response_format = json_object`, a 120s read timeout, and HTTP logging.
- **Entity resolver + embeddings (Tech Spec §5 Step 3, §3, §10):**
  - Added `pipeline/EmbeddingService.kt` — ONNX Runtime Mobile wrapper around `all-MiniLM-L6-v2` with a minimal WordPiece tokenizer. Loads `assets/embeddings/{model.onnx, vocab.txt}` if present; otherwise falls back to a deterministic hashed-bag embedding so the pipeline runs end-to-end without bundled weights. `modelVersion` is persisted with every vector so a future model swap can trigger lazy re-embedding (per the §11 risk).
  - Added `pipeline/EntityResolver.kt` — normalized-name match first, then cosine similarity over name + summary embeddings, with `strong` (≥0.85) / `medium` (≥0.65) / `none` outcomes. Medium matches are tagged `needsConfirmation` for the (still to come) confirmation UI.
- **Transactional graph writes (Tech Spec §5 Step 4):**
  - Extended `data/Daos.kt` with batch inserts, `getRecentNodes`, `getEmbedding(s)`, and a single `@Transaction applyExtraction(...)` method that writes nodes/edges/items/embeddings and flips the capture to `processed` atomically.
  - Added `pipeline/ExtractionApplier.kt` to translate the LLM `ExtractionResult` into Room entities, embed every new node and item on-device, deduplicate references to the same entity within a capture, and fall back to a lazily-created `Inbox` topic when the model returns neither a `match_id` nor a `new` node.
- **Worker rewire:** `pipeline/CaptureProcessorWorker.kt` now orchestrates the full pipeline (retrieve → extract → resolve → write), validates the JSON, performs one corrective retry on parse failure (matching Tech Spec §11's malformed-LLM mitigation), and marks unrecoverable captures `failed` instead of looping. `MainActivity` was updated to enqueue the worker as a unique chain with `NetworkType.CONNECTED` so the queue drains in order on reconnect (FR-7.3).
- **Build:** `assembleDebug` is green. Resolved two Kotlin/ONNX compile snags along the way — `OrtSession.Result` is iterated via `result.iterator().next()` rather than `result[0]`, and FloatArray averaging is done with an explicit `Float` divisor (`/= Int` does not have an `Array.set` operator overload in Kotlin).
- **Known limitations handed to the next agent:**
  - The MiniLM ONNX file is not yet bundled — embeddings run in fallback mode until `assets/embeddings/` is populated.
  - `confirmationsNeeded` is counted and logged but not yet surfaced in the UI (no merge prompt yet).
  - Model id is still `deepseek-chat`; switch to `deepseek-v4-flash` when ready.
  - Room emits FK index warnings on `nodes/edges/items` (non-fatal; documented above).

**Session 3: Phase 3 — Read Pipeline + Ink & Mist UI**
- **Compose Navigation added.** `androidx.navigation:navigation-compose 2.7.7` + lifecycle viewmodel/runtime compose; `material-icons-extended` for the menu/icon set.
- **Ink & Mist theme system (`ui/theme/`):**
  - `Theme.kt` — `CortexTheme` wraps Material3 with a dark `InkMistColorScheme` (warm off-white text, moonstone accent, `#15171C → #1B1E26` vertical canvas gradient).
  - `CanvasBackground` composable that paints the calm canvas behind every screen.
  - `glassSurface` modifier — translucent white-6% fill + 1dp hairline border at 12% white, used on capture review surfaces, the menu sheet, answer cards and the search field. Compose doesn't expose a runtime background blur so we approximate; it still reads as a floating interactive surface (FR §8.5).
  - `Type.kt` — humanist sans / low-contrast serif / mono families per FR §8.4, mapped to system families until Geist Sans / Fraunces / Geist Mono are bundled.
  - `domainColor()` + `DomainDot`/`DomainChip`/`Hairline` atoms so work/personal hue carries meaning in small doses (FR §8.3).
- **App shell (`ui/AppShell.kt`).** `ModalNavigationDrawer` opened by a hamburger icon on every screen, routing to Capture / Ask / Browse / Settings. Drawer is painted on the canvas gradient with moonstone-tinted selection chips.
- **Capture screen rework (`capture/CaptureScreen.kt`).** Now uses theme tokens (no hardcoded charcoal), gains a soft radial-gradient halo around the breathing orb so it reads as glowing rather than flat, and the orb diameter grew to 120dp (220dp halo). Review state uses the warm primary text colour and the Process button takes the moonstone accent.
- **Read pipeline (`pipeline/QueryService.kt` + `api/QuerySchema.kt` + `api/AnswerPrompts.kt`).**
  - **Classify** (DeepSeek JSON-only): intent ∈ {lookup, keyword, relational, semantic} and domain ∈ {work, personal, mixed}, plus extracted entities. Stable system prompt for cache hits. Falls back to a local keyword heuristic if the API call fails so the app degrades gracefully offline.
  - **Scoped retrieve**: entity-by-entity LIKE search via the new `searchNodesScoped(query, domain, limit)` DAO method; salient-term passes from `CandidateRetrieval`; vector-cosine top-up against the most recently updated nodes; and `searchItemsScoped` to surface parent nodes whose *items* mention the term.
  - **Graph expansion**: new `expandOneHop(seedIds, domain)` DAO method that JOINs through `edges` filtered by domain. Items per node are also scoped by domain (mixed → no filter), so a work query can never accidentally pull personal items.
  - **Answer**: DeepSeek answering prompt mandates ground-truth-only answers, a trailing `SOURCES: id1, id2, ...` line that the UI parses into tappable source chips, and clearly-labelled Work/Personal sections for mixed queries (FR-3.6).
- **Ask screen (`ui/screens/AskScreen.kt` + `AskViewModel.kt`).** Glass input bar with a moonstone send button, italicised echo of the question, scrollable answer body, intent/domain chip, and a list of source chips that route into Node Detail. Idle state shows three example questions per the functional spec.
- **Browse screen (`ui/screens/BrowseScreen.kt`).** Glass search input + lazy list of root nodes. Search runs the scoped LIKE over the whole graph. `getRootNodesFlow()` powers reactive updates as captures land.
- **Node detail (`ui/screens/NodeDetailScreen.kt`).** Loads node, children, items (each rendered on its own glass card with kind/status metadata + domain dot), and lateral links with relation type + domain. Linked rows and sub-nodes route deeper. Pulls only via the new DAO helpers, no leakage of cross-domain items when callers pass a domain.
- **Settings (`ui/screens/SettingsScreen.kt`).** Read-only inspector: shows whether the DeepSeek key is configured, which embedding model is live (real MiniLM vs hashed fallback), and the local DB name. Backup/restore is wired as "coming soon" — implementation lands in Phase 4.
- **Wiring & build.** `MainActivity` now mounts `CortexTheme { AppShell(...) }` and extracts capture enqueueing into a private function. `assembleDebug` is green (two warnings only: `Icons.Rounded.ArrowBack` deprecation in NodeDetail, and a `kotlinx.serialization` `explicitNulls` opt-in in AnswerPrompts — both safe to silence later). APK pushed to the device via `adb install -r` and launched.
- **Known limitations handed to the next agent:**
  - Compose has no production-quality runtime blur, so glass surfaces are approximated. If we want true frosted glass we'd need RenderEffect on API 31+ (we already require API 33).
  - The Browse search is a single global LIKE; once vector recall is real we should fuse FTS + vector here too.
  - The answering prompt asks for `SOURCES:` at the very end; if the model puts it elsewhere the chip list will be empty (the source seed nodes still surface as a fallback).
  - DeepSeek model id is still `deepseek-chat`; Tech Spec §8 wants the explicit `deepseek-v4-flash`.

**Session 4: Spec amendments + light "Lapis & Linen" theme + Ask-from-home**
- **Spec amendments (appended, not erased).** Both `Functional_Specification_Personal_Mind_Map.md` and `Technical_Specification_Personal_Mind_Map.md` gained an "Amendments" section at the bottom. Amendment A1 supersedes the dark "Ink & Mist" palette with a light "Lapis & Linen" palette; Amendment A2 adds a secondary Ask action to the home screen. Original §8 / FR-1.1 text was kept verbatim with "(superseded)" markers so the audit trail stays intact.
- **Theme — Lapis & Linen.** Repointed every token in `InkMist`: linen canvas (`#F6F2E9 → #ECE6D8`), deep ink primary text (`#15171C`), muted slate secondary (`#5C6470`), sapphire accent (`#2657A1`) with a paler companion `MoonstoneSoft` (`#5E84BF`) for the Ask voice mode. `glassSurface` now paints translucent white on linen with an ink hairline so the cards read as *brighter* floating surfaces. `CortexTheme` switched from `darkColorScheme` to `lightColorScheme`. Added `SoftFill` / `SoftFillStrong` tokens (6% / 12% ink) and replaced every `Color.White.copy(alpha = ...)` row/card background across Ask, Browse, NodeDetail, Capture so they remain visible on the new canvas.
- **Orb glitch fix.** Removed `AnimatedContent(targetState = speechState)` from `CaptureScreen` — because `SpeechState.Listening` carries the live `rms`, the previous build re-keyed AnimatedContent on every RMS update and continuously crossfaded between the same Listening branch, making the orb appear to flash on and off. Replaced with a plain `when` so the orb's `InfiniteTransition` is created once per phase and stays continuous throughout listening. Also tightened RMS-driven scale (cap 0.30 instead of 0.40) and stretched the breath cycle so the size pulse reads as breathing rather than wobble.
- **Orb visual rework.** Single jewel-core radial gradient: white-hot crown highlight on top, sapphire body below, hairline sapphire border. Surrounding mid-wash + outer halo widened to 280dp. Looks luminous on linen instead of flat.
- **Ask from the home screen (FR-1.1a–e / Amendment T-A2).**
  - Added a secondary `Ask anything` pill button under the orb with a mic glyph in a sapphire well.
  - Capture screen now tracks a local `VoiceMode` (CAPTURE / ASK). When the user taps the Ask button we set `voiceMode = ASK` then start the recogniser; on `SpeechState.Finished` we route the transcript into the Ask flow instead of the review/file flow.
  - During Ask voice input the orb tints with `MoonstoneSoft` (paler sapphire) and the listening title reads "Listening — your question", so the owner can tell at a glance which mode they're in.
  - Navigation Compose route reshaped: old `ask` → new `ask?q={q}` with an optional nullable string argument. `AskScreen` gained `initialQuestion` + `onConsumedInitialQuestion` parameters and auto-fires `vm.ask(...)` once on first composition, then clears the arg from the back-stack entry so it doesn't refire on configuration change.
  - `Routes.ask(question)` helper URL-encodes the transcript.
- **APK size.** Phone storage was wedged at 100% on every install attempt. Added `ndk { abiFilters += listOf("arm64-v8a") }` to the debug `defaultConfig`. APK dropped from 85 MB → 69 MB by stripping the unused armeabi-v7a / x86 / x86_64 native libs ONNX Runtime ships.
- **Known limitations handed to the next agent:**
  - Drawer sheet still uses the canvas gradient — looks fine, but a tighter glass treatment with a soft shadow would be more premium. Skipped to keep this change focused.
  - The breathing orb still doesn't have a true blur halo (Compose doesn't expose runtime background blur portably); RenderEffect on API 31+ could add real Gaussian glow.
  - `MoonstoneSoft` is only used on the Ask voice path so far; consider using it for hover/secondary states elsewhere.
  - DeepSeek model id is still `deepseek-chat`; Tech Spec §8 wants explicit `deepseek-v4-flash`.
  - The ASK_PATTERN route still keys on `ask?q={q}`; drawer "Ask" navigation reuses `Routes.ASK` (no query). NavigationDrawerItem's `selected` check now strips '?' to handle both.

> **Handoff Note:** Before executing further code changes, read the `Functional_Specification_Personal_Mind_Map.md` and `Technical_Specification_Personal_Mind_Map.md` located in the root of the project to understand the broader context, **including the Amendments sections at the end of each — those override earlier requirements where they conflict.**
