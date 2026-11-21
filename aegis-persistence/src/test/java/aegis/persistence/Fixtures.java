package aegis.persistence;

import aegis.protocol.Priority;
import aegis.protocol.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Deterministic sample data for persistence tests. */
final class Fixtures {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T07:00:00Z"), ZoneOffset.UTC);
  static final Instant NOW = CLOCK.instant();

  private Fixtures() {}

  record Base(Db db, String buildingId, String floorId, String operatorId, List<String> deviceIds) {}

  /** Building -> floor -> operator user -> N enrolled devices, via repos where possible. */
  static Base base(Db db, int deviceCount) throws SQLException {
    String buildingId = UUID.randomUUID().toString();
    String floorId = UUID.randomUUID().toString();
    try (Connection c = db.open(); Statement s = c.createStatement()) {
      s.executeUpdate("INSERT INTO buildings(id,code,name) VALUES('" + buildingId + "','B-"
          + buildingId.substring(0, 8) + "','HQ')");
      s.executeUpdate("INSERT INTO floors(id,building_id,floor_number,label) VALUES('" + floorId + "','"
          + buildingId + "',1,'Ground')");
    }
    User op = new UserRepo(db, CLOCK).create("operator", "argon2-hash", Role.SECURITY_OPERATOR);
    DeviceRepo devices = new DeviceRepo(db, CLOCK);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < deviceCount; i++) {
      ids.add(devices.enroll(floorId, "DEV-" + i, "cred-hash-" + i).id());
    }
    return new Base(db, buildingId, floorId, op.id(), List.copyOf(ids));
  }

  /** Seed every one of the 26 tables with FK-consistent sample rows. */
  static void seedAll(Db db) throws SQLException {
    Instant now = NOW;
    Instant later = now.plusSeconds(3600);
    String b = UUID.randomUUID().toString();
    String f = UUID.randomUUID().toString();
    String u1 = UUID.randomUUID().toString();
    String u2 = UUID.randomUUID().toString();
    String d1 = UUID.randomUUID().toString();
    String a = UUID.randomUUID().toString();
    String del = UUID.randomUUID().toString();
    String z = UUID.randomUUID().toString();
    String inc = UUID.randomUUID().toString();
    String tpl = UUID.randomUUID().toString();
    String op = UUID.randomUUID().toString();
    String run = UUID.randomUUID().toString();

    try (Connection c = db.open()) {
      // 001 base (12 tables)
      ins(c, "buildings", "id,code,name", b, "B1", "HQ");
      ins(c, "floors", "id,building_id,floor_number,label", f, b, 1, "Ground");
      ins(c, "users", "id,username,password_hash,role,enabled,created_at",
          u1, "admin", "hash1", "SYSTEM_ADMIN", 1, now);
      ins(c, "users", "id,username,password_hash,role,enabled,created_at",
          u2, "operator", "hash2", "SECURITY_OPERATOR", 1, now);
      ins(c, "role_grants", "id,user_id,building_id,floor_id,scope,granted_at",
          UUID.randomUUID(), u2, b, f, "FLOOR", now);
      ins(c, "devices", "id,floor_id,device_code,credential_hash,enabled,enrolled_at",
          d1, f, "DEV-1", "cred", 1, now);
      ins(c, "alerts", "id,building_id,created_by,category,priority,state,current_revision,created_at,expires_at",
          a, b, u2, "FIRE", "EMERGENCY", "ACTIVE", 1, now, later);
      ins(c, "alert_targets", "id,alert_id,revision,device_id", UUID.randomUUID(), a, 1, d1);
      ins(c, "deliveries", "id,alert_id,revision,device_id,state,next_retry_at,attempt_count,created_at",
          del, a, 1, d1, "RECEIVED", null, 1, now);
      ins(c, "delivery_attempts", "id,delivery_id,message_id,attempted_at,outcome",
          UUID.randomUUID(), del, "msg-1", now, "OK");
      ins(c, "device_acks", "id,device_id,alert_id,revision,delivery_id,ack_kind,acked_at,user_id",
          UUID.randomUUID(), d1, a, 1, del, "RECEIVED", now, null);
      ins(c, "audit_events", "id,occurred_at,actor_id,action,scope,outcome,correlation_id,payload_json",
          UUID.randomUUID(), now, u2, "ALERT_PUBLISH", "BUILDING:" + b, "OK", "corr-1", "{}");
      ins(c, "alert_approvals", "id,alert_id,approver_id,decision,decided_at",
          UUID.randomUUID(), a, u1, "APPROVE", now);
      // 002 expansion (14 tables)
      ins(c, "zones", "id,floor_id,zone_code,label,display_x,display_y", z, f, "Z1", "Zone 1", 0.0, 0.0);
      ins(c, "zone_devices", "zone_id,device_id,assigned_at", z, d1, now);
      ins(c, "incidents", "id,building_id,opened_by,kind,summary,simulation,state,opened_at,closed_at",
          inc, b, u2, "FIRE", "Smoke on floor 1", 1, "OPEN", now, null);
      ins(c, "incident_events", "id,incident_id,occurred_at,event_kind,actor_id,correlation_id,payload_json",
          UUID.randomUUID(), inc, now, "OPENED", u2, "corr-1", "{}");
      ins(c, "incident_alerts", "incident_id,alert_id", inc, a);
      ins(c, "alert_revisions", "alert_id,revision,title,body,metadata_json,created_at,created_by",
          a, 1, "Fire", "Evacuate now", "{}", now, u2);
      ins(c, "alert_templates", "id,code,category,priority,enabled", tpl, "FIRE_EVAC", "FIRE", "EMERGENCY", 1);
      ins(c, "alert_template_locales", "template_id,locale,title,body", tpl, "vi", "Chay", "So tan ngay");
      ins(c, "alert_operations", "id,alert_id,revision,operation_kind,requested_by,requested_at,request_id,payload_hash,state",
          op, a, 1, "PUBLISH", u2, now, "req-seed-1", "hash", "DONE");
      ins(c, "operation_receipts", "operation_id,device_id,acknowledged_at,attempt_count,last_error",
          op, d1, now, 1, null);
      ins(c, "network_fault_runs", "id,scenario_name,random_seed,began_at,ended_at,git_commit,environment_json,result_json",
          run, "loss-30", 42, now, null, "abc123", "{}", null);
      ins(c, "network_fault_events", "id,run_id,occurred_at,fault_kind,device_id,params_json,outcome",
          UUID.randomUUID(), run, now, "PACKET_LOSS", d1, "{\"p\":0.3}", "OK");
      ins(c, "metrics_samples", "id,recorded_at,run_id,incident_id,metric_name,metric_value,unit,tags_json",
          UUID.randomUUID(), now, run, inc, "delivery_latency_ms", 123.4, "ms", "{}");
      ins(c, "demo_scenarios", "id,scenario_key,label,enabled,definition_json",
          UUID.randomUUID(), "fire-drill", "Fire drill", 1, "{}");
    }
  }

  private static void ins(Connection c, String table, String cols, Object... vals) throws SQLException {
    String placeholders = String.join(",", Collections.nCopies(vals.length, "?"));
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO " + table + "(" + cols + ") VALUES(" + placeholders + ")")) {
      for (int i = 0; i < vals.length; i++) {
        Object v = vals[i];
        if (v instanceof Instant t) ps.setString(i + 1, Sql.ts(t));
        else if (v instanceof UUID u) ps.setString(i + 1, u.toString());
        else if (v instanceof Boolean x) ps.setInt(i + 1, x ? 1 : 0);
        else if (v instanceof Integer n) ps.setInt(i + 1, n);
        else if (v instanceof Double x) ps.setDouble(i + 1, x);
        else ps.setString(i + 1, (String) v);
      }
      ps.executeUpdate();
    }
  }
}
