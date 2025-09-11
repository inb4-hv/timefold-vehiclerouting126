package org.acme.vehiclerouting.domain;

import ai.timefold.solver.core.impl.heuristic.selector.common.nearby.NearbyDistanceMeter;

public class LocationDistanceMeter implements NearbyDistanceMeter<Visit, LocationAware> {

    @Override
    public double getNearbyDistance(Visit origin, LocationAware destination) {
        Location o = origin.getLocation();
        Location d = destination.getLocation();

        boolean useHighway = origin.isAllowHighways();
        if (destination instanceof Visit dv) {
            useHighway = useHighway || dv.isAllowHighways();
        }
        return useHighway ? o.getHighwayTimeTo(d) : o.getNoMotorwayTimeTo(d);
    }
}
