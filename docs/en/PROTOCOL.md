Language: English | Vietnamese original: docs/PROTOCOL.md

# EBSP/2.0 wire contract (shared — do not change without a version bump)

Framing: `uint32 BE length L` + `L bytes strict UTF-8 JSON`, L in 1..65536. Reject oversized frames BEFORE allocation. Use readFully; handle partial/coalesced reads. Validate UTF-8, JSON, version==2, the type enum, and role-authorized messages.

Envelope: `{version:2, type, messageId, correlationId, sentAt(UTC ISO-8601), payload}`.

Idempotency: CREATE uses requestId; each delivery has a stable deliveryId + alertId + revision; each transport attempt uses a new messageId. ACK only advances state. Cancel/expire tombstones prevent stale redisplay. ACK_RECEIVED (durable local store) != ACK_USER (human gesture).

Retry demo: 3s/6s/12s + jitter, max 3 retries then FAILED. Heartbeat 10s, suspect after 3 missed heartbeats.

Primary channel: TCP/TLS. Multicast/UDP = advisory hint only; the client must fetch verified content over authenticated TLS before display.

Message catalog: see aegis.protocol.MessageType. Fixtures: Plan/protocol_examples.json.
