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
 * Builds TWO driving-time maps by calling /ors/v2/directions pairwise:
 *  - "highway"   : highways allowed (avoid=false)
 *  - "noMotorway": motorways avoided (avoid=true)
 *
 * The domain (Visit/Vehicle/LocationDistanceMeter) chooses which one to use per leg
 * according to the allowHighways rule you specified.
 */
public final class OrsDrivingTimeCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String profile;

    // Cache per leg to avoid hammering ORS.
    // Key: "<fromLat,fromLon>-><toLat,toLon>|avoid=<true|false>"
    private static final Map<String, Long> DURATION_CACHE_SEC = new ConcurrentHashMap<>();

    public OrsDrivingTimeCalculator(String baseUrl, String profile) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.profile = profile;
    }

    /**
     * Initialize BOTH maps on each Location:
     *  - setDrivingTimeSecondsHighway(...)
     *  - setDrivingTimeSecondsNoMotorway(...)
     *
     * We compute pairwise durations twice (avoid=false/true) so later code can pick
     * the right one per leg.
     */
    public void initDrivingTimeMapsForPlan(List<Vehicle> vehicles, List<Visit> visits) {
        // Collect all distinct locations (depots + visits)
        List<Location> allLocs = new ArrayList<>(vehicles.size() + visits.size());
        for (Vehicle v : vehicles) {
            allLocs.add(v.getHomeLocation());
        }
        for (Visit vi : visits) {
            allLocs.add(vi.getLocation());
        }

        // For every origin, build two maps: highway and no-motorway.
        for (Location from : allLocs) {
            Map<Location, Long> hiMap = new HashMap<>(allLocs.size());
            Map<Location, Long> noMap = new HashMap<>(allLocs.size());

            for (Location to : allLocs) {
                if (from == to) {
                    hiMap.put(to, 0L);
                    noMap.put(to, 0L);
                    continue;
                }

                long tHi = fetchDurationSec(from, to, /*avoidHighways=*/false);
                long tNo = fetchDurationSec(from, to, /*avoidHighways=*/true);

                hiMap.put(to, tHi);
                noMap.put(to, tNo);
            }

            // Assign the two matrices to the location
            from.setDrivingTimeSecondsHighway(hiMap);
            from.setDrivingTimeSecondsNoMotorway(noMap);
        }
    }

    /** Get duration (seconds) for a leg; uses cache and falls back to Haversine on error. */
    private long fetchDurationSec(Location from, Location to, boolean avoidHighways) {
        String key = key(from, to, avoidHighways);
        Long cached = DURATION_CACHE_SEC.get(key);
        if (cached != null) return cached;

        try {
            long sec = callDirectionsSeconds(from, to, avoidHighways);
            DURATION_CACHE_SEC.put(key, sec);
            return sec;
        } catch (Exception e) {
            // Fallback to Haversine so we never produce a sentinel/unusable value.
            long fallback = HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
            DURATION_CACHE_SEC.put(key, fallback);
            return fallback;
        }
    }

    /** Call /ors/v2/directions/{profile}?format=geojson and extract the summary.duration (seconds). */
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

        // options.avoid_features: ["highways"] when avoidHighways == true
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
        return Math.round(durationSec);
    }

    private static String key(Location from, Location to, boolean avoid) {
        return from.toString() + "->" + to.toString() + "|avoid=" + avoid;
    }
}
