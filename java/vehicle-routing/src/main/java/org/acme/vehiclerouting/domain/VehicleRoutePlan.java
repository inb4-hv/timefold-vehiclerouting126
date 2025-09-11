package org.acme.vehiclerouting.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.vehiclerouting.domain.geo.OrsDrivingTimeCalculator;
import org.acme.vehiclerouting.domain.geo.HighwayUsageRegistry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**

The plan for routing vehicles to visits, including:

capacity

time windows

Travel time is used to evaluate feasibility and optimize routes.
*/
@JsonInclude(JsonInclude.Include.NON_NULL)
@PlanningSolution
public class VehicleRoutePlan {

private String name;

private Location southWestCorner;
private Location northEastCorner;

private LocalDateTime startDateTime;

private LocalDateTime endDateTime;

@PlanningEntityCollectionProperty
private List<Vehicle> vehicles;

@PlanningEntityCollectionProperty
@ValueRangeProvider
private List<Visit> visits;

@PlanningScore
private HardSoftLongScore score;

private SolverStatus solverStatus;

@JsonProperty(access = JsonProperty.Access.READ_ONLY)
private String scoreExplanation;

public VehicleRoutePlan() {
}

public VehicleRoutePlan(String name, HardSoftLongScore score, SolverStatus solverStatus) {
this.name = name;
this.score = score;
this.solverStatus = solverStatus;
}

@JsonCreator
public VehicleRoutePlan(@JsonProperty("name") String name,
@JsonProperty("southWestCorner") Location southWestCorner,
@JsonProperty("northEastCorner") Location northEastCorner,
@JsonProperty("startDateTime") LocalDateTime startDateTime,
@JsonProperty("endDateTime") LocalDateTime endDateTime,
@JsonProperty("vehicles") List<Vehicle> vehicles,


@JsonProperty("visits") List<Visit> visits) {
this.name = name;
this.southWestCorner = southWestCorner;
this.northEastCorner = northEastCorner;
this.startDateTime = startDateTime;
this.endDateTime = endDateTime;
this.vehicles = vehicles;
this.visits = visits;

 List<Location> locations = Stream.concat(
         vehicles.stream().map(Vehicle::getHomeLocation),
         visits.stream().map(Visit::getLocation)).toList();

 // Initialize driving times with ORS matrices, fallback to Haversine if ORS is not reachable.
 String baseUrl = System.getProperty("app.ors.base-url",
         System.getenv().getOrDefault("APP_ORS_BASE_URL", "http://ors-app:8082"));
 String profile = System.getProperty("app.ors.profile",
         System.getenv().getOrDefault("APP_ORS_PROFILE", "driving-car"));

 try {
     HighwayUsageRegistry.clear();
     new OrsDrivingTimeCalculator(baseUrl, profile).initDrivingTimeMaps(locations);
 } catch (Exception ex) {
     // Fallback that always works, so the app still runs even if ORS is down.
     org.acme.vehiclerouting.domain.geo.HaversineDrivingTimeCalculator
             .getInstance().initDrivingTimeMaps(locations);
 }
}

    public String getName() {
    return name;
    }

    public Location getSouthWestCorner() {
    return southWestCorner;
    }

    public Location getNorthEastCorner() {
    return northEastCorner;
    }

    public LocalDateTime getStartDateTime() {
    return startDateTime;
    }

    public LocalDateTime getEndDateTime() {
    return endDateTime;
    }

    public List<Vehicle> getVehicles() {
    return vehicles;
    }

    public List<Visit> getVisits() {
    return visits;
    }

    public HardSoftLongScore getScore() {
    return score;
    }

    public void setScore(HardSoftLongScore score) {
    this.score = score;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public long getTotalDrivingTimeSeconds() {
    return vehicles == null ? 0 : vehicles.stream().mapToLong(Vehicle::getTotalDrivingTimeSeconds).sum();
    }

    public SolverStatus getSolverStatus() {
    return solverStatus;
    }

    public void setSolverStatus(SolverStatus solverStatus) {
    this.solverStatus = solverStatus;
    }

    public String getScoreExplanation() {
    return scoreExplanation;
    }

    public void setScoreExplanation(String scoreExplanation) {
    this.scoreExplanation = scoreExplanation;
    }
}