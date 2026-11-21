package aegis.core;

import java.util.Objects;

/**
 * Immutable device placement: deviceId at building/floor, optionally inside a zone.
 * {@code zoneId} may be null (device not assigned to any zone); the other fields are required.
 */
public record DeviceLocation(String deviceId, String buildingId, String floorId, String zoneId) {

    public DeviceLocation {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(floorId, "floorId");
    }
}
