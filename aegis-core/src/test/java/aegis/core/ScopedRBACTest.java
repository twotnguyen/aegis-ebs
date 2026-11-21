package aegis.core;

import aegis.protocol.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScopedRBACTest {

    private static final Grants GRANTS =
            Grants.of(List.of("B1"), Map.of("B1", List.of("F1", "F2")));

    private final ScopedRBAC rbac = new ScopedRBAC();

    @Test
    void systemAdminPublishesAnywhere() {
        assertTrue(rbac.canPublish(Role.SYSTEM_ADMIN, Grants.none(), "BX", "FX"));
        assertTrue(rbac.canPublish(Role.SYSTEM_ADMIN, GRANTS, "B1", "F1"));
        assertTrue(rbac.canPublish(Role.SYSTEM_ADMIN, null, "B9", "F9"));
    }

    @Test
    void securityOperatorWithinGrantedBuilding() {
        assertTrue(rbac.canPublish(Role.SECURITY_OPERATOR, GRANTS, "B1", "F1"));
        // Building-wide grant covers every floor of that building, even unlisted ones.
        assertTrue(rbac.canPublish(Role.SECURITY_OPERATOR, GRANTS, "B1", "F99"));
    }

    @Test
    void securityOperatorOutsideGrantedBuildingDenied() {
        assertFalse(rbac.canPublish(Role.SECURITY_OPERATOR, GRANTS, "B2", "F1"));
    }

    @Test
    void securityOperatorNeedsBuildingGrantNotFloorGrant() {
        Grants floorOnly = Grants.of(List.of(), Map.of("B1", List.of("F1")));
        assertFalse(rbac.canPublish(Role.SECURITY_OPERATOR, floorOnly, "B1", "F1"));
    }

    @Test
    void floorManagerWithinGrantedFloor() {
        assertTrue(rbac.canPublish(Role.FLOOR_MANAGER, GRANTS, "B1", "F1"));
        assertTrue(rbac.canPublish(Role.FLOOR_MANAGER, GRANTS, "B1", "F2"));
    }

    @Test
    void floorManagerOtherFloorSameBuildingDenied() {
        assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, GRANTS, "B1", "F3"));
    }

    @Test
    void floorManagerOtherBuildingDenied() {
        assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, GRANTS, "B2", "F1"));
    }

    @Test
    void floorManagerBuildingGrantAloneDenied() {
        Grants buildingOnly = Grants.of(List.of("B1"), Map.of());
        assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, buildingOnly, "B1", "F1"));
    }

    @Test
    void employeeNeverPublishes() {
        assertFalse(rbac.canPublish(Role.EMPLOYEE, GRANTS, "B1", "F1"));
        assertFalse(rbac.canPublish(Role.EMPLOYEE, Grants.of(List.of("B1"), Map.of("B1", List.of("F1"))), "B1", "F1"));
    }

    @Test
    void deviceNeverPublishes() {
        assertFalse(rbac.canPublish(Role.DEVICE, GRANTS, "B1", "F1"));
    }

    @Test
    void nullGrantsDeniesScopedRoles() {
        assertFalse(rbac.canPublish(Role.SECURITY_OPERATOR, null, "B1", "F1"));
        assertFalse(rbac.canPublish(Role.FLOOR_MANAGER, null, "B1", "F1"));
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> rbac.canPublish(null, GRANTS, "B1", "F1"));
        assertThrows(NullPointerException.class, () -> rbac.canPublish(Role.SYSTEM_ADMIN, GRANTS, null, "F1"));
        assertThrows(NullPointerException.class, () -> rbac.canPublish(Role.SYSTEM_ADMIN, GRANTS, "B1", null));
    }

    @Test
    void grantsAreImmutableSnapshot() {
        List<String> buildings = new ArrayList<>(List.of("B1"));
        Map<String, Collection<String>> floors = new HashMap<>();
        floors.put("B1", new ArrayList<>(List.of("F1")));
        Grants grants = Grants.of(buildings, floors);

        buildings.add("B2");
        floors.get("B1").add("F2");
        floors.put("B3", List.of("F1"));

        assertFalse(grants.coversBuilding("B2"));
        assertFalse(grants.coversFloor("B1", "F2"));
        assertFalse(grants.coversFloor("B3", "F1"));
        assertThrows(UnsupportedOperationException.class, () -> grants.buildings().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> grants.floorsByBuilding().put("X", java.util.Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> grants.floorsByBuilding().get("B1").add("X"));
    }
}
