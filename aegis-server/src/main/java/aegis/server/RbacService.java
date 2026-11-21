package aegis.server;

import aegis.core.Grants;
import aegis.core.ScopedRBAC;
import aegis.persistence.RoleGrant;
import aegis.persistence.RoleGrantRepo;
import aegis.protocol.Role;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads durable role_grants into immutable {@link Grants} and enforces
 * {@link ScopedRBAC} on every publish-class command. SYSTEM_ADMIN publishes
 * anywhere; SECURITY_OPERATOR within granted buildings; FLOOR_MANAGER only on
 * granted floors; EMPLOYEE/DEVICE never.
 */
public final class RbacService {

  /** Placeholder floorId for building-wide scope (matches no floor grant). */
  public static final String BUILDING_WIDE_FLOOR = "*";

  private final RoleGrantRepo grantRepo;
  private final ScopedRBAC rbac = new ScopedRBAC();

  public RbacService(RoleGrantRepo grantRepo) {
    this.grantRepo = Objects.requireNonNull(grantRepo);
  }

  public Grants grantsFor(String userId) throws SQLException {
    List<RoleGrant> rows = grantRepo.findByUser(userId);
    Set<String> buildings = new HashSet<>();
    Map<String, Set<String>> floors = new HashMap<>();
    for (RoleGrant g : rows) {
      if (g.buildingId() == null) continue;
      if (g.floorId() == null) {
        buildings.add(g.buildingId());
      } else {
        buildings.add(g.buildingId());
        floors.computeIfAbsent(g.buildingId(), k -> new HashSet<>()).add(g.floorId());
      }
    }
    return Grants.of(buildings, floors);
  }

  public boolean canPublish(Role role, Grants grants, String buildingId, String floorId) {
    return rbac.canPublish(role, grants, buildingId,
        floorId == null ? BUILDING_WIDE_FLOOR : floorId);
  }
}
