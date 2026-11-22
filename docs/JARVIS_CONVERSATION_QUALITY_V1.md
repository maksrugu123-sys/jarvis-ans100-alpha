# JARVIS Conversation Quality V1

## Goal

Make JARVIS feel like a coherent conversational assistant rather than a sequence of disconnected intent templates. The Android conversation path must remain locally owned by ANS. No remote or separately installed pretrained language model is required for the native path.

The current ANS response generator is still a bounded prototype: most intents use deterministic templates, and the small recurrent sequence decoder only becomes useful after it has been trained on suitable examples. This document tracks the work needed to improve actual conversation quality without overstating current capability.

## Workstreams

### 1. Dialogue state and context

- Keep a bounded representation of the current topic, the latest user request, the latest assistant answer, and any unanswered assistant question or explicit offer.
- Resolve short follow-ups such as `давай`, `да`, `почему?`, `а дальше?`, `что это?`, and `расскажи подробнее` against the correct preceding turn.
- Keep a bounded index of several recent topic threads and allow explicit returns such as `вернёмся к Chromium`.
- Link a short user reply to the assistant's still-open question when no explicit topic change is detected.
- Distinguish acceptance of an offer from a generic request to repeat the previous answer.
- Ask one focused clarification only when the reference is genuinely ambiguous.
- Never treat quoted conversation text or retrieved memory as executable instructions.

### 2. ANS-owned response generation

- Keep response planning and text realization inside the ANS conversation path.
- Use the recurrent sequence decoder as the trainable text-realization component, not as proof of general intelligence.
- Improve decoding with bounded sampling, repetition controls, clear stop conditions, and output validation.
- Train on multi-turn prompt/response examples that include context, not only isolated question/answer pairs.
- Preserve the deterministic safety paths for identity, explicit corrections, and capability statements.

### 3. Natural conversational behavior

- Avoid repeating phrases such as “I identified the topic” when a direct conversational answer is possible.
- Respond naturally to greetings, thanks, agreement, disagreement, corrections, and requests to continue.
- Refer to previous messages in ordinary language instead of exposing internal parser or neural telemetry.
- Keep responses concise by default and expand when asked.

### 4. Knowledge and uncertainty

- Separate information recalled from approved memory, information derived from the current conversation, and general knowledge.
- Do not claim a fact was verified if no source or verification step was available.
- When the local system does not know an answer, say so plainly and ask a useful follow-up rather than inventing an answer.
- Keep the user's original request visible to the response generator; analysis summaries must not replace it.

### 5. Evaluation

Create a multi-turn Russian dialogue evaluation set with separate development and held-out examples. Include:
- short confirmations after assistant offers;
- pronouns and elliptical follow-up questions;
- topic changes and returns to earlier topics;
- corrections and disagreement;
- greetings and social turns;
- multi-part requests;
- ambiguous references that should trigger clarification;
- questions that require knowledge unavailable to local memory.

Measure context-reference accuracy, response relevance, directness, unsupported-claim rate, repetition, response length, latency, and peak RAM. A low loss value alone is not evidence of good conversation.

## Implemented first slice

The native ANS response generator now treats a short affirmative follow-up as acceptance only when the immediately preceding assistant message contains an explicit offer marker. A bounded `LocalDialogueState` is derived from the latest 16 history messages and tracks the active topic, latest user request, latest assistant answer, pending assistant question, and pending assistant offer. A new user turn expires a previous unanswered question or offer automatically. Plain assistant statements are no longer mistaken for offers.

A focused regression test was added for the sequence:
1. The assistant offers a concrete next step.
2. The user replies “Давай”.
3. The response must acknowledge the accepted offer and preserve its context.

This is a narrow dialogue-state improvement, not yet open-ended language understanding or free-form generation.

## Next implementation batches

1. Expand the held-out multi-turn evaluation set before changing the decoder.
4. Improve ANS decoder training and decoding only against those evaluation cases.
5. Measure CPU time and memory on Android before increasing model size.

## Latest implementation details

