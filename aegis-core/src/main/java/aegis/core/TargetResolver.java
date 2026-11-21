package aegis.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot resolving target deviceIds by building, floor, or zone.
 *
 * <p>The device collection is copied and indexed once at construction; later mutation of
 * the source has no effect. Query results are sorted, deduplicated, unmodifiable lists;
 * unknown scopes yield an empty list. A deviceId registered twice with the same location
 * is collapsed; conflicting locations for one deviceId throw {@link IllegalArgumentException}.
 * Devices with a null zone appear in building/floor results but in no zone result.
 */
public final class TargetResolver {

    private final Map<String, List<String>> byBuilding;
    private final Map<String, List<String>> byFloor;
    private final Map<String, List<String>> byZone;
    private final int deviceCount;

    public TargetResolver(Collection<DeviceLocation> devices) {
        Objects.requireNonNull(devices, "devices");
        Map<String, DeviceLocation> registry = new LinkedHashMap<>();
        for (DeviceLocation device : devices) {
            Objects.requireNonNull(device, "device");
            DeviceLocation previous = registry.putIfAbsent(device.deviceId(), device);
            if (previous != null && !previous.equals(device)) {
                throw new IllegalArgumentException(
                        "conflicting locations for device " + device.deviceId() + ": " + previous + " vs " + device);
            }
        }
        Map<String, List<String>> buildings = new HashMap<>();
        Map<String, List<String>> floors = new HashMap<>();
        Map<String, List<String>> zones = new HashMap<>();
        for (DeviceLocation device : registry.values()) {
            buildings.computeIfAbsent(device.buildingId(), k -> new ArrayList<>()).add(device.deviceId());
            floors.computeIfAbsent(key(device.buildingId(), device.floorId()), k -> new ArrayList<>())
                    .add(device.deviceId());
            if (device.zoneId() != null) {
                zones.computeIfAbsent(key(device.buildingId(), device.floorId(), device.zoneId()),
                        k -> new ArrayList<>()).add(device.deviceId());
            }
        }
        this.byBuilding = freeze(buildings);
        this.byFloor = freeze(floors);
        this.byZone = freeze(zones);
        this.deviceCount = registry.size();
    }

    public static TargetResolver of(Collection<DeviceLocation> devices) {
        return new TargetResolver(devices);
    }

    public List<String> resolveBuilding(String buildingId) {
        Objects.requireNonNull(buildingId, "buildingId");
        return byBuilding.getOrDefault(buildingId, List.of());
    }

    public List<String> resolveFloor(String buildingId, String floorId) {
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(floorId, "floorId");
        return byFloor.getOrDefault(key(buildingId, floorId), List.of());
    }

    public List<String> resolveZone(String buildingId, String floorId, String zoneId) {
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(floorId, "floorId");
        Objects.requireNonNull(zoneId, "zoneId");
        return byZone.getOrDefault(key(buildingId, floorId, zoneId), List.of());
    }

    /** Number of distinct devices in the snapshot. */
    public int deviceCount() {
        return deviceCount;
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> source) {
        Map<String, List<String>> frozen = new HashMap<>();
        source.forEach((key, ids) -> {
            Collections.sort(ids);
            frozen.put(key, List.copyOf(ids));
        });
        return Map.copyOf(frozen);
    }

    private static String key(String... parts) {
        return String.join("\u0000", parts);
    }
}
