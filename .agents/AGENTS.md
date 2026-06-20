# Project-Specific Agent Rules for Cortex

## Mandatory Handoff Documentation

Whenever an agent finishes a block of work, they **MUST** perform the following steps before ending their turn:

1. **Update `DEVELOPMENT_PROGRESS.md` (Append Chronologically):** Open the `DEVELOPMENT_PROGRESS.md` file in the project root. You must **append** your recent work to the chronological log section at the bottom of the file. Do NOT delete or overwrite the historical record. This ensures a complete sequence of everything that has been done from the start of the project is maintained. Include any important technical decisions or workarounds made, and update the top-level "Immediate Next Steps" section for the following agent.
2. **Update Implementation Plan:** If the implementation plan has changed, ensure the changes are reflected in the appropriate planning artifact (`implementation_plan.md`). 

This ensures that the next agent picking up the task has perfect continuity and understands exactly where to start without needing to read the entire conversation history.
