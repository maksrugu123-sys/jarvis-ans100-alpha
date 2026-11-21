# JARVIS Peer Memory Sync V1

**Status:** PC-side peer-sync memory store and TLS LAN listener plus a source-level Android HTTPS client/Compose pairing panel are implemented. Cross-device runtime is not verified yet.

## Transport

The ordinary PC local console remains bound to `127.0.0.1`. An optional second listener can bind to one explicit private IPv4 address and requires TLS. Wildcard/public binds and non-TLS LAN binds are rejected. The Android client will pin the SHA-256 fingerprint of the server leaf certificate and use the existing one-time Bearer token. The certificate must contain the LAN IP in its Subject Alternative Name.

Example PC setup:

```bash
LAN_IP=192.168.1.20
openssl req -x509 -newkey rsa:3072 -nodes -days 365 \
  -keyout jarvis-peer.key -out jarvis-peer.crt \
  -subj "/CN=$LAN_IP" -addext "subjectAltName=IP:$LAN_IP"
openssl x509 -in jarvis-peer.crt -outform DER | sha256sum
python3 -m tools.jarvis_app --port 8765 --platform desktop \
  --peer-host "$LAN_IP" --peer-port 8766 \
  --peer-cert jarvis-peer.crt --peer-key jarvis-peer.key
```

The peer endpoint is `https://LAN_IP:8766`; in the Android app, enter the PC's private IPv4 address and the SHA-256 fingerprint printed from the certificate. The source-level Android client reuses the existing Bearer token encrypted by Android Keystore, checks the pinned leaf certificate and retains the platform's IP-SAN hostname verification. It accepts only RFC1918 IPv4 literals and HTTPS; no DNS names, HTTP or public endpoints are allowed. Restrict the OS firewall to the private network profile and this port. Do not share the certificate private key or access token.

## Data contract

Authenticated endpoints:
- `GET /api/peer-sync/memories` returns the peer revision and approved memory records.
- `POST /api/peer-sync/memories` accepts `expected_revision`, approved records and an optional bounded `deleted_ids` tombstone list. The current Android client sends approved records only and does not yet propagate deletion tombstones.

Records contain only `id`, `content`, `category`, `updated_at` and `user_approved=true`. The service uses revision compare-and-swap and atomic file replacement. It merges additively, prefers a newer timestamp for same-ID edits, and reports conflicts instead of silently overwriting newer data. The server accepts optional `deleted_ids`, but the current Android client deliberately sends no tombstones: synchronization is additive/upsert-only, and deleting a note on one side does not delete it on the other. Full chat-history sync is not part of V1.

The peer overlay is separate from the JPVR workspace file. The PC read-only action executor can include these peer memories in its approved-memory search. This keeps peer transport data distinct from credentials and runtime checkpoints.

## Verification

Four Python tests cover merge/reopen, newer-update, CAS conflict and rejection of unapproved/duplicate records. Android unit tests cover private-IP validation, certificate fingerprint normalization, exact approved-record payloads and revision bounds. All these tests are **AUTHORED, NOT RUN**. No TLS end-to-end connection, PC firewall setup, Android UI pairing or target-device verification has been run. Pairing and sync are currently manual; automatic scheduling and deletion-tombstone propagation are not implemented.
