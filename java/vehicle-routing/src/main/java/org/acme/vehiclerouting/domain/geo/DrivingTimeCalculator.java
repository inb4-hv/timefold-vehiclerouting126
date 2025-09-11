package org.acme.vehiclerouting.domain.geo;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.acme.vehiclerouting.domain.Location;

public interface DrivingTimeCalculator {

    long calculateDrivingTime(Location from, Location to);

    default Map<Location, Map<Location, Long>> calculateBulkDrivingTime(
            Collection<Location> fromLocations,
            Collection<Location> toLocations) {
        return fromLocations.stream().collect(Collectors.toMap(
                Function.identity(),
                from -> toLocations.stream().collect(Collectors.toMap(
                        Function.identity(),
                        to -> calculateDrivingTime(from, to)))));
    }

    /** 
     * Default single-matrix initializer: assign the same matrix to both highway and no-motorway.
     * (Used by Haversine fallback.)
     */
    default void initDrivingTimeMaps(Collection<Location> locations) {
        Map<Location, Map<Location, Long>> m = calculateBulkDrivingTime(locations, locations);
        locations.forEach(loc -> {
            loc.setDrivingTimeSecondsHighway(m.get(loc));
            loc.setDrivingTimeSecondsNoMotorway(m.get(loc));
        });
    }
}
