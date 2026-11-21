-- AEGIS v2 additive migration. Apply ONCE after 001_base_schema.sql.
-- App enforces cross-building/zone consistency and chronological invariants.
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS zones (
  id TEXT PRIMARY KEY,
  floor_id TEXT NOT NULL REFERENCES floors(id),
  zone_code TEXT NOT NULL,
  label TEXT NOT NULL,
  display_x REAL,
  display_y REAL,
  UNIQUE(floor_id, zone_code)
);
CREATE TABLE IF NOT EXISTS zone_devices (
  zone_id TEXT NOT NULL REFERENCES zones(id),
  device_id TEXT NOT NULL REFERENCES devices(id),
  assigned_at TEXT NOT NULL,
  PRIMARY KEY(zone_id,device_id)
);
CREATE INDEX IF NOT EXISTS ix_zone_devices_device ON zone_devices(device_id);
CREATE TABLE IF NOT EXISTS incidents (
  id TEXT PRIMARY KEY,
  building_id TEXT NOT NULL REFERENCES buildings(id),
  opened_by TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL,
  summary TEXT NOT NULL,
  simulation INTEGER NOT NULL DEFAULT 1 CHECK(simulation IN (0,1)),
  state TEXT NOT NULL CHECK(state IN ('DRAFT','OPEN','INVESTIGATING','CONTAINED','RESOLVED','CLOSED','ABORTED')),
  opened_at TEXT NOT NULL,
  closed_at TEXT
);
CREATE INDEX IF NOT EXISTS ix_incidents_state ON incidents(state,opened_at);
CREATE TABLE IF NOT EXISTS incident_events (
  id TEXT PRIMARY KEY,
  incident_id TEXT NOT NULL REFERENCES incidents(id),
  occurred_at TEXT NOT NULL,
  event_kind TEXT NOT NULL,
  actor_id TEXT,
  correlation_id TEXT,
  payload_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_incident_events_timeline ON incident_events(incident_id,occurred_at);
CREATE TABLE IF NOT EXISTS incident_alerts (
  incident_id TEXT NOT NULL REFERENCES incidents(id),
  alert_id TEXT NOT NULL UNIQUE REFERENCES alerts(id),
  PRIMARY KEY(incident_id,alert_id)
);
CREATE TABLE IF NOT EXISTS alert_revisions (
  alert_id TEXT NOT NULL REFERENCES alerts(id),
  revision INTEGER NOT NULL CHECK(revision >= 1),
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  metadata_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  created_by TEXT REFERENCES users(id),
  PRIMARY KEY(alert_id,revision)
);
CREATE TABLE IF NOT EXISTS alert_templates (
  id TEXT PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  category TEXT NOT NULL,
  priority TEXT NOT NULL CHECK(priority IN ('INFO','WARNING','EMERGENCY','CRITICAL')),
  enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1))
);
CREATE TABLE IF NOT EXISTS alert_template_locales (
  template_id TEXT NOT NULL REFERENCES alert_templates(id),
  locale TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  PRIMARY KEY(template_id,locale)
);
CREATE TABLE IF NOT EXISTS alert_operations (
  id TEXT PRIMARY KEY,
  alert_id TEXT NOT NULL REFERENCES alerts(id),
  revision INTEGER NOT NULL,
  operation_kind TEXT NOT NULL CHECK(operation_kind IN ('PUBLISH','UPDATE','CANCEL')),
  requested_by TEXT NOT NULL REFERENCES users(id),
  requested_at TEXT NOT NULL,
  request_id TEXT NOT NULL UNIQUE,
  payload_hash TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('PENDING','IN_PROGRESS','DONE','FAILED'))
);
CREATE TABLE IF NOT EXISTS operation_receipts (
  operation_id TEXT NOT NULL REFERENCES alert_operations(id),
  device_id TEXT NOT NULL REFERENCES devices(id),
  acknowledged_at TEXT,
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
  last_error TEXT,
  PRIMARY KEY(operation_id,device_id)
);
CREATE INDEX IF NOT EXISTS ix_operation_receipts_pending ON operation_receipts(operation_id,acknowledged_at);
CREATE TABLE IF NOT EXISTS network_fault_runs (
  id TEXT PRIMARY KEY,
  scenario_name TEXT NOT NULL,
  random_seed INTEGER NOT NULL,
  began_at TEXT NOT NULL,
  ended_at TEXT,
  git_commit TEXT,
  environment_json TEXT NOT NULL,
  result_json TEXT
);
CREATE TABLE IF NOT EXISTS network_fault_events (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES network_fault_runs(id),
  occurred_at TEXT NOT NULL,
  fault_kind TEXT NOT NULL,
  device_id TEXT REFERENCES devices(id),
  params_json TEXT NOT NULL,
  outcome TEXT
);
CREATE INDEX IF NOT EXISTS ix_fault_events_run ON network_fault_events(run_id,occurred_at);
CREATE TABLE IF NOT EXISTS metrics_samples (
  id TEXT PRIMARY KEY,
  recorded_at TEXT NOT NULL,
  run_id TEXT REFERENCES network_fault_runs(id),
  incident_id TEXT REFERENCES incidents(id),
  metric_name TEXT NOT NULL,
  metric_value REAL NOT NULL,
  unit TEXT,
  tags_json TEXT
);
CREATE INDEX IF NOT EXISTS ix_metrics_name_time ON metrics_samples(metric_name,recorded_at);
CREATE TABLE IF NOT EXISTS demo_scenarios (
  id TEXT PRIMARY KEY,
  scenario_key TEXT NOT NULL UNIQUE,
  label TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
  definition_json TEXT NOT NULL
);
