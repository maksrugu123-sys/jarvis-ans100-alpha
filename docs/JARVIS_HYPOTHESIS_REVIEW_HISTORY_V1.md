# JARVIS Hypothesis Review History V1

## Purpose

Preserve a bounded, immutable audit snapshot for each explicit manual hypothesis review so the reviewer can reopen the materials that informed an earlier decision. A snapshot is historical context, not a fresh fetch, source authentication, or proof of truth.

## Persistence

- The existing SQLite manual-review journal remains the write-ahead record for the decision and its optional explicit-user relation evidence.
- Schema version 2 adds `manual_hypothesis_review_snapshots`, keyed by the same decision ID.
- The decision row and snapshot row are written in one SQLite transaction.
- Existing version-1 databases are upgraded by creating the snapshot table. Older decisions remain valid and appear without a snapshot.
- Snapshots are bounded to 48 KiB per decision. They retain the hypothesis ID, primary page title/URL/content digest/fetch timestamp, source URLs/hosts/digests/audit classifications, extracted excerpts and their digests/polarity candidates, and packet warnings.
- Full fetched page bodies are not stored. Excerpts are capped at the passage extractor's hard limit; source and passage counts are bounded by the packet contracts.
- Reusing a decision ID with a different decision payload or a different snapshot is rejected.

## User workflow

1. The review history shows the saved outcome and rationale.
2. “Материалы проверки” opens the stored historical snapshot, including source metadata, excerpts, heuristic polarity labels, and warnings.
3. “Проверить снова” is offered only while the same hypothesis is still in the current review queue. It starts a new user-triggered search using the current revision state; it does not silently reuse old pages.
4. A new review creates a new packet and a new decision ID. Earlier decisions remain in the journal.

## Safety and interpretation

- Historical excerpts remain unverified and may be stale or incomplete.
- Host diversity and duplicate detection do not establish publisher independence.
- Polarity labels are lexical heuristic candidates, not fact classifications.
- Only an explicit human confirmation can create an `EXPLICIT_USER` relation observation. That is a user attestation, not objective verification.
- Reopening a snapshot does not fetch a URL or change a hypothesis.
- A fresh re-review does not automatically train neural weights. Neural credit remains separate from the manual review journal.

## Verification status

The instrumented test for snapshot persistence across SQLite reopen was added but has not been run. No Android build, test suite, APK, CI workflow, or benchmark was run for this change. Kotlin/Android integration remains unverified until an authorized validation pass.
