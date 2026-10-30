package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rest of the shift as seen at {@code minute}: each cab starts where its last finished activity
 * left it, and the plan keeps everything not yet finished, including activities in progress (they
 * cannot be undone). Trips already done, and backup trips already started, drop out.
 *
 * <p>This makes a re-planned plan checkable by the same validator as a full-day plan.
 *
 * @param history what each cab finished before {@code minute}, for display
 */
public record Snapshot(int minute, Scenario scenario, Plan plan, Map<String, List<Activity>> history) {

    public static Snapshot of(Scenario scenario, Plan plan, int minute) {
        List<Vehicle> vehicles = new ArrayList<>();
        Map<String, List<Activity>> rest = new LinkedHashMap<>();
        Map<String, List<Activity>> history = new LinkedHashMap<>();
        Set<String> tripsLeft = new HashSet<>();

        for (Vehicle v : scenario.vehicles()) {
            List<Activity> timeline = plan.timeline(v.id());
            int split = 0;
            while (split < timeline.size() && timeline.get(split).end() <= minute) split++;
            List<Activity> done = timeline.subList(0, split);
            List<Activity> left = timeline.subList(split, timeline.size());
            if (done.isEmpty()) {
                vehicles.add(v);
            } else {
                Activity last = done.get(done.size() - 1);
                vehicles.add(v.withState(last.socEndKWh(), last.to(), last.end()));
            }
            history.put(v.id(), List.copyOf(done));
            rest.put(v.id(), List.copyOf(left));
            for (Activity a : left) if (a instanceof TripLeg leg) tripsLeft.add(leg.tripId());
        }

        Map<String, Trip> trips = scenario.tripsById();
        List<UnassignedTrip> unassigned = new ArrayList<>();
        for (UnassignedTrip u : plan.unassigned()) {
            if (trips.get(u.tripId()).startTime() >= minute) {
                unassigned.add(u);
                tripsLeft.add(u.tripId());
            }
        }
        List<Trip> remainingTrips = scenario.trips().stream().filter(t -> tripsLeft.contains(t.id())).toList();

        Scenario now = new Scenario(scenario.name(), minute, scenario.shiftEnd(), vehicles, scenario.hubs(),
                remainingTrips, scenario.tariff(), scenario.physics());
        return new Snapshot(minute, now, new Plan(plan.scenarioName(), rest, unassigned), history);
    }

    /** The activity a cab is in the middle of at {@code minute}, or null if it is idle. */
    public Activity inProgress(String cabId) {
        List<Activity> left = plan.timeline(cabId);
        return !left.isEmpty() && left.get(0).start() < minute ? left.get(0) : null;
    }

    public Snapshot withScenario(Scenario changed) {
        return new Snapshot(minute, changed, plan, history);
    }
}
