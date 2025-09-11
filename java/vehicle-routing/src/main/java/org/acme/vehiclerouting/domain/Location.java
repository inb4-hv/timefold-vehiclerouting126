package org.acme.vehiclerouting.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonFormat(shape = JsonFormat.Shape.ARRAY)
public class Location {

    private double latitude;
    private double longitude;

    // Two maps: with highways and without motorways
    @JsonIgnore
    private Map<Location, Long> drivingTimeSecondsHighway;
    @JsonIgnore
    private Map<Location, Long> drivingTimeSecondsNoMotorway;

    @JsonCreator
    public Location(@JsonProperty("latitude") double latitude, @JsonProperty("longitude") double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Map<Location, Long> getDrivingTimeSecondsHighway() {
        return drivingTimeSecondsHighway;
    }

    public void setDrivingTimeSecondsHighway(Map<Location, Long> drivingTimeSecondsHighway) {
        this.drivingTimeSecondsHighway = drivingTimeSecondsHighway;
    }

    public Map<Location, Long> getDrivingTimeSecondsNoMotorway() {
        return drivingTimeSecondsNoMotorway;
    }

    public void setDrivingTimeSecondsNoMotorway(Map<Location, Long> drivingTimeSecondsNoMotorway) {
        this.drivingTimeSecondsNoMotorway = drivingTimeSecondsNoMotorway;
    }

    /** Highway travel time (seconds) to the given location. */
    public long getHighwayTimeTo(Location to) {
        return drivingTimeSecondsHighway.get(to);
    }

    /** Non-motorway travel time (seconds) to the given location. */
    public long getNoMotorwayTimeTo(Location to) {
        return drivingTimeSecondsNoMotorway.get(to);
    }

    /** Back-compat: use the faster of the two (mainly for any forgotten callers). */
    public long getDrivingTimeTo(Location to) {
        long hi = getHighwayTimeTo(to);
        long no = getNoMotorwayTimeTo(to);
        return Math.min(hi, no);
    }

    @Override
    public String toString() {
        return latitude + "," + longitude;
    }
}
