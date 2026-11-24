package aegis.server;

import aegis.core.Grants;
import aegis.persistence.Db;
import aegis.persistence.MigrationRunner;
import aegis.persistence.RoleGrantRepo;
import aegis.persistence.User;
import aegis.persistence.UserRepo;
import aegis.protocol.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RbacServiceTest {

  @TempDir
  Path tmp;

  private Db db;
  private UserRepo users;
  private RoleGrantRepo grants;
  private RbacService rbac;
  private String floor1;
  private String floor7;

  @BeforeEach
  void setup() throws Exception {
    db = new Db(tmp.resolve("rbac.db"));
    new MigrationRunner(db).migrate();
    users = new UserRepo(db);
    grants = new RoleGrantRepo(db);
    rbac = new RbacService(grants);
    try (var c = db.open(); var s = c.createStatement()) {
      s.execute("INSERT INTO buildings(id,code,name) VALUES('BLD-A','A','Tower A')");
      s.execute("INSERT INTO buildings(id,code,name) VALUES('BLD-B','B','Tower B')");
      s.execute("INSERT INTO floors(id,building_id,floor_number) VALUES('F1','BLD-A',1)");
      s.execute("INSERT INTO floors(id,building_id,floor_number) VALUES('F7','BLD-A',7)");
    }
    floor1 = "F1";
    floor7 = "F7";
  }

  private User user(String name, Role role) throws Exception {
    return users.create(name, "$argon2id$unused", role);
  }

  @Test
  void systemAdminPublishesAnywhere() throws Exception {
    User admin = user("admin", Role.SYSTEM_ADMIN);
    Grants g = rbac.grantsFor(admin.id());
    assertTrue(rbac.canPublish(Role.SYSTEM_ADMIN, g, "BLD-A", floor1));
    assertTrue(rbac.canPublish(Role.SYSTEM_ADMIN, g, "BLD-B", null));
  }

  @Test
  void securityOperatorLimitedToGrantedBuilding() throws Exception {
    User op = user("secops", Role.SECURITY_OPERATOR);
    grants.grant(op.id(), "BLD-A", null, "PUBLISH");
    Grants g = rbac.grantsFor(op.id());
    assertTrue(rbac.canPublish(Role.SECURITY_OPERATOR, g, "BLD-A", floor7));
    assertTrue(rbac.canPublish(Role.SECURITY_OPERATOR, g, "BLD-A", null));
    assertFalse(rbac.canPublish(Role.SECURITY_OPERATOR, g, "BLD-B", null));
  }

  @Test
  void floorManagerLimitedToGrantedFloor() throws Exception {
    User fm = user("floor7", Role.FLOOR_MANAGER);
    grants.grant(fm.id(), "BLD-A", floor7, "PUBLISH");
    Grants g = rbac.grantsFor(fm.id());
    assertTrue(rbac.canPublish(Role.FLOOR_MANAGER, g, "BLD-A", floor7));
    assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, g, "BLD-A", floor1));
    assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, g, "BLD-A", null)); // building-wide denied
    assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, g, "BLD-B", floor7));
  }

  @Test
  void employeeAndDeviceNeverPublish() throws Exception {
    User employee = user("employee", Role.EMPLOYEE);
    grants.grant(employee.id(), "BLD-A", null, "PUBLISH"); // even with a grant row
    Grants g = rbac.grantsFor(employee.id());
    assertFalse(rbac.canPublish(Role.EMPLOYEE, g, "BLD-A", floor1));
    assertFalse(rbac.canPublish(Role.DEVICE, Grants.none(), "BLD-A", floor1));
  }

  @Test
  void viewGrantDoesNotAuthorizePublish() throws Exception {
    User op = user("viewer", Role.SECURITY_OPERATOR);
    grants.grant(op.id(), "BLD-A", null, "VIEW");
    Grants g = rbac.grantsFor(op.id());
    assertFalse(rbac.canPublish(Role.SECURITY_OPERATOR, g, "BLD-A", floor1));
  }

  @Test
  void revokeRemovesScopeOnNextLookup() throws Exception {
    User op = user("revoked", Role.FLOOR_MANAGER);
    var grant = grants.grant(op.id(), "BLD-A", floor7, "PUBLISH");
    assertTrue(rbac.canPublish(Role.FLOOR_MANAGER, rbac.grantsFor(op.id()), "BLD-A", floor7));
    grants.revoke(grant.id());
    assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, rbac.grantsFor(op.id()), "BLD-A", floor7));
  }

  @Test
  void noGrantsMeansNoScope() throws Exception {
    User op = user("lonely", Role.SECURITY_OPERATOR);
    Grants g = rbac.grantsFor(op.id());
    assertFalse(rbac.canPublish(Role.SECURITY_OPERATOR, g, "BLD-A", floor1));
  }
}
