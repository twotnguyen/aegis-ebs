Language: English | Vietnamese original: docs/DEMO-3ROLES.md

# THREE-ROLE DEMO MODEL — AEGIS-EBS

> 🌐 [Vietnamese version](../DEMO-3ROLES.md) · [Language guide](../I18N.md)

> SIMULATION/TRAINING ONLY. This is an academic prototype; it does not replace certified fire alarm or evacuation public-address systems. Run all commands from the repository root `aegis-ebs/`.

## 1. Overview

The demo is shown on three screens at once, one role per screen, so it is clear who observes, who receives alerts, and who operates. The three roles share one TLS server (`9443`), one status feed (`ws://localhost:9444`) and one HTTP bridge (`http://localhost:9445`); start everything with `bash scripts/demo-web.sh` or the manual commands in `docs/en/USER-GUIDE.md`.

Published figures stay unchanged in this document: 347 Java tests + 20 dashboard tests, CI (`.github/workflows/ci.yml`), 100-client benchmark, chaos C01-C05, digital twin footprints 72x50 m, i18n 299 keys. No new figures are introduced into the demo flow.

## 2. The three roles

| Role | Screen | Responsibility in the demo | Does not |
|---|---|---|---|
| **3D twin** | Three.js dashboard, `http://localhost:5173`, **Twin** tab | Viewing screen: zones change color by severity, timeline/replay, chaos panel, reports, delivery funnel; click a floor for fly-to/cutaway/explode. | Does not publish, approve or cancel commands. |
| **Zone client** | JavaFX desktop, e.g. device `DEV-A-F05-B-01` | Receives `ALERT_PUSH` for its own zone; stores a durable inbox (SQLite WAL) then sends `ACK_RECEIVED`; popup + beep; a person presses **Acknowledge** to send `ACK_USER` with `userId`. | Does not receive alerts for other zones; never derives `ACK_USER` from `ACK_RECEIVED`; cannot publish. |
| **Admin console** | **Admin** tab on the dashboard (extended HTTP bridge `http://localhost:9445`) | Monitors fleet/status; approves or edits `PUBLISH` grants in the `role_grants` table; reads the `audit_events` log (actor, action, scope, outcome, correlationId) through the extended bridge. | Does not edit an alert already in flight; scope always comes from the DB, never from a client payload. |

Note: PUBLISH grants and the audit log use the two existing tables `role_grants` and `audit_events` (see `docs/en/ERD.md`). Approving/editing a grant and reading audit through the extended bridge are choices of the demo model, not new tables or columns. Every Admin mutation requires an enabled `SYSTEM_ADMIN` as `operator`; the audit `actor_id` is that real user ID, never a self-declared string. The bridge binds loopback by default; if widened to Tailscale, restrict it with ACLs and move to a fully authenticated server API before any non-lab use.

## 3. Five-step demo flow

**Step 1 - Admin approves the PUBLISH grant.** Log in as `admin/admin-demo-secret` (SYSTEM_ADMIN) and open the **Admin** tab. In `role_grants`, approve or edit the `PUBLISH` grant for `secops` (building) and/or `floor7` (floor 7). Scope is derived from the DB. Without a valid grant, the operator's publish returns `DENIED` / `INVALID_SCOPE` and is still written to the audit.

**Step 2 - Operator publishes F05-B.** The operator sends `ALERT_CREATE` then `ALERT_PUBLISH` with an idempotent `requestId`; the target snapshot is zone `F05-B` (floor 5, zone B), immutable after activation. The server checks RBAC from `role_grants`, then fans out `ALERT_PUSH` to devices in the zone by priority (CRITICAL first).

**Step 3 - JavaFX client `DEV-A-F05-B-01` popup + Acknowledge.** The target device receives the push, commits the durable inbox **before** sending `ACK_RECEIVED`; the window shows the SIMULATION banner, popup + beep. A person presses **Acknowledge** -> `ACK_USER` (with `userId` and a separate timestamp). Devices outside zone `F05-B` do not receive the alert; retries (3s/6s/12s, at most 3) target only the unacked delivery.

**Step 4 - 3D twin changes color.** The dashboard receives `status/1` over WS `9444`; zone `F05-B` on the twin (10 floors x 4 zones, footprints 72x50 m) changes color by severity; the delivery funnel advances `SENT -> RECEIVED -> ACK_USER`. The twin only observes: the color is the result of real ACKs from the client, not a command sent by the twin.

**Step 5 - Admin reads the audit.** Back on the **Admin** tab, read `audit_events` through the extended bridge: entries with action `PUBLISH`, `PUBLISH_DUPLICATE`, `DENIED`, `AUTH_OPERATOR_SUCCESS` / `AUTH_OPERATOR_REJECTED` (plus the entry written for the grant approval by the bridge) are append-only, with actor/scope/outcome/correlationId. Incident replay is read-only and does not re-emit alerts.

## 4. Tailscale: what works and what does not

- **Works:** Tailscale is an L3 overlay with `100.x` addresses. All TCP/TLS/HTTP/WS channels pass through it normally: TLS `9443`, status WS `9444`, bridge `9445`, Vite `5173`. From another machine on the tailnet you can open `http://100.x.y.z:5173` or point the JavaFX client at `100.x.y.z:9443` (still using the truststore that matches the self-signed certificate).
- **Does not work:** Lesson 8 multicast (`239.10.10.10:9998`, TTL=1). TTL=1 keeps the packet inside one LAN segment; the L3 overlay of Tailscale does not forward multicast between machines. Do not run the multicast lab over the tailnet.
- **Alternatives:** use a physical LAN (2 or more machines) for the real multicast demo; on a single host use the unicast fallback (`UnicastHintPublisher` / `UnicastHintSubscriber`) - network-lab falls back automatically when multicast is unavailable (see `docs/en/USER-GUIDE.md` section 9 and `docs/en/ARCHITECTURE.md`).

## 5. Links

- Vietnamese version: [`docs/DEMO-3ROLES.md`](../DEMO-3ROLES.md)
- Operation guide: [`docs/en/USER-GUIDE.md`](USER-GUIDE.md) - 12-minute script: [`docs/en/DEMO-RUNBOOK.md`](DEMO-RUNBOOK.md)
