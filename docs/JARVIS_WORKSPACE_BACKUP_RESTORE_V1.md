# Workspace Backup & Restore V1

**Status:** source-level implementation in the desktop PWA, Android Compose client and authenticated Python console. Backup crypto and restore endpoint tests are authored, not run. No platform runtime or device verification is claimed.

## Purpose and scope

The `.jwb` file is a password-encrypted, point-in-time backup of the portable shared workspace V1. It contains the canonical workspace document (conversations, explicitly approved memories, task summaries and non-secret settings), a backup identifier/time/platform, and a SHA-256 integrity digest. It is deliberately a different format and operation from the merge-oriented `.jws` transfer.

A `.jwb` file does **not** back up the task/session JPVR registry, Android-only SharedPreferences drafts, Android Keystore keys, authentication tokens, neural/network checkpoints, Python environments, application binaries or the JPVR container itself. Those stores have separate owners and backup/recovery requirements.

## Cryptographic format

- Envelope: `jarvis.workspace.backup.encrypted`, extension `.jwb`.
- Payload: `jarvis.workspace.backup`, version 1.0.
- PBKDF2-HMAC-SHA256, 310,000 iterations, random 16-byte salt.
- AES-256-GCM, random 12-byte nonce, 128-bit tag.
- Backup-specific authenticated additional data: `JARVIS-WORKSPACE-BACKUP-ENCRYPTED-V1`. A transfer `.jws` envelope cannot be substituted for a backup envelope.
- The nested workspace keeps its own canonical SHA-256 digest. The backup wrapper has a second digest over its metadata and nested workspace. AES-GCM authenticates the encrypted bytes.
- The passphrase is not stored or sent to the local API. Store it separately from the backup file. A forgotten passphrase cannot be recovered.

## Backup procedure

1. Refresh the current workspace from the local authenticated service.
2. Create and encrypt a `.jwb` snapshot.
3. Save it through the browser download or Android Storage Access Framework.
4. The interface records the revision covered by the exported backup. A later workspace mutation invalidates that evidence.
5. Before restore, the user must explicitly acknowledge that the current-revision backup was saved somewhere separate.

The platform reports that a download/write was initiated or completed at its API boundary; it cannot prove that a browser user retained the downloaded file or that external storage will remain available. The user acknowledgement is therefore required and must be honest.

## Restore procedure

1. A `.jwb` file is read locally and decrypted. The envelope format/AAD, UTF-8 JSON canonicality, backup wrapper digest, nested workspace digest, schema, sizes and secret-setting restrictions are checked before any write.
2. The backup's `workspace_id` must match the destination workspace. Restore cannot silently replace or adopt another workspace identity.
3. The UI displays the backup timestamp and counts of conversations, memories, task summaries and settings. This is a replacement preview, not a merge.
4. Restore is disabled unless the current workspace revision still matches the saved pre-restore backup revision and the user acknowledges the separate backup.
5. On explicit confirmation, the client POSTs the full replacement to authenticated `/api/workspace/restore` with the exact expected revision. The server validates the portable workspace again and publishes it with JPVR CAS. A stale revision returns HTTP 409 and must invalidate the preview.
6. The current local workspace is replaced by the selected backup snapshot. There is no field-by-field conflict merge in restore mode.

A failed or ambiguous request must not be blindly retried. Refresh the workspace and compare its contents with the selected backup before deciding what happened. Keep the pre-restore `.jwb` until the restored state has been inspected.

## JPVR file corruption

Workspace backup restore requires the local console and workspace store to be readable. If the underlying `workspace.jpvr` is damaged and the console cannot open it, do not delete, truncate or overwrite the only source file. Preserve a byte-for-byte copy first. The existing Python reference recovery path is separate:

- `JPVRStorage.scan_history()` is read-only and identifies the last verified committed prefix.
- `JPVRStorage.recover_copy(destination, expected_commit_id=...)` can publish that prefix as a new file only after a fresh locked scan and readback. It refuses an existing destination and never mutates the source.
- Open and inspect the recovered copy separately. Do not replace the original JPVR file automatically. The scanner/recovery-copy remain reference components and are not yet verified on Android filesystems or Windows.

There is no automatic repair, background recovery, in-place rollback or guaranteed power-loss behavior.

## Cross-platform and operational boundaries

Android uses the Storage Access Framework and the Termux loopback service at `127.0.0.1:8765`. Desktop uses the browser file picker and download. Both use the same payload/envelope contract and restore endpoint. PC/Android local workspaces remain separate; `.jwb` is manually moved between devices, not synchronized.

The service remains loopback-only. Never expose it through LAN, `0.0.0.0`, a tunnel or the public Internet as a backup shortcut. The Bearer token is local API access control, not a human identity or an approval for external actions.

## Verification

- Python restore-endpoint regression test: AUTHORED, NOT RUN.
- Android backup/restore AES-GCM tests: AUTHORED, NOT RUN.
- Desktop WebCrypto backup/restore browser flow: source-level only, NOT RUN.
- Android SAF write/read, Termux end-to-end restore, CAS race handling, Windows 10/11, browser download retention, large-file behavior and filesystem fault injection: NOT RUN.
- No tests, builds, APK/AAB, CI or GitHub Actions were run for this source change.
