package org.acme.vehiclerouting.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.Visit;

import java.time.Duration;

public class VehicleRoutingConstraintProvider implements ConstraintProvider {

    public static final String FIXED_VEHICLE = "fixedVehicle";
    public static final String SERVICE_FINISHED_AFTER_MAX_END_TIME = "serviceFinishedAfterMaxEndTime";
    public static final String START_DELAY = "startDelay";
    public static final String CHEF_LEVEL_MISMATCH = "chefLevelMismatch";
    public static final String VEHICLE_LOAD_BALANCE = "vehicleLoadBalance";
    public static final String EARLY_START_WITHIN_WINDOW = "earlyStartWithinWindow";
    public static final String MINIMIZE_TRAVEL_TIME = "minimizeTravelTime";

    private static final long W_START_DELAY = 10_000L;
    private static final long W_CHEF_MISMATCH = 2_000L;
    private static final long W_VEHICLE_LOAD_BALANCE = 1_800L;
    private static final long W_EARLY_START = 1_500L;
    private static final long W_TRAVEL_TIME = 1L;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                respectChronoOrder(factory),                      // HARD
                fixedVehicle(factory),                            // HARD
                serviceFinishedAfterMaxEndTime(factory),         // HARD
                startDelay(factory),                             // SOFT
                chefLevelMismatch(factory),                      // SOFT
                vehicleLoadBalance(factory),                     // SOFT
                earlyStartWithinWindow(factory),                 // SOFT
                minimizeTravelTime(factory)                      // SOFT (lowest)
        };
    }

    protected Constraint fixedVehicle(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getFixedVehicle() != null && v.getVehicle() != null
                        && !v.getFixedVehicle().equals(v.getVehicle().getId()))
                .penalize(HardSoftLongScore.ofHard(1_000_000))
                .asConstraint(FIXED_VEHICLE);
    }

    protected Constraint respectChronoOrder(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getPreviousVisit() != null)
                .filter(visit -> visit.getMinStartTime().isBefore(visit.getPreviousVisit().getMinStartTime()))
                .penalize(HardSoftLongScore.ofHard(1_000_000))
                .asConstraint("respectChronoOrder");
    }

    protected Constraint serviceFinishedAfterMaxEndTime(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(Visit::isServiceFinishedAfterMaxEndTime)
                .penalizeLong(HardSoftLongScore.ONE_HARD, Visit::getServiceFinishedDelayInMinutes)
                .asConstraint(SERVICE_FINISHED_AFTER_MAX_END_TIME);
    }

    protected Constraint startDelay(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getStartServiceTime() != null)
                .penalizeLong(HardSoftLongScore.ofSoft(W_START_DELAY), v -> {
                    long minutes = Duration.between(v.getMinStartTime(), v.getStartServiceTime()).toMinutes();
                    return Math.max(0, minutes);
                })
                .asConstraint(START_DELAY);
    }

    protected Constraint chefLevelMismatch(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getVehicle() != null)
                .penalizeLong(HardSoftLongScore.ofSoft(W_CHEF_MISMATCH), v -> {
                    int required = v.getChefLevelRequired();
                    int got = v.getVehicle().getChefLevel();
                    int diff = required - got;
                    return diff > 0 ? (long) diff * diff : 0L;
                })
                .asConstraint(CHEF_LEVEL_MISMATCH);
    }

    protected Constraint vehicleLoadBalance(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .filter(vehicle -> vehicle.getTotalDemand() > 60)
                .penalizeLong(HardSoftLongScore.ofSoft(W_VEHICLE_LOAD_BALANCE),
                        vehicle -> {
                            long overload = vehicle.getTotalDemand() - 60;
                            return (overload * overload) / 2;
                        })
                .asConstraint(VEHICLE_LOAD_BALANCE);
    }

    protected Constraint earlyStartWithinWindow(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getStartServiceTime() != null)
                .penalizeLong(HardSoftLongScore.ofSoft(W_EARLY_START), v -> {
                    var latestStart = v.getMaxEndTime().minus(v.getServiceDuration());
                    long minutesEarly = java.time.Duration.between(v.getStartServiceTime(), latestStart).toMinutes();
                    return Math.max(0, minutesEarly);
                })
                .asConstraint(EARLY_START_WITHIN_WINDOW);
    }

    protected Constraint minimizeTravelTime(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .penalizeLong(HardSoftLongScore.ofSoft(W_TRAVEL_TIME), Vehicle::getTotalDrivingTimeSeconds)
                .asConstraint(MINIMIZE_TRAVEL_TIME);
    }
}