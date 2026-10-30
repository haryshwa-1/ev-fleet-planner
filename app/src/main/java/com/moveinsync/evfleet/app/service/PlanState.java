package com.moveinsync.evfleet.app.service;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Tariff;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import com.moveinsync.evfleet.core.planner.Replanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to continue a shift from one published version: the rest of the shift as of
 * {@code asOfMinute}, plus what already happened. Physics and tariff are fixed for the deployment,
 * so they are not stored.
 *
 * @param history    activities each cab has finished
 * @param pastBackup trips that went to backup cabs and have already started
 */
public record PlanState(String scenarioName, int asOfMinute, int shiftEnd, List<Vehicle> vehicles, List<Hub> hubs,
                        List<Trip> trips, Plan plan, Map<String, List<Activity>> history,
                        List<UnassignedTrip> pastBackup) {

    private static final FleetPhysics PHYSICS = ScenarioGenerator.standardPhysics();
    private static final Tariff TARIFF = ScenarioGenerator.standardTariff();

    public static PlanState initial(Scenario s, Plan plan) {
        return new PlanState(s.name(), s.shiftStart(), s.shiftEnd(), s.vehicles(), s.hubs(), s.trips(), plan,
                Map.of(), List.of());
    }

    public Scenario scenario() {
        return new Scenario(scenarioName, asOfMinute, shiftEnd, vehicles, hubs, trips, TARIFF, PHYSICS);
    }

    /** The state after a repair: what the repair's snapshot cut off moves into the history. */
    public PlanState after(Replanner.Result r) {
        Map<String, List<Activity>> merged = new LinkedHashMap<>(history);
        r.snapshot().history().forEach((cab, done) -> {
            if (done.isEmpty()) return;
            List<Activity> all = new ArrayList<>(merged.getOrDefault(cab, List.of()));
            all.addAll(done);
            merged.put(cab, all);
        });
        List<UnassignedTrip> past = new ArrayList<>(pastBackup);
        Map<String, Trip> byId = scenario().tripsById();
        for (UnassignedTrip u : plan.unassigned()) {
            if (byId.get(u.tripId()).startTime() < r.snapshot().minute()) past.add(u);
        }
        Scenario s = r.snapshot().scenario();
        return new PlanState(scenarioName, r.snapshot().minute(), shiftEnd, s.vehicles(), s.hubs(), s.trips(),
                r.after(), merged, past);
    }

    /** A cab's whole day: what it finished, then what is still planned. */
    public List<Activity> fullTimeline(String cabId) {
        List<Activity> all = new ArrayList<>(history.getOrDefault(cabId, List.of()));
        all.addAll(plan.timeline(cabId));
        return all;
    }

    public int backupTrips() {
        return pastBackup.size() + plan.unassigned().size();
    }

    public long electricTrips() {
        long done = history.values().stream().flatMap(List::stream).filter(a -> a instanceof TripLeg).count();
        return done + plan.assignedTripCount();
    }

    public double chargingCost() {
        double done = history.values().stream().flatMap(List::stream).filter(a -> a instanceof ChargingSession)
                .mapToDouble(a -> ((ChargingSession) a).costRupees()).sum();
        return done + plan.totalChargingCost();
    }
}
