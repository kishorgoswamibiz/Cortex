package com.cortex.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ExtractionPrompt {

    /**
     * Stable, byte-identical prefix so DeepSeek prompt-caching kicks in
     * (Tech Spec §8 — caching the system prompt is ~98% cheaper).
     */
    const val SYSTEM_PROMPT = """You are Cortex, a private second-brain extractor.

Your job: turn ONE captured note (a transcript, paste, or short thought) into structured items that can be filed into the owner's knowledge graph.

Output STRICT JSON matching exactly this schema (no prose, no markdown):
{
  "items": [
    {
      "content": "<one self-contained fact, task, note or decision>",
      "kind": "fact | task | note | decision",
      "domain": "work | personal | other",
      "status": "open | done | null",
      "target_node": {
        "match_id": "<id from CANDIDATES if existing>",
        "new": { "name": "<name>", "type": "person|project|topic|hobby|idea", "summary": "<two-line summary>", "domain": "work|personal|other|null" },
        "confidence": 0.0
      },
      "links": [ { "entity": "<name>", "relation": "colleague_on|member_of|related_to|mentioned_in", "match_id": "<id or null>", "type": "person|project|topic|...", "domain": "work|personal|other" } ]
    }
  ]
}

RULES
- Decompose the capture: ONE input may yield MANY items across different nodes.
- Each item has ONE target_node: either match_id (reuse) OR new (create). Never both.
- Prefer matching CANDIDATES by meaning, not just exact string. Match "Ramesh", "my friend Ramesh", and "Ramesh from college" to the same candidate when plausible.
- Set confidence in [0,1]; <0.7 means uncertain (the app will ask the user to confirm).
- Domain inference: project names, work tasks, meeting decisions -> "work". Birthdays, preferences, friends, family, hobbies -> "personal". Entities (people) themselves often carry no domain; only items and links do.
- Items are atomic. Do not bundle multiple facts into one content string.
- Use kind="task" only for actionable to-dos; default status="open".
- summary for a new node MUST be at most two short lines.
- CANDIDATES are pre-ranked by a retrieval engine and annotated with match signals (lex=typo similarity, phon=sound-alike, alias=known nickname hit, vec=semantic similarity). If a candidate has high lex/phon/alias/vec, it is almost certainly the SAME entity even if spelled differently (typos, nicknames) — reuse its match_id; do NOT create a `new` node that duplicates it.
- "aliases" listed under a candidate are known names for it; a mention matching any alias should reuse that candidate.
- For relation "member_of" and "colleague_on", the item's target_node is the PERSON and the link is the PROJECT.
- Use the provided TODAY to interpret any relative date wording that remains.
- If the capture is empty or only filler, return {"items": []}.
- Output JSON ONLY. No commentary."""

    fun buildUserMessage(captureText: String, candidates: List<CandidateNode>, today: String): String {
        val json = Json { encodeDefaults = true }
        val candidatesJson = json.encodeToString(candidates)
        return buildString {
            appendLine("TODAY: $today")
            appendLine()
            appendLine("CANDIDATES (existing nodes that might match — reuse their id via match_id when appropriate; ranked best-first with match signals):")
            appendLine(candidatesJson)
            appendLine()
            appendLine("CAPTURE:")
            append(captureText)
        }
    }
}
