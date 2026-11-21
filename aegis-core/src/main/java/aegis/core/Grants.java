package aegis.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable scope grants for publish authorization: a set of granted buildingIds and,
 * per building, a set of granted floorIds. Defensive copies everywhere — mutating the
 * source collections after construction has no effect, and all accessors return
 * unmodifiable views.
 */
public record Grants(Set<String> buildings, Map<String, Set<String>> floorsByBuilding) {

    public Grants {
        buildings = buildings == null ? Set.of() : Set.copyOf(buildings);
        if (floorsByBuilding == null) {
            floorsByBuilding = Map.of();
        } else {
            Map<String, Set<String>> copy = new HashMap<>();
            floorsByBuilding.forEach((building, floors) ->
                    copy.put(Objects.requireNonNull(building, "buildingId"), Set.copyOf(floors)));
            floorsByBuilding = Collections.unmodifiableMap(copy);
        }
    }

    /** Empty grants: nothing is authorized for scoped roles. */
    public static Grants none() {
        return new Grants(Set.of(), Map.of());
    }

    /** Factory taking ordinary collections; nulls are treated as empty. */
    public static Grants of(Collection<String> buildings, Map<String, ? extends Collection<String>> floorsByBuilding) {
        Set<String> buildingCopy = buildings == null ? Set.of() : new HashSet<>(buildings);
        Map<String, Set<String>> floorCopy = new HashMap<>();
        if (floorsByBuilding != null) {
            floorsByBuilding.forEach((building, floors) ->
                    floorCopy.put(building, floors == null ? Set.of() : new HashSet<>(floors)));
        }
        return new Grants(buildingCopy, floorCopy);
    }

    public boolean coversBuilding(String buildingId) {
        return buildings.contains(buildingId);
    }

    public boolean coversFloor(String buildingId, String floorId) {
        Set<String> floors = floorsByBuilding.get(buildingId);
        return floors != null && floors.contains(floorId);
    }
}
