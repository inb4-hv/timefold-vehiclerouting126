package org.acme.vehiclerouting.domain.geo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration as JDuration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acme.vehiclerouting.domain.Location;

public final class OrsDrivingTimeCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private final String baseUrl;
    private final String profile;

    public OrsDrivingTimeCalculator(String baseUrl, String profile) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.profile = profile;
    }

    public void initDrivingTimeMaps(Collection<Location> locations) {
        if (locations.size() <= 1) {
            locations.forEach(l -> l.setDrivingTimeSeconds(Map.of(l, 0L)));
            return;
        }

        // Build ORS location list [lon, lat]
        List<double[]> coords = new ArrayList<>(locations.size());
        List<Location> indexToLoc = new ArrayList<>(locations.size());
        for (Location l : locations) {
            coords.add(new double[]{ l.getLongitude(), l.getLatitude() });
            indexToLoc.add(l);
        }

        // Two matrices: highway allowed, and avoid motorways
        MatrixResult highway = fetchMatrix(coords, false);
        MatrixResult noMotorway = fetchMatrix(coords, true);

        // Fill per-location maps
        for (int i = 0; i < indexToLoc.size(); i++) {
            Location from = indexToLoc.get(i);
            Map<Location, Long> map = new HashMap<>();
            for (int j = 0; j < indexToLoc.size(); j++) {
                Location to = indexToLoc.get(j);
                long timeSec;
                boolean usedHighway;

                if (i == j) {
                    timeSec = 0L;
                    usedHighway = false;
                } else {
                    // Prefer no-motorway time if it exists
                    Double tNo = noMotorway.duration(i, j);
                    Double dNo = noMotorway.distance(i, j);
                    Double tHi = highway.duration(i, j);
                    Double dHi = highway.distance(i, j);

                    if (tNo != null) {
                        timeSec = adjustedWith85Cap(tNo, dNo);
                        usedHighway = false;
                    } else if (tHi != null) {
                        timeSec = adjustedWith85Cap(tHi, dHi);
                        usedHighway = true;
                    } else {
                        // Fallback defensive zero-reachability → large penalty
                        timeSec = 36000; // 10h fallback
                        usedHighway = false;
                    }
                }
                map.put(to, timeSec);
                HighwayUsageRegistry.mark(from, to, usedHighway);
            }
            indexToLoc.get(i).setDrivingTimeSeconds(map);
        }
    }

    private static long adjustedWith85Cap(double orsSeconds, Double distanceMetersOrNull) {
        if (distanceMetersOrNull == null) return Math.round(orsSeconds);
        double minSecondsAt85 = distanceMetersOrNull / (85.0 * 1000.0 / 3600.0);
        return Math.round(Math.max(orsSeconds, minSecondsAt85));
    }

    private MatrixResult fetchMatrix(List<double[]> coords, boolean avoidMotorways) {
        try {
            String url = baseUrl + "/ors/v2/matrix/" + profile;
            Map<String,Object> body = new HashMap<>();
            body.put("locations", coords);
            body.put("metrics", List.of("duration", "distance"));
            body.put("resolve_locations", false);
            if (avoidMotorways) {
                body.put("avoid_features", List.of("motorway"));
            }

            String json = MAPPER.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode root = MAPPER.readTree(resp.body());
                JsonNode durations = root.get("durations");
                JsonNode distances = root.get("distances");
                return new MatrixResult(durations, distances);
            } else {
                // Return empty MatrixResult → caller will fallback
                return MatrixResult.empty();
            }
        } catch (Exception e) {
            return MatrixResult.empty();
        }
    }

    private static final class MatrixResult {
        private final JsonNode durations;
        private final JsonNode distances;

        private MatrixResult(JsonNode durations, JsonNode distances) {
            this.durations = durations;
            this.distances = distances;
        }

        static MatrixResult empty() {
            return new MatrixResult(null, null);
        }

        Double duration(int i, int j) {
            if (durations == null || durations.isNull()) return null;
            JsonNode row = durations.get(i);
            if (row == null || row.isNull()) return null;
            JsonNode v = row.get(j);
            return (v == null || v.isNull()) ? null : v.asDouble();
        }

        Double distance(int i, int j) {
            if (distances == null || distances.isNull()) return null;
            JsonNode row = distances.get(i);
            if (row == null || row.isNull()) return null;
            JsonNode v = row.get(j);
            return (v == null || v.isNull()) ? null : v.asDouble();
        }
    }
}
