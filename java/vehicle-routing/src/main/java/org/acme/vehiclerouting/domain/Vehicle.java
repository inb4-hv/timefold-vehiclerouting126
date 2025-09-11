package org.acme.vehiclerouting.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(scope = Vehicle.class, generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@PlanningEntity
public class Vehicle implements LocationAware {

    @PlanningId
    private String id;
    private int capacity;
    @JsonIdentityReference
    private Location homeLocation;

    private LocalDateTime departureTime;

    // custom field
    private int chefLevel;

    @JsonIdentityReference(alwaysAsId = true)
    @PlanningListVariable
    private List<Visit> visits;

    public Vehicle() {
    }

    public Vehicle(String id, int capacity, Location homeLocation, LocalDateTime departureTime) {
        this.id = id;
        this.capacity = capacity;
        this.homeLocation = homeLocation;
        this.departureTime = departureTime;
        this.visits = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) { this.id = id; }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public Location getHomeLocation() {
        return homeLocation;
    }

    public void setHomeLocation(Location homeLocation) {
        this.homeLocation = homeLocation;
    }

    public LocalDateTime getDepartureTime() {
        return departureTime;
    }

    // used by import to map earliestDeparture
    public void setDepartureTime(LocalDateTime departureTime) {
        this.departureTime = departureTime;
    }

    public int getChefLevel() {
        return chefLevel;
    }

    public void setChefLevel(int chefLevel) {
        this.chefLevel = chefLevel;
    }

    public List<Visit> getVisits() {
        return visits;
    }

    public void setVisits(List<Visit> visits) {
        this.visits = visits;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    @JsonIgnore
    @Override
    public Location getLocation() {
        return homeLocation;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public int getTotalDemand() {
        int totalDemand = 0;
        for (Visit visit : visits) {
            totalDemand += visit.getDemand();
        }
        return totalDemand;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public long getTotalDrivingTimeSeconds() {
        if (visits.isEmpty()) {
            return 0;
        }
        long total = 0L;

        // Depot -> first
        Visit first = visits.get(0);
        boolean useHi = first.isAllowHighways(); // depot assumed "no", so only first matters
        total += useHi ? homeLocation.getHighwayTimeTo(first.getLocation())
                       : homeLocation.getNoMotorwayTimeTo(first.getLocation());

        // Between visits
        for (int i = 0; i < visits.size() - 1; i++) {
            Visit a = visits.get(i);
            Visit b = visits.get(i + 1);
            boolean hi = a.isAllowHighways() || b.isAllowHighways();
            total += hi ? a.getLocation().getHighwayTimeTo(b.getLocation())
                        : a.getLocation().getNoMotorwayTimeTo(b.getLocation());
        }

        // Last -> depot
        Visit last = visits.get(visits.size() - 1);
        boolean hiBack = last.isAllowHighways();
        total += hiBack ? last.getLocation().getHighwayTimeTo(homeLocation)
                        : last.getLocation().getNoMotorwayTimeTo(homeLocation);

        return total;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public LocalDateTime arrivalTime() {
        if (visits.isEmpty()) {
            return departureTime;
        }

        Visit lastVisit = visits.get(visits.size() - 1);
        return lastVisit.getDepartureTime().plusSeconds(lastVisit.getLocation().getDrivingTimeTo(homeLocation));
    }

    @Override
    public String toString() {
        return id;
    }
}
