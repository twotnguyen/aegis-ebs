package aegis.core;

import aegis.protocol.Role;

import java.util.Objects;

/**
 * Scope-based publish authorization (pure logic).
 *
 * <ul>
 *   <li>{@link Role#SYSTEM_ADMIN}: may publish anywhere.</li>
 *   <li>{@link Role#SECURITY_OPERATOR}: only within a granted building (any floor of it).</li>
 *   <li>{@link Role#FLOOR_MANAGER}: only within a granted floor of the granted building.</li>
 *   <li>{@link Role#EMPLOYEE}, {@link Role#DEVICE}: never publish.</li>
 * </ul>
 *
 * <p>Null grants are treated as {@link Grants#none()}; null role/buildingId/floorId throw
 * {@link NullPointerException}.
 */
public final class ScopedRBAC {

    public boolean canPublish(Role role, Grants grants, String buildingId, String floorId) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(floorId, "floorId");
        Grants scope = grants == null ? Grants.none() : grants;
        return switch (role) {
            case SYSTEM_ADMIN -> true;
            case SECURITY_OPERATOR -> scope.coversBuilding(buildingId);
            case FLOOR_MANAGER -> scope.coversFloor(buildingId, floorId);
            case EMPLOYEE, DEVICE -> false;
        };
    }
}
