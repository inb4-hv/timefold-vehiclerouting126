package org.acme.vehiclerouting.domain.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.Visit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pairwise ORS /ors/v2/directions builder:
 * - For each (from,to) builds BOTH durations:
 *     * highwayMap: no avoid
 *     * noMotorwayMap: options.avoid_features:["highways"]
 * - Location stores both maps. Callers choose which to use.
 */
public final class OrsDrivingTimeCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(OrsDrivingTimeCalculator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;   // e.g. http://localhost:8082
    private final String profile;   // e.g. driving-car
    private final String apiKey;    // optional
    private final boolean debug;

    // Small cache to avoid hammering ORS when importing repeatedly.
    // Key: "<fromLat,fromLon>-><toLat,toLon>|avoid=<true|false>"
    private static final Map<String, Long> DURATION_CACHE_SEC = new ConcurrentHashMap<>();

    public OrsDrivingTimeCalculator(String baseUrl, String profile) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.profile = profile;
        this.apiKey = System.getenv().getOrDefault("APP_ORS_API_KEY", "");
        this.debug = Boolean.parseBoolean(System.getenv().getOrDefault("APP_ORS_DEBUG", "false"));
    }

    /** Build both maps and store them on each Location. */
    public void initDrivingTimeMapsForPlan(List<Vehicle> vehicles, List<Visit> visits) {
        // De-dupe the set of all distinct Location instances (vehicles may share a depot).
        Set<Location> all = new LinkedHashSet<>();
        for (Vehicle v : vehicles) all.add(v.getHomeLocation());
        for (Visit vi : visits)   all.add(vi.getLocation());
        List<Location> locs = new ArrayList<>(all);

        int logBudget = debug ? 1000000 : 12; // print only first N pairs unless debug

        for (Location from : locs) {
            Map<Location, Long> hiMap  = new HashMap<>(locs.size());
            Map<Location, Long> noMap  = new HashMap<>(locs.size());

            for (Location to : locs) {
                if (from == to) {
                    hiMap.put(to, 0L);
                    noMap.put(to, 0L);
                    HighwayUsageRegistry.mark(from, to, false);
                    continue;
                }

                long secHi = fetchDurationSec(from, to, false, logBudget--);
                long secNo = fetchDurationSec(from, to, true,  logBudget--);

                // Record highway-ness just for visibility; solver will choose per-leg later.
                HighwayUsageRegistry.mark(from, to, true);

                hiMap.put(to, secHi);
                noMap.put(to, secNo);
            }

            from.setDrivingTimeSecondsHighway(hiMap);
            from.setDrivingTimeSecondsNoMotorway(noMap);
        }
    }

    private long fetchDurationSec(Location from, Location to, boolean avoidHighways, int logBudget) {
        String key = cacheKey(from, to, avoidHighways);
        Long cached = DURATION_CACHE_SEC.get(key);
        if (cached != null) return cached;

        long sec;
        try {
            sec = callDirectionsSeconds(from, to, avoidHighways);
        } catch (Exception e) {
            long fallback = HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
            if (logBudget > 0) {
                LOG.warn("ORS failed ({}) {} -> {} avoidHighways={} ; using Haversine={}s : {}",
                        e.getMessage(), from, to, avoidHighways, fallback, key);
            }
            sec = fallback;
        }
        DURATION_CACHE_SEC.put(key, sec);

        if (logBudget > 0) {
            LOG.info("ORS {} -> {} avoidHighways={} => {}s", from, to, avoidHighways, sec);
        }
        return sec;
    }

    private long callDirectionsSeconds(Location from, Location to, boolean avoidHighways) throws Exception {
        String url = baseUrl + "/ors/v2/directions/" + profile + "?format=geojson";
        if (!apiKey.isEmpty()) {
            url += "&api_key=" + apiKey;
        }

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
            // ORS expects "highways" for directions. (Matrix used "motorway", but directions uses "highways".)
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
            throw new IllegalStateException("HTTP " + resp.statusCode() + " body: " + resp.body());
        }

        JsonNode r = MAPPER.readTree(resp.body());
        JsonNode features = r.get("features");
        if (features == null || !features.isArray() || features.size() == 0) {
            throw new IllegalStateException("Missing features[] in response");
        }
        JsonNode summary = features.get(0).path("properties").path("summary");
        double durationSec = summary.path("duration").asDouble(Double.NaN);
        if (Double.isNaN(durationSec)) {
            throw new IllegalStateException("Missing summary.duration");
        }
        return Math.round(durationSec);
    }

    private static String cacheKey(Location from, Location to, boolean avoid) {
        return from.toString() + "->" + to.toString() + "|avoid=" + avoid;
    }
}
