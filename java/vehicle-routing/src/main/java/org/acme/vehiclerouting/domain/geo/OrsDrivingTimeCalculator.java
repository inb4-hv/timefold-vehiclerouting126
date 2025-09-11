package org.acme.vehiclerouting.domain.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.Visit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the driving-time map by calling /ors/v2/directions (pairwise).
 * Highway usage per leg follows your rule:
 *   - visit -> X : use source visit.allowHighways
 *   - depot -> visit : use destination visit.allowHighways
 *   - visit -> depot : use source visit.allowHighways
 */
public final class OrsDrivingTimeCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String profile;

    // Simple cache so repeated imports don’t hammer ORS.
    // Key format: "<fromLat,fromLon>-><toLat,toLon>|avoid=<true|false>"
    private static final Map<String, Long> DURATION_CACHE_SEC = new ConcurrentHashMap<>();

    public OrsDrivingTimeCalculator(String baseUrl, String profile) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.profile = profile;
    }

    /**
     * Initialize time maps using pairwise /directions calls and the allowHighways rule.
     */
    public void initDrivingTimeMapsForPlan(List<Vehicle> vehicles, List<Visit> visits) {
        // Build location lists and helper maps
        List<Location> allLocs = new ArrayList<>(vehicles.size() + visits.size());
        Map<Location, Visit> locToVisit = new HashMap<>();
        Set<Location> depotLocs = new HashSet<>();

        for (Vehicle v : vehicles) {
            Location dep = v.getHomeLocation();
            allLocs.add(dep);
            depotLocs.add(dep);
        }
        for (Visit vi : visits) {
            Location lo = vi.getLocation();
            allLocs.add(lo);
            locToVisit.put(lo, vi);
        }

        // Fill per-location driving maps
        for (Location from : allLocs) {
            Map<Location, Long> map = new HashMap<>(allLocs.size());
            for (Location to : allLocs) {
                long sec;
                boolean usedHighway;

                if (from == to) {
                    sec = 0L;
                    usedHighway = false;
                } else {
                    boolean avoidHighways = computeAvoidHighways(from, to, locToVisit, depotLocs);
                    usedHighway = !avoidHighways;
                    sec = fetchDurationSec(from, to, avoidHighways);
                }

                map.put(to, sec);
                HighwayUsageRegistry.mark(from, to, usedHighway);
            }
            from.setDrivingTimeSeconds(map);
        }
    }

    /**
     * Rule:
     *  - If FROM is a visit: use FROM.allowHighways
     *  - else (FROM is depot) and TO is a visit: use TO.allowHighways
     *  - else default to no-highways
     */
    private static boolean computeAvoidHighways(Location from,
                                                Location to,
                                                Map<Location, Visit> locToVisit,
                                                Set<Location> depotLocs) {
        Visit fromVisit = locToVisit.get(from);
        if (fromVisit != null) {
            return !fromVisit.isAllowHighways();
        }
        Visit toVisit = locToVisit.get(to);
        if (toVisit != null) {
            return !toVisit.isAllowHighways();
        }
        // depot -> depot (irrelevant in practice); avoid highways by default
        return true;
    }

    private long fetchDurationSec(Location from, Location to, boolean avoidHighways) {
        String key = key(from, to, avoidHighways);
        Long cached = DURATION_CACHE_SEC.get(key);
        if (cached != null) return cached;

        try {
            long sec = callDirectionsSeconds(from, to, avoidHighways);
            DURATION_CACHE_SEC.put(key, sec);
            return sec;
        } catch (Exception e) {
            // Fallback to Haversine time so we never produce the 10h sentinel.
            long fallback = HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
            DURATION_CACHE_SEC.put(key, fallback);
            return fallback;
        }
    }

    private long callDirectionsSeconds(Location from, Location to, boolean avoidHighways) throws Exception {
        String url = baseUrl + "/ors/v2/directions/" + profile + "?format=geojson";

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode coords = root.putArray("coordinates");
        ArrayNode p1 = coords.addArray();
        p1.add(from.getLongitude());
        p1.add(from.getLatitude());
        ArrayNode p2 = coords.addArray();
        p2.add(to.getLongitude());
        p2.add(to.getLatitude());

        root.put("preference", "fastest");
        root.put("maximum_speed", 85);
        root.put("instructions", false);

        ObjectNode options = root.putObject("options");
        if (avoidHighways) {
            ArrayNode avoid = options.putArray("avoid_features");
            avoid.add("highways");
        }

        String json = MAPPER.writeValueAsString(root);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("ORS directions HTTP " + resp.statusCode());
        }

        JsonNode r = MAPPER.readTree(resp.body());
        JsonNode features = r.get("features");
        if (features == null || !features.isArray() || features.size() == 0) {
            throw new IllegalStateException("ORS directions response missing features.");
        }
        JsonNode summary = features.get(0).path("properties").path("summary");
        double durationSec = summary.path("duration").asDouble(Double.NaN);
        if (Double.isNaN(durationSec)) {
            throw new IllegalStateException("ORS directions response missing duration.");
        }
        // You could also read distance if needed:
        // double distance = summary.path("distance").asDouble();

        return Math.round(durationSec);
    }

    private static String key(Location from, Location to, boolean avoid) {
        return from.toString() + "->" + to.toString() + "|avoid=" + avoid;
    }
}
