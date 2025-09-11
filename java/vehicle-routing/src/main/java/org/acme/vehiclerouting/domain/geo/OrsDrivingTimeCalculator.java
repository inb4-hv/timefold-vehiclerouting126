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
 * - INFO logs are emitted so you can see them in Docker without changing Quarkus log level.
 */
public final class OrsDrivingTimeCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(OrsDrivingTimeCalculator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;     // e.g. http://ors-app:8082
    private final String profile;     // e.g. driving-car
    private final String apiKey;      // optional
    private final boolean debug;      // if true, log every edge
    private final int logFirst;       // log the first N edges
    private final int logEvery;       // or log every Nth edge
    private final int timeoutSec;

    private final HttpClient client;

    // Cache: "<fromLat,fromLon>-><toLat,toLon>|avoid=<true|false>"
    private static final Map<String, Long> DURATION_CACHE_SEC = new ConcurrentHashMap<>();

    public OrsDrivingTimeCalculator(String baseUrl, String profile) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.profile = profile;
        this.apiKey = System.getenv().getOrDefault("APP_ORS_API_KEY", "");
        this.debug = Boolean.parseBoolean(System.getenv().getOrDefault("APP_ORS_DEBUG", "true"));
        this.logFirst = parseIntEnv("APP_ORS_LOG_FIRST", 12);
        this.logEvery = parseIntEnv("APP_ORS_LOG_EVERY", 0); // 0 = off
        this.timeoutSec = parseIntEnv("APP_ORS_TIMEOUT_SEC", 20);

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, timeoutSec / 2)))
                .build();
    }

    private static int parseIntEnv(String k, int def) {
        try { return Integer.parseInt(System.getenv().getOrDefault(k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    /** Build both maps and store them on each Location. */
    public void initDrivingTimeMapsForPlan(List<Vehicle> vehicles, List<Visit> visits) {
        // Unique set of locations (vehicles may share a depot).
        Set<Location> all = new LinkedHashSet<>();
        for (Vehicle v : vehicles) all.add(v.getHomeLocation());
        for (Visit vi : visits)   all.add(vi.getLocation());
        List<Location> locs = new ArrayList<>(all);

        final long totalPairs = (long) locs.size() * (long) locs.size();
        LOG.info("ORS init: baseUrl='{}', profile='{}', apiKeyPresent={}, timeout={}s, locations={}, pairs={}",
                baseUrl, profile, !apiKey.isEmpty(), timeoutSec, locs.size(), totalPairs);
        LOG.info("ORS logging: debug={}, logFirst={}, logEvery={}", debug, logFirst, logEvery);

        int edgeIndex = 0;
        long orsOk = 0, orsFallback = 0;

        for (Location from : locs) {
            Map<Location, Long> hiMap = new HashMap<>(locs.size());
            Map<Location, Long> noMap = new HashMap<>(locs.size());

            long localOk = 0, localFallback = 0;

            for (Location to : locs) {
                if (from == to) {
                    hiMap.put(to, 0L);
                    noMap.put(to, 0L);
                    HighwayUsageRegistry.mark(from, to, false);
                    continue;
                }

                boolean logThis = debug
                        || edgeIndex < logFirst
                        || (logEvery > 0 && (edgeIndex % logEvery == 0));

                long secHi;
                try {
                    secHi = fetchDurationSec(from, to, false, logThis);
                    localOk++; orsOk++;
                } catch (Throwable t) {
                    secHi = HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
                    localFallback++; orsFallback++;
                    if (logThis) {
                        LOG.warn("ORS->Haversine (highway) {} -> {} : {}s (reason: {})",
                                from, to, secHi, t.toString());
                    }
                }

                long secNo;
                try {
                    secNo = fetchDurationSec(from, to, true, logThis);
                    localOk++; orsOk++;
                } catch (Throwable t) {
                    secNo = HaversineDrivingTimeCalculator.getInstance().calculateDrivingTime(from, to);
                    localFallback++; orsFallback++;
                    if (logThis) {
                        LOG.warn("ORS->Haversine (no-highway) {} -> {} : {}s (reason: {})",
                                from, to, secNo, t.toString());
                    }
                }

                // Marking "usedHighway" here is only for visibility in debug tools.
                HighwayUsageRegistry.mark(from, to, true);

                hiMap.put(to, secHi);
                noMap.put(to, secNo);

                edgeIndex++;
            }

            from.setDrivingTimeSecondsHighway(hiMap);
            from.setDrivingTimeSecondsNoMotorway(noMap);

            LOG.info("ORS per-origin summary: from={} pairs={}, orsOk={}, fallback={}",
                    from, locs.size(), localOk, localFallback);
        }

        LOG.info("ORS global summary: locations={}, pairs={}, orsOkEdges={}, fallbackEdges={}",
                locs.size(), totalPairs, orsOk, orsFallback);
    }

    /** Get seconds for (from,to) with avoidHighways flag; logs at INFO when 'logThis' is true. */
    private long fetchDurationSec(Location from, Location to, boolean avoidHighways, boolean logThis) throws Exception {
        String key = cacheKey(from, to, avoidHighways);
        Long cached = DURATION_CACHE_SEC.get(key);
        if (cached != null) {
            if (logThis) LOG.info("ORS cache hit {} -> {} avoidHighways={} => {}s", from, to, avoidHighways, cached);
            return cached;
        }

        long sec = callDirectionsSeconds(from, to, avoidHighways, logThis);
        DURATION_CACHE_SEC.put(key, sec);

        if (logThis) {
            LOG.info("ORS {} -> {} avoidHighways={} => {}s", from, to, avoidHighways, sec);
        }
        return sec;
    }

    private long callDirectionsSeconds(Location from, Location to, boolean avoidHighways, boolean logThis) throws Exception {
        String url = baseUrl + "/ors/v2/directions/" + profile + "?format=geojson";
        if (!apiKey.isEmpty()) url += "&api_key=" + apiKey;

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
            options.putArray("avoid_features").add("highways");
        }

        String json = MAPPER.writeValueAsString(root);

        if (logThis) {
            LOG.info("ORS request: {} -> {} avoidHighways={} {}", from, to, avoidHighways,
                    apiKey.isEmpty() ? "(no apiKey)" : "(apiKey set)");
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String body = resp.body();
            String shortBody = body == null ? "" : body.substring(0, Math.min(240, body.length()));
            throw new IllegalStateException("HTTP " + resp.statusCode() + " body: " + shortBody);
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
