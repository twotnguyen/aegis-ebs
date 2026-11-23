Language: English | Vietnamese original: docs/API.md

# API REFERENCE — EBSP/2.0 (Emergency Broadcast System Protocol)

> A custom application protocol over TCP/TLS. Exact reference: `aegis.protocol` + `docs/PROTOCOL.md` + `Plan/protocol_examples.json`.

## 1. Framing (wire contract)
```
+---------------------+----------------------------------------+
| uint32 BE length L  | L bytes strict UTF-8 JSON              |
+---------------------+----------------------------------------+
```
- `L` ∈ [1, 65536] bytes (measured in bytes, not Java chars). Reject oversize **before** allocation.
- TCP is a byte stream: read the full 4-byte header (`readFully`) then exactly L bytes of payload; handle partial reads and multiple coalesced frames.
- Reject: invalid UTF-8, JSON that is not a single object, version≠2, type outside the enum, missing required fields.

## 2. Envelope
```json
{
  "version": 2,
  "type": "ALERT_PUSH",
  "messageId": "d08b9a90-239d-4701-a931-e8a01ad2e13b",
  "correlationId": "90d8df2d-10a0-4428-819b-6aafbc429461",
  "sentAt": "2026-11-21T00:00:00.000Z",
  "payload": { }
}
```
| Field | Required | Meaning |
|---|---|---|
| version | ✅ | =2 |
| type | ✅ | MessageType |
| messageId | ✅ | unique UUID per send (new on every retry attempt) |
| correlationId | ✅ | points to the messageId of the request being answered |
| sentAt | ✅ | UTC ISO-8601, fixed-width |
| payload | ✅ | object depending on type |

## 3. Message catalog
### Handshake / Identity
| Type | Direction | Main payload |
|---|---|---|
| HELLO | C→S | clientType (DEVICE/OPERATOR/DASHBOARD), deviceId?, supportedVersions[], nonce |
| SERVER_HELLO | S→C | serverId, challenge, heartbeatIntervalMs, nonce echo |
| AUTH_DEVICE | C→S | deviceCode, credential |
| AUTH_OPERATOR | C→S | username, password |
| AUTH_RESULT | S→C | ok, sessionId, permissions, reason? |
| LOGOUT | C→S | — |
| ERROR | S→C | code, message (sanitized) |

### Command (operator)
| Type | Main payload |
|---|---|
| INCIDENT_CREATE | requestId, buildingId, kind, summary, simulation |
| INCIDENT_TRANSITION | incidentId, state (OPEN/INVESTIGATING/CONTAINED/RESOLVED/CLOSED/ABORTED) |
| ALERT_CREATE | requestId, incidentId, category, priority, title, instruction, target{buildingId,floors[],zones[]}, expiresAt, urgency/severity/certainty |
| ALERT_PUBLISH | alertId, revision |
| ALERT_UPDATE | requestId, alertId, new revision, content |
| ALERT_CANCEL | requestId, alertId, reason |
| COMMAND_RESULT | ok, alertId/incidentId, duplicate?, code? |

### Delivery / Client
| Type | Direction | Main payload |
|---|---|---|
| ALERT_PUSH | S→C | incidentId, alertId, revision, deliveryId, buildingId, priority, category, title, instruction, urgency/severity/certainty, expiresAt, simulation |
| ACK_RECEIVED | C→S | alertId, deliveryId, revision, deviceId, ackAt |
| ACK_USER | C→S | alertId, deliveryId, revision, deviceId, userId, action=ACKNOWLEDGE |
| CANCEL_PUSH | S→C | alertId, revision, operationId, reason, simulation |
| ACK_CANCEL | C→S | alertId, operationId, deliveryId, deviceId |
| SYNC_REQUEST | C→S | deviceId, knownRevisions{alertId:revision} |
| SYNC_RESULT | S→C | alerts[] (active latest revision), tombstones[] (cancelled/expired) |
| HEARTBEAT | C→S / S→C | ts |
| HEARTBEAT_ACK | reply | ts |

