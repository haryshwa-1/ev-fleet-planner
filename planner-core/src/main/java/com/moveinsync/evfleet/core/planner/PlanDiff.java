package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a re-plan changed, as dispatchers and drivers would see it.
 *
 * @param tripChanges every trip whose cab changed; a null cab means a backup cab
 * @param cabsChanged cabs whose timeline differs in any way (trips, charging or timing)
 */
public record PlanDiff(List<TripChange> tripChanges, List<ChargingSession> sessionsAdded,
                       List<ChargingSession> sessionsRemoved, Set<String> cabsChanged) {

    public record TripChange(String tripId, String fromCab, String toCab) {
        public boolean toBackup() {
            return toCab == null;
        }

        public boolean rescuedFromBackup() {
            return fromCab == null;
        }
    }

    public static PlanDiff between(Plan before, Plan after) {
        Map<String, String> cabBefore = cabByTrip(before);
        Map<String, String> cabAfter = cabByTrip(after);
        List<TripChange> trips = new ArrayList<>();
        for (String tripId : new TreeSet<>(cabBefore.keySet())) {
            String from = cabBefore.get(tripId);
            String to = cabAfter.get(tripId);
            if (!Objects.equals(from, to)) trips.add(new TripChange(tripId, from, to));
        }

        List<ChargingSession> added = new ArrayList<>();
        List<ChargingSession> removed = new ArrayList<>();
        Set<String> cabs = new TreeSet<>();
        Set<String> ids = new HashSet<>(before.timelines().keySet());
        ids.addAll(after.timelines().keySet());
        for (String cab : ids) {
            List<Activity> b = before.timeline(cab);
            List<Activity> a = after.timeline(cab);
            if (b.equals(a)) continue;
            cabs.add(cab);
            sessions(a).stream().filter(s -> !sessions(b).contains(s)).forEach(added::add);
            sessions(b).stream().filter(s -> !sessions(a).contains(s)).forEach(removed::add);
        }
        return new PlanDiff(List.copyOf(trips), List.copyOf(added), List.copyOf(removed), Set.copyOf(cabs));
    }

    public long reassigned() {
        return tripChanges.stream().filter(c -> c.fromCab() != null && c.toCab() != null).count();
    }

    public long movedToBackup() {
        return tripChanges.stream().filter(TripChange::toBackup).count();
    }

    public long rescuedFromBackup() {
        return tripChanges.stream().filter(TripChange::rescuedFromBackup).count();
    }

    public String summary() {
        return String.format("%d cabs changed, %d trips reassigned, %d to backup, %d rescued from backup, "
                        + "%d charging sessions added, %d removed", cabsChanged.size(), reassigned(), movedToBackup(),
                rescuedFromBackup(), sessionsAdded.size(), sessionsRemoved.size());
    }

    /** Trip id to cab id; trips sent to backup map to null. */
    private static Map<String, String> cabByTrip(Plan plan) {
        Map<String, String> map = new HashMap<>();
        plan.timelines().forEach((cab, timeline) -> {
            for (Activity a : timeline) if (a instanceof TripLeg leg) map.put(leg.tripId(), cab);
        });
        for (UnassignedTrip u : plan.unassigned()) map.put(u.tripId(), null);
        return map;
    }

    private static List<ChargingSession> sessions(List<Activity> timeline) {
        return timeline.stream().filter(a -> a instanceof ChargingSession).map(a -> (ChargingSession) a).toList();
    }
}
