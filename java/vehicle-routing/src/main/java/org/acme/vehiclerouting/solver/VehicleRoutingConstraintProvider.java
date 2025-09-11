package org.acme.vehiclerouting.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

import org.acme.vehiclerouting.domain.Visit;
import org.acme.vehiclerouting.domain.Vehicle;
import org.acme.vehiclerouting.domain.Location;
import org.acme.vehiclerouting.domain.geo.HighwayUsageRegistry;

import java.time.Duration;

public class VehicleRoutingConstraintProvider implements ConstraintProvider {

    // Names
    public static final String FIXED_VEHICLE = "fixedVehicle";
    public static final String SERVICE_FINISHED_AFTER_MAX_END_TIME = "serviceFinishedAfterMaxEndTime";
    public static final String START_DELAY = "startDelay";
    public static final String CHEF_LEVEL_MISMATCH = "chefLevelMismatch";
    public static final String EARLY_START_WITHIN_WINDOW = "earlyStartWithinWindow";
    public static final String AVOID_HIGHWAYS = "avoidHighways";
    public static final String MINIMIZE_TRAVEL_TIME = "minimizeTravelTime";

    // Weights (tune as needed; order implies priority)
    private static final long W_START_DELAY = 10_000L;
    private static final long W_CHEF_MISMATCH = 2_000L;
    private static final long W_EARLY_START = 1_500L;
    private static final long W_AVOID_HIGHWAYS = 600L;
    private static final long W_TRAVEL_TIME = 1L;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                fixedVehicle(factory),                            // HARD
                serviceFinishedAfterMaxEndTime(factory),         // HARD
                startDelay(factory),                             // SOFT (highest)
                chefLevelMismatch(factory),                      // SOFT
                earlyStartWithinWindow(factory),                 // SOFT
                avoidHighways(factory),                          // SOFT
                minimizeTravelTime(factory)                      // SOFT (lowest)
        };
    }

    // ------------------
    // Hard constraints
    // ------------------

    protected Constraint fixedVehicle(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getFixedVehicle() != null && v.getVehicle() != null
                        && !v.getFixedVehicle().equals(v.getVehicle().getId()))
                .penalize(HardSoftLongScore.ONE_HARD)
                .asConstraint(FIXED_VEHICLE);
    }

    protected Constraint serviceFinishedAfterMaxEndTime(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(Visit::isServiceFinishedAfterMaxEndTime)
                .penalizeLong(HardSoftLongScore.ONE_HARD,
                        Visit::getServiceFinishedDelayInMinutes)
                .asConstraint(SERVICE_FINISHED_AFTER_MAX_END_TIME);
    }

    // ------------------
    // Soft constraints
    // ------------------

    // Start as close to orderTime (minStartTime) as possible; lateness penalized linearly in minutes.
    protected Constraint startDelay(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getStartServiceTime() != null)
                .penalizeLong(HardSoftLongScore.ofSoft(W_START_DELAY), v -> {
                    long minutes = Duration.between(v.getMinStartTime(), v.getStartServiceTime()).toMinutes();
                    return Math.max(0, minutes); // no penalty if on-time or (theoretically) earlier (but earlier is clamped in domain)
                })
                .asConstraint(START_DELAY);
    }

    // Prefer vehicles with enough chef level; mismatch squared to punish big gaps.
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

    // As late as possible within window: penalize minutes between chosen start and latest feasible start.
    protected Constraint earlyStartWithinWindow(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> v.getStartServiceTime() != null)
                .penalizeLong(HardSoftLongScore.ofSoft(W_EARLY_START), v -> {
                    var latestStart = v.getMaxEndTime().minus(v.getServiceDuration());
                    long minutesEarly = Duration.between(v.getStartServiceTime(), latestStart).toMinutes();
                    return Math.max(0, minutesEarly);
                })
                .asConstraint(EARLY_START_WITHIN_WINDOW);
    }

    // Avoid highways when the order requests it, but allow exceptions with penalty.
    protected Constraint avoidHighways(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(v -> !v.isAllowHighways() && v.getVehicle() != null)
                .penalizeLong(HardSoftLongScore.ofSoft(W_AVOID_HIGHWAYS), v -> {
                    Location from = (v.getPreviousVisit() != null)
                            ? v.getPreviousVisit().getLocation()
                            : v.getVehicle().getHomeLocation();
                    return HighwayUsageRegistry.usedHighway(from, v.getLocation()) ? 1L : 0L;
                })
                .asConstraint(AVOID_HIGHWAYS);
    }

    // Keep original: total driving time (seconds).
    protected Constraint minimizeTravelTime(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .penalizeLong(HardSoftLongScore.ofSoft(W_TRAVEL_TIME),
                        Vehicle::getTotalDrivingTimeSeconds)
                .asConstraint(MINIMIZE_TRAVEL_TIME);
    }
}
