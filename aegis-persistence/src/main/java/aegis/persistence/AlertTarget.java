package aegis.persistence;

/** alert_targets row: device snapshot for one alert revision. */
public record AlertTarget(String id, String alertId, int revision, String deviceId) {}
