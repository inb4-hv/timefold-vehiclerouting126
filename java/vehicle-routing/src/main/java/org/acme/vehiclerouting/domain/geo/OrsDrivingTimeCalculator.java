package org.acme.vehiclerouting.domain.geo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String profile;

    public OrsDrivingTimeCalculator(String baseUrl, String profile) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.profile = profile;
    }

    /** Build BOTH matrices and assign to each Location. */
    public void initDrivingTimeMaps(Collection<Location> locations) {
        if (locations.size() <= 1) {
            locations.forEach(l -> {
                l.setDrivingTimeSecondsHighway(Map.of(l, 0L));
                l.setDrivingTimeSecondsNoMotorway(Map.of(l, 0L));
            });
            return;
        }

        // ORS expects [lon, lat]
        List<double[]> coords = new ArrayList<>(locations.size());
        List<Location> indexToLoc = new ArrayList<>(locations.size());
        for (Location l : locations) {
            coords.add(new double[]{ l.getLongitude(), l.getLatitude() });
            indexToLoc.add(l);
        }

        MatrixResult hi = fetchMatrix(coords, false);
        MatrixResult no = fetchMatrix(coords, true);

        for (int i = 0; i < indexToLoc.size(); i++) {
            Location from = indexToLoc.get(i);
            Map<Location, Long> mapHi = new HashMap<>();
            Map<Location, Long> mapNo = new HashMap<>();

            for (int j = 0; j < indexToLoc.size(); j++) {
                Location to = indexToLoc.get(j);
                long hiSec, noSec;

                if (i == j) {
                    hiSec = 0L;
                    noSec = 0L;
                } else {
                    Double tHi = hi.duration(i, j);
                    Double dHi = hi.distance(i, j);
                    Double tNo = no.duration(i, j);
                    Double dNo = no.distance(i, j);

                    // Cap at 85 km/h and fall back across matrices if one missing.
                    hiSec = (tHi != null) ? adjustedWith85Cap(tHi, dHi)
                                          : (tNo != null ? adjustedWith85Cap(tNo, dNo) : 36000L);
                    noSec = (tNo != null) ? adjustedWith85Cap(tNo, dNo)
                                          : (tHi != null ? adjustedWith85Cap(tHi, dHi) : 36000L);
                }
                mapHi.put(to, hiSec);
                mapNo.put(to, noSec);
            }
            from.setDrivingTimeSecondsHighway(mapHi);
            from.setDrivingTimeSecondsNoMotorway(mapNo);
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
            body.put("maximum_speed", 85);
            if (avoidMotorways) {
                body.put("avoid_features", List.of("highways"));
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
                return new MatrixResult(root.get("durations"), root.get("distances"));
            } else {
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
