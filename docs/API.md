# API REFERENCE — EBSP/2.0 (Emergency Broadcast System Protocol)

> Giao thức ứng dụng tự thiết kế trên TCP/TLS. Tham chiếu chính xác theo `aegis.protocol` + `docs/PROTOCOL.md` + `Plan/protocol_examples.json`.

## 1. Framing (wire contract)
```
+---------------------+----------------------------------------+
| uint32 BE length L  | L bytes strict UTF-8 JSON              |
+---------------------+----------------------------------------+
```
- `L` ∈ [1, 65536] byte (đo theo byte, không phải Java char). Chặn oversize **trước** cấp phát.
- TCP là byte stream: đọc đủ 4 byte header (`readFully`) rồi đúng L byte payload; xử lý partial read + nhiều frame dính nhau.
- Reject: UTF-8 lỗi, JSON không phải single object, version≠2, type không thuộc enum, field bắt buộc thiếu.

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
| Field | Bắt buộc | Ý nghĩa |
|---|---|---|
| version | ✅ | =2 |
| type | ✅ | MessageType |
| messageId | ✅ | UUID duy nhất mỗi lần gửi (mới mỗi retry attempt) |
| correlationId | ✅ | trỏ messageId của request được trả lời |
| sentAt | ✅ | UTC ISO-8601 fixed-width |
| payload | ✅ | object theo type |

## 3. Message catalog
### Handshake / Identity
| Type | Hướng | Payload chính |
|---|---|---|
| HELLO | C→S | clientType (DEVICE/OPERATOR/DASHBOARD), deviceId?, supportedVersions[], nonce |
| SERVER_HELLO | S→C | serverId, challenge, heartbeatIntervalMs, nonce echo |
| AUTH_DEVICE | C→S | deviceCode, credential |
| AUTH_OPERATOR | C→S | username, password |
| AUTH_RESULT | S→C | ok, sessionId, permissions, reason? |
| LOGOUT | C→S | — |
| ERROR | S→C | code, message (sanitized) |

### Command (operator)
| Type | Payload chính |
|---|---|
| INCIDENT_CREATE | requestId, buildingId, kind, summary, simulation |
| INCIDENT_TRANSITION | incidentId, state (OPEN/INVESTIGATING/CONTAINED/RESOLVED/CLOSED/ABORTED) |
| ALERT_CREATE | requestId, incidentId, category, priority, title, instruction, target{buildingId,floors[],zones[]}, expiresAt, urgency/severity/certainty |
| ALERT_PUBLISH | alertId, revision |
| ALERT_UPDATE | requestId, alertId, revision mới, nội dung |
| ALERT_CANCEL | requestId, alertId, reason |
| COMMAND_RESULT | ok, alertId/incidentId, duplicate?, code? |

### Delivery / Client
| Type | Hướng | Payload chính |
|---|---|---|
| ALERT_PUSH | S→C | incidentId, alertId, revision, deliveryId, buildingId, priority, category, title, instruction, urgency/severity/certainty, expiresAt, simulation |
| ACK_RECEIVED | C→S | alertId, deliveryId, revision, deviceId, ackAt |
| ACK_USER | C→S | alertId, deliveryId, revision, deviceId, userId, action=ACKNOWLEDGE |
| CANCEL_PUSH | S→C | alertId, revision, operationId, reason, simulation |
| ACK_CANCEL | C→S | alertId, operationId, deliveryId, deviceId |
| SYNC_REQUEST | C→S | deviceId, knownRevisions{alertId:revision} |
| SYNC_RESULT | S→C | alerts[] (active latest revision), tombstones[] (cancelled/expired) |
| HEARTBEAT | C→S / S→C | ts |
| HEARTBEAT_ACK | trả lời | ts |

### Dashboard status feed (WS `ws://host:9444/`, feed `status/1`)
| Type | Payload |
|---|---|
| STATUS_SUBSCRIBE | buildingId (RBAC: chặn EMPLOYEE/DEVICE, scope theo grant) |
| STATUS_SNAPSHOT | buildingId, floors[{floorNumber, zones[{zoneCode, devicesTotal, online, received, acknowledged, failed, severity}]}], activeAlerts[], metrics{connected,p50,p95,p99,retryCount} |
| STATUS_DELTA | floors thay đổi một phần, activeAlerts?, metrics? (merge) |
| INCIDENT_EVENT | incidentId, eventKind, actorId, correlationId, payload |
| METRIC_SAMPLE | metricName, value, unit, tags |

## 4. Ví dụ (từ protocol_examples.json)
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

## 5. Mã lỗi
`AUTH_REQUIRED, ACCESS_DENIED, INVALID_SCOPE, INVALID_FRAME, INVALID_VERSION, MESSAGE_TOO_LARGE, RATE_LIMITED, CONFLICT, EXPIRED, SERVER_BUSY, INTERNAL_ERROR, NOT_FOUND, PRE_AUTH_EXPIRED, FORBIDDEN`.

## 6. Quy ước ngữ nghĩa
- **ACK 2 tầng**: `ACK_RECEIVED` (client lưu durable) ≠ `ACK_USER` (người bấm). Không suy ra lẫn nhau.
- **Idempotency**: CREATE dùng `requestId` (UNIQUE); mỗi delivery có `deliveryId` ổn định + `alertId/revision`; mỗi attempt `messageId` mới. Duplicate ACK harmless (ON CONFLICT DO NOTHING).
- **Retry**: chờ ACK 3s/6s/12s + jitter, tối đa 3 retry rồi FAILED. ACK chỉ tiến trạng thái (rank guard), không lùi.
- **Sync**: reconnect gửi `knownRevisions`; server trả active latest revision + tombstone; alert hủy/hết hạn không hồi sinh.
- **Heartbeat**: 10s; suspect sau 3 miss; client-initiated HEARTBEAT + server HEARTBEAT_ACK.
- **Priority**: CRITICAL > EMERGENCY > WARNING > INFO (application scheduling, fairness burst 8).
- **Multicast/UDP**: chỉ hint, không phải lệnh tin cậy; client fetch nội dung qua TLS trước khi hiển thị.