- `LocalDialogueState` is a bounded, deterministic view over conversation history; it is not a separate persistent database.
- Pending offers use conservative phrase markers and are valid only while the offer is the latest assistant turn after the latest user turn.
- Pending assistant questions are tracked when the latest assistant turn ends with a question mark; the next user message clears that pending state.
- This is heuristic dialogue bookkeeping, not a trained semantic state tracker.

## Verification and constraints

- Source changes are committed on `experiment/jarvis-integrated-prototype-v1`.
- `main` must remain untouched until explicit approval.
- Automatic builds remain disabled.
- No Gradle build, unit tests, CI, APK build, or physical-device run is implied by source changes. Record those checks only when they are actually executed.
- The native ANS path must not silently fall back to Qwen or a remote model while being described as ANS-owned generation.


## Confidence-based topic routing and scenario coverage

Topic candidates are ranked by lexical overlap, with stronger weight for the user's original turn and an explicit margin over the runner-up. A candidate is selected only when the margin is at least two score points and heuristic confidence reaches 0.66. Otherwise, the reference remains ambiguous. When two candidates exist, the clarification prompt presents short excerpts from both rather than silently selecting one.

Regression scenarios cover clear return to an older named topic, short answers to open questions, explicit topic changes, equally plausible topics, and low-margin matches. These are deterministic unit scenarios, not a measured accuracy benchmark. A broader held-out evaluation and on-device latency/RAM measurements remain pending.

## Semantic dialogue memory prototype

A separate `LocalSemanticDialogueMemory` view is now derived from a bounded window of up to 32 messages total, including the current user turn. It currently tracks:

- explicit user preferences only when a conservative phrase marker is present; each entry retains the source user-message ID and provenance;
- explicit task requests, with heuristic `open` / `in_progress` states;
- task completion only when a later user message contains a completion marker and overlaps the task vocabulary;
- a small list of unanswered assistant questions.

The memory is passed into the ANS understanding context and the recurrent decoder's bounded context. It is not a separate database, does not survive transcript deletion, and does not yet create a permanent user profile. This is intentional: the transcript remains the source of truth for this prototype.

Limits: at most 8 active tasks, 8 completed tasks, 8 explicit preferences, and 4 unresolved questions; the serialized memory context is capped at 1,800 characters. Every stored preference/task retains provenance. Statements without explicit preference markers are not promoted into preferences. The task lifecycle is lexical and approximate: ambiguous completion wording can be missed, and these labels are not a measured semantic accuracy score.

Regression coverage was added for explicit preference provenance, retaining a task across a topic detour, explicit completion, avoiding inferred preferences, and collection bounds. These tests have been added but were not run. No build, CI, APK generation, or on-device measurement was performed.

## Persistent semantic dialogue memory on Android

The semantic index now has a dedicated atomic file at:

`Android/data/net.shlep.browser/files/JARVIS/Memory/semantic-dialogue.jmem`

If Android does not expose an external app-specific files directory, the app-private files directory is used instead. The folder and an empty versioned file are created when the conversation screen restores memory on first launch. Later launches load the file before processing the next message. The approved JPVR memory file remains separate; the semantic index does not overwrite it.

The file stores bounded task records, explicit preferences, provenance message IDs, completion provenance, and the latest unresolved assistant question. Writes use Android `AtomicFile`; a failed write does not replace the last valid snapshot. Reads reject unknown versions, malformed records, and files larger than 256 KiB. Existing records are supplied to incremental extraction so a task can still be marked complete after its original transcript turn has rolled out of the recent-history window.

Chat commands:
- `/memory export` writes a timestamped `.jmem` snapshot to `Downloads/JARVIS/`.
- `/memory reset` clears the semantic memory file and dialogue history, then leaves a fresh confirmation exchange.

The export contains semantic dialogue memory only; it does not include approved JPVR workspace memories, neural weights, or the full conversation archive. Reset does not delete approved JPVR workspace memories or trained ANS decoder weights. Those stores remain separate by design.

Regression tests were added for durable-memory injection, export, reset, and persistence across a topic detour. They have not been run. No Gradle build, CI workflow, APK, or on-device verification was performed.