### Dashboard status feed (WS `ws://host:9444/`, feed `status/1`)
| Type | Payload |
|---|---|
| STATUS_SUBSCRIBE | buildingId (RBAC: blocks EMPLOYEE/DEVICE, scope by grant) |
| STATUS_SNAPSHOT | buildingId, floors[{floorNumber, zones[{zoneCode, devicesTotal, online, received, acknowledged, failed, severity}]}], activeAlerts[], metrics{connected,p50,p95,p99,retryCount} |
| STATUS_DELTA | partially changed floors, activeAlerts?, metrics? (merge) |
| INCIDENT_EVENT | incidentId, eventKind, actorId, correlationId, payload |
| METRIC_SAMPLE | metricName, value, unit, tags |

## 4. Examples (from protocol_examples.json)
**ALERT_PUSH**
```json
{"version":2,"type":"ALERT_PUSH","messageId":"msg-alert-001","correlationId":"req-001","sentAt":"2026-11-21T00:00:01Z","payload":{"incidentId":"INC-TEST-001","alertId":"ALT-TEST-001","revision":1,"deliveryId":"DLV-TEST-001","buildingId":"B01","category":"FIRE","priority":"CRITICAL","title":"SIMULATION — Fire Drill","instruction":"Training only. Do not take real-world action.","simulation":true,"urgency":"Immediate","severity":"Severe","certainty":"Observed","expiresAt":"2026-11-21T01:00:00Z"}}
```
**ACK_RECEIVED**
```json
{"version":2,"type":"ACK_RECEIVED","messageId":"msg-ack-001","correlationId":"msg-alert-001","sentAt":"2026-11-21T00:00:02Z","payload":{"alertId":"ALT-TEST-001","deliveryId":"DLV-TEST-001","revision":1,"deviceId":"DEV-B01-F05-01","ackAt":"2026-11-21T00:00:02Z"}}
```
**ACK_USER**
```json
{"version":2,"type":"ACK_USER","messageId":"msg-user-ack-001","correlationId":"msg-alert-001","sentAt":"2026-11-21T00:00:05Z","payload":{"alertId":"ALT-TEST-001","deliveryId":"DLV-TEST-001","revision":1,"deviceId":"DEV-B01-F05-01","userId":"USER-TEST-001","action":"ACKNOWLEDGE"}}
```
**CANCEL_PUSH**
```json
{"version":2,"type":"CANCEL_PUSH","messageId":"msg-cancel-001","correlationId":"req-cancel-001","sentAt":"2026-11-21T00:00:06Z","payload":{"alertId":"ALT-TEST-001","revision":2,"operationId":"OP-TEST-001","reason":"Training exercise ended","simulation":true}}
```

## 5. Error codes
`AUTH_REQUIRED, ACCESS_DENIED, INVALID_SCOPE, INVALID_FRAME, INVALID_VERSION, MESSAGE_TOO_LARGE, RATE_LIMITED, CONFLICT, EXPIRED, SERVER_BUSY, INTERNAL_ERROR, NOT_FOUND, PRE_AUTH_EXPIRED, FORBIDDEN`.

## 6. Semantic conventions
- **Two-layer ACK**: `ACK_RECEIVED` (client persisted durably) ≠ `ACK_USER` (human gesture). Neither implies the other.
- **Idempotency**: CREATE uses `requestId` (UNIQUE); each delivery has a stable `deliveryId` + `alertId/revision`; each attempt has a new `messageId`. Duplicate ACKs are harmless (ON CONFLICT DO NOTHING).
- **Retry**: wait 3s/6s/12s + jitter for an ACK, at most 3 retries then FAILED. ACK only advances state (rank guard), never regresses.
- **Sync**: on reconnect send `knownRevisions`; the server returns active latest revisions + tombstones; cancelled/expired alerts are not resurrected.
- **Heartbeat**: 10s; suspect after 3 misses; client-initiated HEARTBEAT + server HEARTBEAT_ACK.
- **Priority**: CRITICAL > EMERGENCY > WARNING > INFO (application-level scheduling, fairness burst 8).
- **Multicast/UDP**: hint only, not a reliable command; the client must fetch verified content over TLS before display.
