# Personal Mind Map — Functional Specification

**Version:** 1.0 — Final (functional)
**Platform:** Android (single-user, local-first)
**Status:** For review and alignment before technical design

---

## 1. How to read this document

This is the *functional* specification: what the app is for, what it must do, and how it should look and feel. It deliberately avoids low-level technical decisions (database design, retrieval algorithms, API wiring) — those live in the separate Technical Specification. Where a functional requirement implies a technical choice, it is described from the user's side of the screen, not the system's.

Requirements are numbered (FR-x) so they can be referenced unambiguously during build and review.

---

## 2. Product vision

A private, voice-first "second brain" that holds everything going on in one person's life — projects, work threads, people, hobbies, stray ideas worth keeping — and lets them get it back later by simply asking. The owner speaks a thought; the app understands it, files it in the right place in a self-organising map of their life, and can answer questions about any of it later. Critically, it keeps work and personal context cleanly separated, so a question about a project never gets answered with someone's birthday, and a question about a friend never drags in a work ticket — unless the owner genuinely wants both.

It runs on the owner's phone. Their data stays on the device. The only thing that leaves is the minimal slice of context needed for the language model to think.

---

## 3. Goals and non-goals

### Goals
- Capture thoughts by voice with near-zero friction.
- Never lose track of anything across many parallel projects and life threads.
- Organise information automatically, without forcing the owner to pick categories up front.
- Answer questions accurately, pulling from the right context and nothing irrelevant.
- Keep work and personal context separate by default.
- Run locally, lightweight and fast, on the owner's own phone.
- Feel like a calm, premium, considered tool — not a generic AI app.

### Non-goals (for v1)
- Multi-user, sharing, or collaboration.
- Always-on background listening.
- Rich media (images, audio, video) as attachments — text and spreadsheet files only.
- Cloud sync across devices.
- A public release or app-store distribution. This is a personal tool.

---

## 4. The user

A single owner — anyone who wants one private place to keep track of everything across their work and personal life. The app makes no assumptions about profession; nothing in it is specific to one kind of job. The need it answers is a common one: many parallel threads (projects, people, ideas, plans) that are easy to lose track of, plus frustration with general assistants that blur work and personal context together. The owner values privacy, control, speed, and restraint in design.

---

## 5. Core concepts (in plain language)

**The map.** The owner's information is held as a connected map of *nodes*. A node is a meaningful "thing" — a project, a person, a hobby, a topic, an idea. Nodes can contain sub-nodes (a project contains its sub-topics; a sub-topic contains specific items), so the map reads like a tree you can drill into, with some sideways connections where things genuinely relate.

**Node identity.** Every node has a short **name** and an auto-generated **two-line summary**. These let the app find the right node quickly without reading everything inside it.

**Domains.** Every piece of information carries a domain — most often **work** or **personal** — assigned automatically when it's filed. Domains are how the app keeps context from bleeding across.

**Entities and links.** A person, like "Cam," is a single entity in the map even if they appear in many places. Cam can be linked to two different projects *and* hold personal facts (birthday, what they like) at the same time. The links themselves carry the domain — so Cam's work links and Cam's personal facts are stored together but tagged differently, and the app only ever follows the links relevant to the question being asked.

**Attachments.** Text and spreadsheet files can be attached to a node, living alongside the information they belong to.

---

## 6. Functional requirements

### FR-1 — Capture (voice and text)

- **FR-1.1** The home screen presents a single primary action to start speaking ("tap to speak"). Recording is always manual; the app never listens in the background.
- **FR-1.2** While the owner is speaking, the app transcribes their speech to editable text in real time. **It does not process, file, or send anything yet.** Transcription only.
- **FR-1.3** The owner taps again to stop. The app then shows the full transcript in an **editable** text field.
- **FR-1.4** The owner can freely edit the transcript to correct any mis-transcription before anything is processed.
- **FR-1.5** A distinct, deliberate **Process** action commits the (corrected) text for understanding and filing. Nothing is filed until Process is tapped.
- **FR-1.6** The owner can discard the transcript or re-record instead of processing.
- **FR-1.7** Transcription uses the device's on-device speech recognition where available (local, no audio leaves the phone).
- **FR-1.8** As an alternative to speaking, the owner can **type or paste text** into the same capture field — for example, a meeting transcript or notes copied from elsewhere. Pasted/typed text goes through the same editable-review → **Process** flow as a transcript; nothing is filed until Process is tapped.
- **FR-1.9** Comfortable input limits per single capture:
  - **Spoken:** up to about 5 minutes — roughly 700 words at ~140 words per minute. This covers a long, uninterrupted train of thought.
  - **Typed / pasted:** comfortably up to about **5,000 words (~30,000 characters)** — roughly a 30–40 minute meeting transcript. This stays well within the language model's context window, so it adds no technical strain.
  - Genuinely large inputs (multi-hour transcripts, whole documents) are not forced through one capture: the app suggests splitting them, or the owner attaches them as a file instead. This keeps capture comfortable rather than extreme.

