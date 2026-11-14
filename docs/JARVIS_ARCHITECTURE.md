[object Object]

### 3.76 Read-only Task Loop action execution V1 (14 November 2026)

The PC local console can now execute a bounded user-requested Task Loop through `POST /api/tasks/{task_id}/run`. The request must provide a task revision and between one and four exact action steps. V1 exposes only `memory.search`, `browser.search`, `browser.open` and `browser.read`. The first reads only user-approved shared-workspace memories; the browser operations run only on the desktop's visible Playwright browser and remain GET/read-only.

Every step is represented as a dependency-linked `PlanningAction`, bound to an exact read-only capability/scope and bounded compute/memory/time/output budget. The existing `JARVISTaskLoop` persists task lifecycle/step results in the task/session JPVR checkpoint and the permission gate is deny-by-default. The UI/API caller must explicitly request the run; a queued task does not execute automatically.

No shell, file mutation, downloads, messaging, purchases, account login, uploads, or arbitrary plugin/tool execution are exposed. Side-effecting actions are deliberately not part of this V1 and must not be added without a trusted signed approval provider, exact request binding, durable idempotency, and downstream reconciliation. This is a safe read-only action loop, not unrestricted autonomous control.

Four service/adapter tests are AUTHORED, NOT RUN. PC Playwright/browser runtime validation remains separate.


### 3.77 Peer memory sync transport V1 (14 November 2026)

The PC console now has a separate, optional TLS peer listener. The ordinary local-console listener remains loopback-only. The peer listener requires an explicit private IPv4 address, rejects wildcard/public binding and refuses non-loopback HTTP. It checks the Host/Origin against the configured address and uses the existing Bearer authentication. The peer certificate must be installed with an IP Subject Alternative Name; the planned Android client will pin its SHA-256 leaf-certificate fingerprint.

`PeerMemorySyncStore` is a bounded, CAS-protected additive overlay for explicitly approved memories only. It uses atomic temp-file/fsync/replace publication and a monotonically increasing revision. Same-ID updates compare updated_at; conflicts are retained/reported. V1 does not sync chat history or deletion tombstones and keeps the peer overlay separate from the main JPVR workspace. The PC read-only action executor can include peer-overlay records in approved-memory search.

The Android TLS pinning client, encrypted peer credentials UI, automatic sync trigger and device-to-device runtime validation remain NOT IMPLEMENTED. Four Python store tests are AUTHORED, NOT RUN. See `docs/JARVIS_PEER_MEMORY_SYNC_V1.md`.
