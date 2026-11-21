# JARVIS Android Local Approved Memory V1

**Status:** source implementation on mobile-first-offline-v1; Kotlin tests AUTHORED, NOT RUN; Android persistence and device behavior NOT VERIFIED.

## Purpose

Provide useful approved-memory recall on the Android phone without requiring Termux, a running Python server, internet access or a remote JPVR workspace connection.

## Implementation

- SharedPreferencesLocalWorkspaceMemoryStorage stores the versioned snapshot in application-private SharedPreferences.
- LocalWorkspaceMemoryCodec uses canonical Base64URL-encoded UTF-8 fields and rejects unknown format versions, malformed encodings, duplicate IDs, invalid text, invalid lengths and trailing/noncanonical data.
- LocalWorkspaceMemoryCache loads the snapshot at app startup and validates each candidate before publishing it. A mutation is visible to JARVIS Core only after SharedPreferences.commit() returns success. Add/delete operations are dispatched on the conversation background executor so synchronous preference I/O does not block the Compose main thread; the panel clears its text only after an acknowledged successful save.
- A corrupt snapshot or failed write blocks new mutation rather than silently resetting or claiming success.
- The Compose device-memory panel supports explicit add, search/filter and delete. New device-local notes are approved by the user's direct save action.
- Imported shared-workspace records are merged only when user_approved=true. Existing local records are preserved when an imported batch arrives, and the panel state is refreshed after the import/restore commit.
- Removing the optional Termux bearer token does not delete device-local notes.

## Bounds

| Field | Limit |
|---|---:|
| Records | 2,000 |
| Content | 700 characters |
| Category | 64 characters |
| ID | 128 characters |
| Updated-at metadata | 80 characters |
| Encoded snapshot | 3 MiB |

Core retrieves at most three lexical-overlap matches and supplies at most 1,800 characters of memory context to the language component. Retrieved notes are explicitly untrusted reference data, not instructions.

## Persistence and portability boundary

This is app-private SharedPreferences storage, not a native Kotlin JPVR/JNVM engine. The feature does not add application-level encryption at rest. Android-created device-local notes are not automatically uploaded to the Python JPVR workspace and are not currently included in the .jws / .jwb export path. Shared workspace records and device-local notes therefore remain separate across platforms; there is no automatic synchronization.

The old Python desktop JPVR workspace continues to be the canonical PC workspace. Termux remains optional and may import approved workspace records into the Android local cache when connected.

## Tests and verification

app/src/test/java/org/jarvis/ans100/alpha/data/LocalWorkspaceMemoryStoreTest.kt covers reopen, approved-only merge, preservation of local records, failed commit, corrupt snapshot fencing, size limits, unloaded-cache state and case-sensitive imported IDs. Status: AUTHORED, NOT RUN.

No Gradle build, APK/AAB, Android installation, process-kill recovery, device storage test, encryption review or performance measurement has been run. Do not claim verified durability until those checks are separately authorized and executed.
