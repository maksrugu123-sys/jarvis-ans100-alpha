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

## Phone folder storage and chat commands

The Android app stores approved local memories in the app-specific external folder:

`Android/data/<applicationId>/files/JARVIS/Memory/approved-memory.jmem`

The folder and initial V2 memory file are created automatically when the memory cache is first initialized (including the first app launch that constructs the ViewModel). On later launches the storage adapter reads the existing file before the memory cache is exposed to the conversation generator. If the app-specific external directory is unavailable, Android's internal app files directory is used as a fallback.

The first read migrates the previous `SharedPreferences` snapshot into the new file if no file exists yet. Writes use Android `AtomicFile`, so an interrupted write should not replace the last valid snapshot.

Chat commands:
- `/memory location` — show the actual storage directory.
- `/memory export` — write a timestamped `.jmem` export to the public `Downloads/JARVIS` folder on Android 10+, without requesting broad storage permission. Older Android versions use the app-specific `JARVIS/Memory/exports` folder.
- `/memory import` — open the Android file picker for a `.jmem` backup. The selected file is bounded to 3 MiB and fully decoded/validated before it is staged; invalid files do not change current memory.
- `/memory import confirm` — replace the complete current memory snapshot with the staged backup. This is a separate explicit confirmation; it does not merge records.
- `/memory import cancel` — discard the staged backup without changing current memory.
- `/memory reset` — ask for confirmation.
- `/memory reset confirm` — clear all approved memory records, including superseded correction history, while retaining the folder and an empty V2 file.

Russian aliases are also accepted: `/память папка`, `/память выгрузить`, `/память импорт`, `/память импорт подтвердить`, `/память импорт отмена`, `/память сбросить`, and `/память сбросить подтверждаю`. Export includes the approved-memory snapshot only; it does not include chats, model weights, access tokens, or other app data. A restored backup replaces only approved local memory; it does not restore conversations, model weights, settings or access tokens.

## Explicit correction history

- A direct correction through `LocalWorkspaceMemoryCache.correct(id, correctedContent)` creates a new approved record and links the old record to it with `supersededById`; it never silently overwrites the old text.
- The response generator excludes superseded records from recall, while the full snapshot retains them as an audit trail.
- The storage codec writes format V2 and reads legacy V1 snapshots for one-way migration. Correction links are validated; a failed commit leaves the previous active memory unchanged.
- Deleting a current corrected record also removes its superseded ancestors to avoid dangling lineage.
- This is an explicit cache API foundation only: the Compose memory panel does not yet expose a correction action, and automatic natural-language conflict detection is intentionally not implemented. A correction must be explicitly confirmed by the user; timestamps alone do not decide which fact is true.

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
