# JARVIS Peer Memory Sync V1

**Status:** PC-side peer-sync memory store and TLS LAN listener implemented at source level. Android peer client and UI integration remain the next step; no cross-device runtime is verified yet.

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

The peer endpoint is `https://LAN_IP:8766`; use the certificate SHA-256 and the local console Bearer token when pairing the phone. Restrict the OS firewall to the private network profile and this port. Do not share the certificate private key or access token.

## Data contract

Authenticated endpoints:
- `GET /api/peer-sync/memories` returns the peer revision and approved memory records.
- `POST /api/peer-sync/memories` accepts exactly `expected_revision` and a list of approved records.

Records contain only `id`, `content`, `category`, `updated_at` and `user_approved=true`. The service uses revision compare-and-swap and atomic file replacement. It merges additively, prefers a newer timestamp for same-ID edits, and reports conflicts instead of silently overwriting newer data. Deletion tombstones and full chat-history sync are intentionally not part of V1.

The peer overlay is separate from the JPVR workspace file. The PC read-only action executor can include these peer memories in its approved-memory search. This keeps peer transport data distinct from credentials and runtime checkpoints.

## Verification

Four Python tests are authored for merge/reopen, newer-update, CAS conflict and rejection of unapproved/duplicate records. Tests and TLS end-to-end connection are **AUTHORED, NOT RUN**. The Android pinning client and app pairing UI are not implemented in this increment.
