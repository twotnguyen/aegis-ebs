package aegis.persistence;

import java.time.Instant;

/** role_grants row. buildingId/floorId may be null for broader scopes. */
public record RoleGrant(String id, String userId, String buildingId, String floorId,
                        String scope, Instant grantedAt) {}
