package org.acme.vehiclerouting.domain.geo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.acme.vehiclerouting.domain.Location;

public final class HighwayUsageRegistry {

    private static final Map<String, Boolean> USED_HIGHWAY = new ConcurrentHashMap<>();

    private HighwayUsageRegistry() {}

    private static String key(Location from, Location to) {
        // Location.toString() is "lat,lon", good enough as a key
        return from.toString() + "->" + to.toString();
    }

    public static void mark(Location from, Location to, boolean usedHighway) {
        USED_HIGHWAY.put(key(from, to), usedHighway);
    }

    public static boolean usedHighway(Location from, Location to) {
        return USED_HIGHWAY.getOrDefault(key(from, to), false);
    }

    public static void clear() {
        USED_HIGHWAY.clear();
    }
}
