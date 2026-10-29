package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;

import java.util.List;
import java.util.Optional;

/**
 * A concrete, already-feasible way for one cab to serve one trip: the activities to append
 * and how attractive that is (lower score = better).
 */
public record Insertion(CabState cab, String tripId, List<Activity> activities, double score, double socAfterKWh) {

    public Optional<ChargingSession> chargingSession() {
        return activities.stream().filter(a -> a instanceof ChargingSession)
                .map(a -> (ChargingSession) a).findFirst();
    }
}
