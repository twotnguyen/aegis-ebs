package aegis.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TargetResolverTest {

    private static TargetResolver resolver() {
        return new TargetResolver(List.of(
                new DeviceLocation("d1", "B1", "F1", "Z1"),
                new DeviceLocation("d2", "B1", "F1", "Z2"),
                new DeviceLocation("d3", "B1", "F2", "Z1"),
                new DeviceLocation("d4", "B2", "F1", "Z1"),
                new DeviceLocation("d5", "B1", "F1", null)));
    }

    @Test
    void resolveBuildingListsAllDevices() {
        assertEquals(List.of("d1", "d2", "d3", "d5"), resolver().resolveBuilding("B1"));
        assertEquals(List.of("d4"), resolver().resolveBuilding("B2"));
    }

    @Test
    void resolveFloorFiltersByBuildingAndFloor() {
        assertEquals(List.of("d1", "d2", "d5"), resolver().resolveFloor("B1", "F1"));
        assertEquals(List.of("d3"), resolver().resolveFloor("B1", "F2"));
    }

    @Test
    void resolveZoneFilters() {
        assertEquals(List.of("d1"), resolver().resolveZone("B1", "F1", "Z1"));
        assertEquals(List.of("d2"), resolver().resolveZone("B1", "F1", "Z2"));
    }

    @Test
    void zoneNamesAreScopedToFloorAndBuilding() {
        assertEquals(List.of("d3"), resolver().resolveZone("B1", "F2", "Z1"));
        assertEquals(List.of("d4"), resolver().resolveZone("B2", "F1", "Z1"));
    }

    @Test
    void unknownScopesReturnEmptyList() {
        TargetResolver r = resolver();
        assertTrue(r.resolveBuilding("BX").isEmpty());
        assertTrue(r.resolveFloor("B1", "F9").isEmpty());
        assertTrue(r.resolveZone("B1", "F1", "Z9").isEmpty());
    }

    @Test
    void deviceWithoutZoneIsInBuildingAndFloorOnly() {
        TargetResolver r = resolver();
        assertTrue(r.resolveBuilding("B1").contains("d5"));
        assertTrue(r.resolveFloor("B1", "F1").contains("d5"));
        assertFalse(r.resolveZone("B1", "F1", "Z1").contains("d5"));
        assertFalse(r.resolveZone("B1", "F1", "Z2").contains("d5"));
    }

    @Test
    void resultsAreUnmodifiable() {
        TargetResolver r = resolver();
        assertThrows(UnsupportedOperationException.class, () -> r.resolveBuilding("B1").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> r.resolveFloor("B1", "F1").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> r.resolveZone("B1", "F1", "Z1").add("x"));
    }

    @Test
    void snapshotIgnoresLaterInputMutation() {
        List<DeviceLocation> source = new ArrayList<>(List.of(
                new DeviceLocation("d1", "B1", "F1", "Z1"),
                new DeviceLocation("d2", "B1", "F1", "Z2")));
        TargetResolver r = new TargetResolver(source);
        source.add(new DeviceLocation("d9", "B1", "F1", "Z1"));
        source.clear();
        assertEquals(List.of("d1", "d2"), r.resolveBuilding("B1"));
        assertEquals(2, r.deviceCount());
    }

    @Test
    void resultsAreSortedDeterministically() {
        TargetResolver r = new TargetResolver(List.of(
                new DeviceLocation("d3", "B1", "F2", "Z1"),
                new DeviceLocation("d1", "B1", "F1", "Z1"),
                new DeviceLocation("d5", "B1", "F1", null),
                new DeviceLocation("d2", "B1", "F1", "Z2")));
        assertEquals(List.of("d1", "d2", "d3", "d5"), r.resolveBuilding("B1"));
    }

    @Test
    void duplicateDeviceSameLocationDeduplicated() {
        DeviceLocation d1 = new DeviceLocation("d1", "B1", "F1", "Z1");
        TargetResolver r = new TargetResolver(List.of(d1, d1, new DeviceLocation("d2", "B1", "F1", "Z1")));
        assertEquals(List.of("d1", "d2"), r.resolveZone("B1", "F1", "Z1"));
        assertEquals(2, r.deviceCount());
    }

    @Test
    void sameFloorMultiZoneMembershipIndexedInEveryZone() {
        TargetResolver r = new TargetResolver(List.of(
                new DeviceLocation("d1", "B1", "F1", "Z1"),
                new DeviceLocation("d1", "B1", "F1", "Z2")));
        assertEquals(List.of("d1"), r.resolveZone("B1", "F1", "Z1"));
        assertEquals(List.of("d1"), r.resolveZone("B1", "F1", "Z2"));
        assertEquals(List.of("d1"), r.resolveFloor("B1", "F1"));
        assertEquals(1, r.deviceCount());
    }

    @Test
    void conflictingDeviceLocationsRejected() {
        List<DeviceLocation> conflicting = List.of(
                new DeviceLocation("d1", "B1", "F1", "Z1"),
                new DeviceLocation("d1", "B2", "F1", "Z1"));
        assertThrows(IllegalArgumentException.class, () -> new TargetResolver(conflicting));
    }

    @Test
    void nullQueryArgumentsRejected() {
        TargetResolver r = resolver();
        assertThrows(NullPointerException.class, () -> r.resolveBuilding(null));
        assertThrows(NullPointerException.class, () -> r.resolveFloor(null, "F1"));
        assertThrows(NullPointerException.class, () -> r.resolveFloor("B1", null));
        assertThrows(NullPointerException.class, () -> r.resolveZone("B1", "F1", null));
    }

    @Test
    void deviceCountReportsDistinctDevices() {
        assertEquals(5, resolver().deviceCount());
    }

    @Test
    void nullLocationFieldsRejected() {
        assertThrows(NullPointerException.class, () -> new DeviceLocation(null, "B1", "F1", "Z1"));
        assertThrows(NullPointerException.class, () -> new DeviceLocation("d1", null, "F1", "Z1"));
        assertThrows(NullPointerException.class, () -> new DeviceLocation("d1", "B1", null, "Z1"));
        // zoneId is optional
        assertDoesNotThrow(() -> new DeviceLocation("d1", "B1", "F1", null));
        assertThrows(NullPointerException.class, () -> new TargetResolver(null));
    }
}