### FR-2 — Understanding and filing

- **FR-2.1** On Process, the app extracts the meaningful content from the text: what it's about, which people/projects/topics it concerns, any tasks or facts, and the domain (work / personal).
- **FR-2.2** A single input can produce **multiple** filed items across several nodes. A meeting transcript, for instance, may yield decisions, action items, and facts about different people and projects all at once — the app decomposes one long input into all the distinct items it contains.
- **FR-2.3** The app decides where the information belongs in the map, creating new nodes only when no suitable existing node is found, and placing items under the correct parent.
- **FR-2.4** Before creating any new node for a person, project, or topic, the app checks for an existing match to avoid duplicates (e.g. "Ramesh," "my friend Ramesh," and "Ramesh from college" must resolve to one node).
- **FR-2.5** When a new node is created, the app generates its name and two-line summary automatically.
- **FR-2.6** **Filing behaviour is hybrid:** the app files silently when it is confident about placement and entity matches, and asks for a single, quick confirmation only when it is unsure (e.g. "New person 'Cam', or the existing Cam?"). The owner is never nagged for obvious entries.
- **FR-2.7** After filing, the app briefly confirms what was captured and where it went, so the owner can trust it landed correctly.

### FR-3 — Context separation (work vs personal)

- **FR-3.1** Information is tagged with a domain when filed. The same entity (e.g. Cam) can hold both work links and personal facts simultaneously; the *links*, not the entity, carry the domain.
- **FR-3.2** Every question is interpreted for intent and domain before the app retrieves anything: **work**, **personal**, or **mixed/social**.
- **FR-3.3** The owner's natural signals drive this: naming a project or saying "work" marks a query as work; asking about birthdays, preferences, or social plans marks it personal.
- **FR-3.4** For a work query, the app retrieves only work-domain context. Personal facts about the same people are never loaded. (Asking "what was the discussion with Cam on the XYZ topic?" must not surface Cam's birthday.)
- **FR-3.5** For a personal query, only personal context is retrieved.
- **FR-3.6** For a genuinely mixed/social query (e.g. "I'm meeting Cam, what should I keep in mind?"), the app may surface both domains, but keeps them **visually and structurally separated** in the answer rather than blended, or offers a one-tap option to include work items.
- **FR-3.7** When intent is ambiguous, the app defaults to the signalled or most likely domain and offers a quick way to widen scope, rather than guessing and mixing.

### FR-4 — Asking questions (retrieval and answers)

- **FR-4.1** The owner can ask questions in natural language, by voice or text.
- **FR-4.2** The app supports: direct lookup by name ("show me Project X"), keyword search within node summaries and content, semantic/fuzzy recall (finding the right thing even when phrased differently from how it was stored), and relational questions ("how is Cam connected to my projects?").
- **FR-4.3** Retrieval is scoped by the query's domain (FR-3) and pulls only the relevant slice of the map — the matching nodes plus their directly connected nodes — not the whole map.
- **FR-4.4** Answers should make it clear which parts of the map they came from, so the owner can trust and verify them and jump to the source node.
- **FR-4.5** Answers are **text only** in v1 (no spoken read-back). The format is chosen by the language model to fit the question — a paragraph, a table, or a short bulleted list (for example, a table or list for upcoming birthdays or per-project open items, prose for a narrative answer). No fixed template is imposed.
- **FR-4.6** Example queries the app must handle well:
  - "For Project X, what do I still need to confirm with Cam?"
  - "Whose birthdays are coming up this month?"
  - "I'm going to meet Ramesh — what should I keep in mind?"
  - "That cool thing I read about [topic] a while back — what was it?"

### FR-5 — Browsing and navigation

- **FR-5.1** A menu (hamburger icon, top corner) opens navigation to browse the map and files without cluttering the capture screen.
- **FR-5.2** The map is browsable as a drill-in tree: tap a node to open it, see its summary, its sub-nodes, its items, its links to other nodes, and its attached files.
- **FR-5.3** From any node, the owner can navigate to connected nodes (e.g. from Cam to Project X).
- **FR-5.4** Search is available from the browse view.

### FR-6 — File attachments

- **FR-6.1** The owner can attach files to a node. Supported types: plain text documents and spreadsheets (e.g. .txt, .csv, .xlsx). No other media in v1.
- **FR-6.2** A file is **never** filed automatically or silently. On upload, the app **proposes** a destination — a suggested target node plus a short description of what the file is — because the owner may not remember every node in the map.
- **FR-6.3** The owner then confirms or adjusts the proposal: save as suggested, edit the description, move it under a different node, place it in a broader section rather than a named node, mark it as something new, or give a short instruction in their own words. Nothing is stored until the owner confirms.
- **FR-6.4** Files are stored attached to the chosen destination, and the on-device folder structure mirrors the node structure (folders and sub-folders matching nodes and sub-nodes).
- **FR-6.5** Attached files are retrievable when browsing their node, and their existence/relevance can inform answers about that node.

### FR-7 — Offline capture

- **FR-7.1** Capture works with no network. Transcription (on-device) and the editable review step function offline.
- **FR-7.2** If there is no network at Process time, the corrected text is queued locally with a timestamp; nothing is lost.
- **FR-7.3** When the network returns, the app processes the queued backlog **in timestamp order**, so context is built up in the sequence it was spoken.
- **FR-7.4** The owner can see what is pending and what has been processed.

### FR-8 — Backup and durability

- **FR-8.1** Because the map lives only on the device, the owner can export a full backup of the entire map and attachments.
- **FR-8.2** The backup is saved to the phone's **Downloads** folder.
- **FR-8.3** The backup is **unencrypted** in v1, by deliberate choice: the owner should be able to open and inspect their complete mind map directly, in a human-readable form. (Encryption is deferred to a later phase.)
- **FR-8.4** The owner can restore from a backup onto the same or a new device.
- **FR-8.5** (This addresses the single-point-of-failure risk of a local-only second brain: a lost or wiped phone must not mean losing everything.)

### FR-9 — Correcting and curating the map

- **FR-9.1** The owner can rename a node, edit its summary, move it under a different parent, merge two nodes that turned out to be duplicates, and delete nodes or items.
- **FR-9.2** The owner can re-assign the domain of a node or link if the app filed something under the wrong context.
- **FR-9.3** These corrections keep the map clean over time and let the owner override the app's automatic decisions.

---

## 7. Key user flows

**Capture a thought**
1. Tap to speak.
2. Speak; watch the transcript appear live.
3. Tap to stop.
4. Read the transcript; edit any mis-heard words.
5. Tap Process.
6. The app files it (silently, or with one quick confirmation if unsure) and shows a short "captured here" confirmation.

**Ask a question**
1. Ask by voice or text.
2. The app interprets intent and domain.
3. It retrieves the relevant slice of the map.
4. It answers, showing which nodes the answer came from.
5. The owner can tap through to any source node.

**Browse the map**
1. Open the menu.
2. Drill into a node; read its summary, items, links, and files.
3. Navigate sideways to connected nodes, or search.

**Attach a file**
1. Open the relevant node (or attach while capturing about it).
2. Add a text or spreadsheet file.
3. The file is stored with the node; the folder tree mirrors the map.

---

## 8. UI / UX design direction

The owner has one firm requirement: it must feel like an expensive, premium, calm app — and must **not** look like generic AI software. This section defines that concretely so the build doesn't drift into defaults.

### 8.1 What "not AI slop" means here (things to avoid)
- No purple→blue or rainbow gradients; no neon or acid accent colours.
- No sparkle/star/robot emojis as decoration.
- No wall of identical rounded chat bubbles.
- No stacks of heavy drop-shadowed cards.
- No generic component-library default look (uniform radii, default greys, stock icons everywhere).
- No clutter: every element must earn its place.

### 8.2 The feeling we're going for
A quiet, private study at dusk. Depth and calm, generous negative space, soft light, and a single point of focus at a time. The app should feel like a considered instrument for thinking, not a busy productivity dashboard. Restraint is the whole aesthetic: we spend boldness in exactly one place (the capture moment) and keep everything else disciplined and quiet.

### 8.3 Colour system (primary theme: "Ink & Mist", dark)
A deep, desaturated base lets frosted-glass surfaces and a single soft glow do the work.

- **Canvas** `#15171C` → `#1B1E26` (very subtle top-to-bottom gradient for depth)
- **Glass surface** white at ~6–10% opacity over the canvas, with background blur and a 1px hairline border at ~12% white
- **Primary text** `#ECEAE4` (a *warm* off-white — avoids a clinical feel)
- **Secondary text** `#8A8F99`
- **Accent / active state** `#7FB3B0` (calm luminous moonstone — used sparingly, mainly for the listening state and the primary action)

**Domain hues** (colour carries meaning — this reinforces the core feature):
- **Work** `#6E8CB0` (cool slate-blue)
- **Personal** `#C29A7A` (warm muted clay)

These domain hues appear only in small doses — a node's tag, a link accent, a dot beside an item — never as large fills. Seeing a thread of warm vs cool tells the owner at a glance whether they're looking at work or personal context, which is the whole philosophy of the app expressed in colour.

v1 ships this single dark Ink & Mist theme as the final, definitive look — there is no separate light theme. (A light theme may be considered in a later phase; if ever added, it should be a cool misty light, not the over-used warm cream.)

### 8.4 Typography
- **Body & UI:** a clean, modern humanist sans (e.g. Geist Sans or Hanken Grotesk) — *not* the default system font, set with generous line height and calm tracking.
- **Display / app wordmark / answer headlines:** a soft, low-contrast optical serif (e.g. Fraunces), used *sparingly*. This is the one deliberate aesthetic risk: a touch of warm, human serif against the cool ink gives a "second brain" some soul, rather than reading as a cold utility. If it ever feels decorative rather than characterful, drop back to the sans.
- **Timestamps / metadata / small data:** a mono face (e.g. Geist Mono) at small sizes for a precise, instrument-like touch.

### 8.5 Glassmorphism (used with restraint)
Frosted glass appears only on layered surfaces — the bottom capture bar, the slide-in menu sheet, and answer cards — sitting over the calm canvas with real background blur and a hairline border. It is *not* applied to everything; over-used glass becomes noise. Glass signals "this is a floating, interactive surface."

### 8.6 Motion and animation (lightweight, purposeful)
- Spring-based, gentle, short (≈150–280ms), ease-out. No bounce gimmicks, no confetti, no decorative loaders.
- Content fades and rises softly as it appears.
- State changes (capture → review → process → filed) cross-fade calmly rather than cutting.
- Respect the system "reduce motion" setting.

### 8.7 The signature element
**The breathing capture orb.** When the owner taps to speak, a single calm luminous orb (the moonstone accent) gently breathes/pulses in the centre, and the live transcript settles in softly beneath it. This is the one memorable, premium moment of the app — the point where a spoken thought becomes something the app holds. Everything else stays quiet so this moment lands.

### 8.8 Layout, spacing, iconography
- One focus per screen; lots of breathing room. An 8pt spacing grid for consistency.
- A single, consistent, fine-lined icon set (not mixed styles).
- Empty states are invitations to act ("Tap to capture your first thought"), written in the app's own calm voice — never apologetic or filler.
- Copy throughout uses plain, active voice and consistent vocabulary (the action that says "Process" produces a result that says "Captured").

---

## 9. Screens (overview)

- **Home / Capture** — the canvas, the breathing orb, the tap-to-speak action. The default screen.
- **Review & Edit** — the editable transcript with Process / Re-record / Discard.
- **Answer / Ask** — question input and the answer, with visible source nodes and (for mixed queries) clearly separated work vs personal sections.
- **Menu** — slide-in glass sheet; routes to Browse, Files, Settings.
- **Browse (map tree)** — drill-in node navigation with search and sideways links.
- **Node detail** — name, summary, items, domain tags, links, attached files, and edit/move/merge controls.
- **Files** — node-mirrored folder view.
- **Settings** — backup/export & restore, speech settings.

---

## 10. Out of scope for v1
Multi-device sync, sharing/collaboration, always-on listening, non-text/spreadsheet attachments, public distribution, and any cloud storage of the map.

---

## 11. Resolved decisions for v1
- **Theme:** the single dark "Ink & Mist" theme is final for v1; no light theme.
- **Answer output:** text only; the language model picks the format that fits — paragraph, table, or short bullet list. No voice read-back.
- **Backup:** unencrypted, saved to the phone's Downloads folder, deliberately human-readable.
- **File uploads:** never silent; the app proposes a target node and a description, and the owner confirms or adjusts (different node, broader section, mark as new, or free-text instruction).
- **Input per capture:** spoken up to ~5 minutes (≈700 words); typed/pasted up to ~5,000 words (~30,000 characters, roughly a 30–40 minute meeting transcript). Larger inputs are split or attached as a file.

### Deferred to later phases
Encrypted backups, an optional light theme, voice read-back of answers, multi-device sync, and richer attachment types.

---

## 12. Amendments

This section captures decisions made *after* v1.0 was first agreed and built against. Every amendment is **additive**: the original text above is intentionally preserved so the rationale and the trail of decisions stays auditable. Where an amendment overrides an earlier requirement, the original is marked "(superseded)" inline below — never deleted.

### Amendment A1 — 2026-06-20: Visual identity → "Lapis & Linen" (light)

**Status:** Adopted.
**Supersedes (in part):** §8.3 ("Ink & Mist", dark) and §11 ("Theme: the single dark 'Ink & Mist' theme is final for v1; no light theme.").

**What changed.** The owner reviewed the first Capture-screen build and felt that a dark canvas with a muted teal accent did not read as premium — too close to generic dark-app territory, and the dark green felt institutional rather than considered. The visual identity is therefore being re-pitched to a **light, paper-like theme** with a deep jewel-tone accent — codenamed **"Lapis & Linen"**.

**New direction (Lapis & Linen).**
- **Canvas:** warm linen paper, a subtle top-to-bottom gradient from `#F6F2E9` → `#ECE6D8`. Reads as a quiet, lit room at midday rather than a dusk study.
- **Primary text:** deep ink `#15171C` (the colour that used to be the dark canvas — flipped to type).
- **Secondary text:** muted slate `#5C6470`.
- **Accent / active state:** **Sapphire** `#2657A1` — used sparingly for the primary action, listening state, link colour, and the breathing-orb glow. The accent is the one bright moment; everything else stays restrained.
- **Hairlines & dividers:** ink at ~10% opacity, replacing white-12% glass borders.
- **Glass surfaces:** white at ~55–65% opacity over the paper, with a hairline ink border. The surfaces still read as floating cards, but they are now *brighter* than the canvas instead of darker than it.
- **Domain hues** (still small-dose only): work → sapphire `#2657A1`; personal → terracotta `#B5734A`.

**What still holds.** The principles in §8.1, §8.2, §8.5–§8.8 (no AI-slop tropes, calm/restrained mood, glass used in a few deliberate places, soft motion, signature breathing orb) all carry over unchanged. The typography stack and 8pt spacing grid are unchanged.

**Original direction (retained for reference).** §8.3 above describes the dark "Ink & Mist" theme with the moonstone teal accent (`#7FB3B0`). It is no longer the target look but is kept in the document because (a) several copy decisions and animation choices were written against it, and (b) a dark mode may return as an optional theme in a later phase.

### Amendment A2 — 2026-06-20: Ask is available directly from the home screen

**Status:** Adopted.
**Supersedes (in part):** **FR-1.1** ("The home screen presents a single primary action to start speaking ('tap to speak')."). FR-1.1 still describes the *dominant* action; what changes is that the home screen also offers a *secondary* Ask action.

**What changed.** During first testing the owner observed that capturing a thought takes one tap, but *asking* the app a question requires opening the menu and navigating to a different screen first. That asymmetry undermines the "second brain at hand" feel. The home screen should let the owner work from one place.

**New requirements.**
- **FR-1.1a** The home screen presents two voice actions: the dominant **Capture** action (the breathing orb, unchanged) and a secondary **Ask** action shown beneath it. Capture remains visually the larger, more luminous of the two — Ask is a calmer companion.
- **FR-1.1b** Tapping Ask starts on-device speech recognition in the same way Capture does. The screen reuses the same review affordance (live transcript, tap to stop). When the owner stops, the transcript is sent into the Ask flow, not the Capture filing flow.
- **FR-1.1c** During an Ask voice input, the orb visual stays present but tints to the accent's softer companion (a paler sapphire wash), so the owner can tell at a glance which mode they are in.
- **FR-1.1d** When Ask is taken from the home screen, the app transitions to the Ask answer view, with the question already submitted — the owner does not have to confirm or re-tap "send".
- **FR-1.1e** Typing a question by text is still supported via the existing Ask screen (FR-4.1 unchanged).

**Why this is consistent with the rest of the functional spec.** Capture is still the *signature* moment with the breathing orb (FR §8.7). Ask is still an answer screen with text output (FR-4.5), with source-node chips (FR-4.4). The change is purely how the owner *enters* Ask: from a hamburger menu (old) **or** with one tap from the home screen (new).

