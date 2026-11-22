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
