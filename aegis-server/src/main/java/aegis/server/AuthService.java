package aegis.server;

import aegis.persistence.Device;
import aegis.persistence.DeviceRepo;
import aegis.persistence.User;
import aegis.persistence.UserRepo;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator/device authentication against the durable users/devices rows.
 * Credentials are verified with Argon2id; disabled accounts never authenticate.
 */
public final class AuthService {

  private final UserRepo users;
  private final DeviceRepo devices;
  private final PasswordHasher hasher;

  public AuthService(UserRepo users, DeviceRepo devices, PasswordHasher hasher) {
    this.users = Objects.requireNonNull(users);
    this.devices = Objects.requireNonNull(devices);
    this.hasher = Objects.requireNonNull(hasher);
  }

  public Optional<User> authenticateOperator(String username, String password) throws SQLException {
    if (username == null || password == null) return Optional.empty();
    Optional<User> user = users.findByUsername(username);
    if (user.isEmpty() || !user.get().enabled()) return Optional.empty();
    return hasher.verify(password, user.get().passwordHash()) ? user : Optional.empty();
  }

  public Optional<Device> authenticateDevice(String deviceCode, String credential) throws SQLException {
    if (deviceCode == null || credential == null) return Optional.empty();
    Optional<Device> device = devices.findByCode(deviceCode);
    if (device.isEmpty() || !device.get().enabled()) return Optional.empty();
    return hasher.verify(credential, device.get().credentialHash()) ? device : Optional.empty();
  }

  public PasswordHasher hasher() { return hasher; }
}
