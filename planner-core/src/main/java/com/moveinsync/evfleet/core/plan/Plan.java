package com.moveinsync.evfleet.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The output of planning: an ordered activity list per cab and the trips sent to backup cabs.
 * Immutable; re-planning produces a new Plan.
 */
public record Plan(String scenarioName, Map<String, List<Activity>> timelines, List<UnassignedTrip> unassigned) {

    public Plan {
        Map<String, List<Activity>> copy = new LinkedHashMap<>();
        timelines.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        timelines = Collections.unmodifiableMap(copy);
        unassigned = List.copyOf(unassigned);
    }

    public List<Activity> timeline(String vehicleId) {
        return timelines.getOrDefault(vehicleId, List.of());
    }

    public Stream<Activity> allActivities() {
        return timelines.values().stream().flatMap(List::stream);
    }

    public long assignedTripCount() {
        return allActivities().filter(a -> a instanceof TripLeg).count();
    }

    public double totalChargingCost() {
        return allActivities().filter(a -> a instanceof ChargingSession)
                .mapToDouble(a -> ((ChargingSession) a).costRupees()).sum();
    }

    public long chargingSessionCount() {
        return allActivities().filter(a -> a instanceof ChargingSession).count();
    }

    public double deadheadKm() {
        return allActivities().filter(a -> a instanceof Deadhead).mapToDouble(a -> ((Deadhead) a).km()).sum();
    }
}
