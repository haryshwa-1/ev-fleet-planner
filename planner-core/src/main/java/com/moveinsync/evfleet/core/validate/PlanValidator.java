package com.moveinsync.evfleet.core.validate;

import com.moveinsync.evfleet.core.model.Charger;
import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Deadhead;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import com.moveinsync.evfleet.core.util.Clock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Independent checker for any plan against a scenario.
 *
 * <p>It shares no code with the planner except the scenario's physics rules (distance, speed,
 * consumption, charging curve), which are inputs to the problem. It re-derives the state of charge
 * of every cab from its initial charge, step by step, and ignores what the planner computed except
 * to report mismatches.
 *
 * <p>Checking only activity boundaries is enough: within a drive, charge falls linearly, and within
 * a charging session it rises monotonically, so the minimum is always at a boundary.
 */
public final class PlanValidator {

    public static final int MAX_LISTED = 200;
    private static final double KWH_EPS = 1e-3;
    private static final double KM_EPS = 0.01;
    private static final double LOC_EPS = 1e-6;

    public ValidationReport validate(Scenario scenario, Plan plan) {
        return new Run(scenario, plan).execute();
    }

    private static final class Run {
        private final Scenario scenario;
        private final Plan plan;
        private final FleetPhysics physics;
        private final List<Violation> violations = new ArrayList<>();
        private final Map<Rule, Long> byRule = new EnumMap<>(Rule.class);
        private int violationCount;
        private long checks;
        private double cost;
        private double lowestSocPct = 100.0;
        private int chargingSessions;

        Run(Scenario scenario, Plan plan) {
            this.scenario = scenario;
            this.plan = plan;
            this.physics = scenario.physics();
        }

        ValidationReport execute() {
            Map<String, Trip> trips = scenario.tripsById();
            Map<String, Vehicle> vehicles = scenario.vehiclesById();
            Map<String, Hub> hubs = scenario.hubsById();
            Map<String, Charger> chargers = scenario.chargersById();

            int assigned = checkTripAccounting(trips);
            List<ChargingSession> sessions = new ArrayList<>();

            plan.timelines().forEach((vehicleId, activities) -> {
                Vehicle v = vehicles.get(vehicleId);
                if (check(v != null, Rule.UNKNOWN_REFERENCE, vehicleId, "cab not in scenario")) {
                    checkVehicle(v, activities, trips, hubs, chargers, sessions);
                }
            });

            Map<String, Integer> peakByHub = checkChargerCapacity(sessions, hubs);
            chargingSessions = sessions.size();

            var stats = new ValidationReport.Stats(trips.size(), assigned, plan.unassigned().size(),
                    chargingSessions, Math.round(cost * 100) / 100.0,
                    Math.round(lowestSocPct * 10) / 10.0, peakByHub);
            return new ValidationReport(violationCount == 0, violationCount, List.copyOf(violations),
                    Map.copyOf(byRule), checks, stats);
        }

        private int checkTripAccounting(Map<String, Trip> trips) {
            Map<String, Integer> seen = new HashMap<>();
            int assigned = 0;
            for (List<Activity> timeline : plan.timelines().values()) {
                for (Activity a : timeline) {
                    if (a instanceof TripLeg leg) {
                        seen.merge(leg.tripId(), 1, Integer::sum);
                        assigned++;
                    }
                }
            }
            for (UnassignedTrip u : plan.unassigned()) {
                seen.merge(u.tripId(), 1, Integer::sum);
                check(u.reason() != null, Rule.MISSING_REASON, u.tripId(), "unassigned trip without reason");
            }
            for (String tripId : trips.keySet()) {
                int n = seen.getOrDefault(tripId, 0);
                check(n == 1, Rule.TRIP_ACCOUNTING, tripId, "trip appears " + n + " times in plan");
            }
            for (String tripId : seen.keySet()) {
                check(trips.containsKey(tripId), Rule.UNKNOWN_REFERENCE, tripId, "trip not in scenario");
            }
            return assigned;
        }

        private void checkVehicle(Vehicle v, List<Activity> activities, Map<String, Trip> trips,
                                  Map<String, Hub> hubs, Map<String, Charger> chargers,
                                  List<ChargingSession> sessions) {
            double soc = v.initialSocKWh();
            double floor = v.batteryKWh() * physics.socFloorFraction();
            Location at = v.location();
            int clock = v.availableFrom();
            String id = v.id();

            for (Activity a : activities) {
                String where = id + " @" + Clock.format(a.start());
                check(a.start() >= clock, Rule.TIME_ORDER, where,
                        "starts before previous activity ends (" + Clock.format(clock) + ")");
                check(a.end() >= a.start(), Rule.TIME_ORDER, where, "ends before it starts");
                check(near(a.from(), at), Rule.LOCATION_CONTINUITY, where,
                        "starts at " + a.from() + " but cab is at " + at);
                check(Math.abs(a.socStartKWh() - soc) <= KWH_EPS, Rule.STATE_MISMATCH, where,
                        String.format("plan says start SoC %.3f, recomputed %.3f", a.socStartKWh(), soc));

                switch (a) {
                    case Deadhead d -> soc = checkDrive(v, where, d.from(), d.to(), d.km(), d.start(), d.end(), soc);
                    case TripLeg leg -> {
                        Trip t = trips.get(leg.tripId());
                        if (check(t != null, Rule.UNKNOWN_REFERENCE, where, "trip " + leg.tripId())) {
                            check(leg.start() == t.startTime(), Rule.TRIP_TIMING, where,
                                    "trip " + t.id() + " starts " + Clock.format(leg.start())
                                            + " instead of " + Clock.format(t.startTime()));
                            check(leg.end() <= t.latestFinish(), Rule.TRIP_TIMING, where,
                                    "trip " + t.id() + " ends " + Clock.format(leg.end())
                                            + " after latest finish " + Clock.format(t.latestFinish()));
                            check(near(leg.from(), t.start()) && near(leg.to(), t.end()), Rule.LOCATION_CONTINUITY,
                                    where, "trip " + t.id() + " endpoints differ from scenario");
                            check(Math.abs(leg.km() - t.distanceKm()) <= KM_EPS, Rule.TRAVEL_TIME, where,
                                    "trip " + t.id() + " distance differs from scenario");
                            int minMinutes = physics.geo().travelMinutes(t.distanceKm(), t.startTime());
                            check(leg.end() - leg.start() >= minMinutes, Rule.TRAVEL_TIME, where,
                                    "trip " + t.id() + " shorter than " + minMinutes + " min");
                            soc -= physics.driveEnergyKWh(v, t.distanceKm(), leg.start());
                        }
                    }
                    case ChargingSession c -> {
                        soc = checkCharge(v, where, c, hubs, chargers, soc);
                        sessions.add(c);
                    }
                }

                check(soc >= floor - KWH_EPS, Rule.BATTERY_FLOOR, where,
                        String.format("SoC %.1f%% below floor %.0f%%", 100 * soc / v.batteryKWh(),
                                100 * physics.socFloorFraction()));
                check(Math.abs(a.socEndKWh() - soc) <= KWH_EPS, Rule.STATE_MISMATCH, where,
                        String.format("plan says end SoC %.3f, recomputed %.3f", a.socEndKWh(), soc));
                lowestSocPct = Math.min(lowestSocPct, 100 * soc / v.batteryKWh());
                at = a.to();
                clock = a.end();
            }
        }

        private double checkDrive(Vehicle v, String where, Location from, Location to, double km,
                                  int start, int end, double soc) {
            double expectedKm = physics.geo().roadKm(from, to);
            check(Math.abs(km - expectedKm) <= KM_EPS, Rule.TRAVEL_TIME, where,
                    String.format("drive says %.2f km, road distance is %.2f km", km, expectedKm));
            int minMinutes = physics.geo().travelMinutes(expectedKm, start);
            check(end - start >= minMinutes, Rule.TRAVEL_TIME, where,
                    "drive takes " + (end - start) + " min, needs " + minMinutes);
            return soc - physics.driveEnergyKWh(v, expectedKm, start);
        }

        private double checkCharge(Vehicle v, String where, ChargingSession c, Map<String, Hub> hubs,
                                   Map<String, Charger> chargers, double soc) {
            Hub hub = hubs.get(c.hubId());
            Charger charger = chargers.get(c.chargerId());
            if (!check(hub != null && charger != null, Rule.UNKNOWN_REFERENCE, where,
                    "hub " + c.hubId() + " / charger " + c.chargerId())) {
                return c.socEndKWh();
            }
            check(charger.hubId().equals(hub.id()), Rule.CHARGER_UNAVAILABLE, where,
                    "charger " + charger.id() + " is not at hub " + hub.id());
            check(near(c.location(), hub.location()), Rule.LOCATION_CONTINUITY, where,
                    "session location is not the hub");
            check(charger.isWorkingThroughout(c.start(), c.end()), Rule.CHARGER_UNAVAILABLE, where,
                    "charger " + charger.id() + " failed at " + Clock.format(charger.failedFrom()));

            // Integrate minute by minute so the tariff can change mid-session.
            double power = physics.chargePowerKW(v, charger);
            double s = soc;
            for (int m = c.start(); m < c.end(); m++) {
                double next = physics.curve().socAfter(v.batteryKWh(), s, power, 1);
                cost += (next - s) * scenario.tariff().rateAt(m);
                s = next;
            }
            check(s <= v.batteryKWh() + KWH_EPS, Rule.CHARGE_CURVE, where, "charged above capacity");
            check(c.socEndKWh() <= s + KWH_EPS, Rule.CHARGE_CURVE, where,
                    String.format("claims %.3f kWh, curve allows at most %.3f", c.socEndKWh(), s));
            return s;
        }

        /** Sweep per hub: at no instant more sessions than working chargers; per charger, no overlap. */
        private Map<String, Integer> checkChargerCapacity(List<ChargingSession> sessions, Map<String, Hub> hubs) {
            Map<String, List<ChargingSession>> byCharger = new HashMap<>();
            Map<String, List<ChargingSession>> byHub = new HashMap<>();
            for (ChargingSession c : sessions) {
                byCharger.computeIfAbsent(c.chargerId(), k -> new ArrayList<>()).add(c);
                byHub.computeIfAbsent(c.hubId(), k -> new ArrayList<>()).add(c);
            }
            byCharger.forEach((chargerId, list) -> {
                list.sort(Comparator.comparingInt(ChargingSession::start));
                for (int i = 1; i < list.size(); i++) {
                    ChargingSession prev = list.get(i - 1);
                    ChargingSession cur = list.get(i);
                    check(cur.start() >= prev.end(), Rule.CHARGER_DOUBLE_BOOKED, chargerId,
                            "sessions overlap at " + Clock.format(cur.start()));
                }
            });

            Map<String, Integer> peakByHub = new TreeMap<>();
            for (Hub hub : hubs.values()) {
                List<ChargingSession> list = byHub.getOrDefault(hub.id(), List.of());
                TreeMap<Integer, Integer> delta = new TreeMap<>();
                for (ChargingSession c : list) {
                    if (c.end() > c.start()) {
                        delta.merge(c.start(), 1, Integer::sum);
                        delta.merge(c.end(), -1, Integer::sum);
                    }
                }
                // Capacity also changes when a charger fails, so check those instants too.
                for (Charger ch : hub.chargers()) {
                    if (ch.failedFrom() != Charger.WORKING) delta.merge(ch.failedFrom(), 0, Integer::sum);
                }
                int inUse = 0;
                int peak = 0;
                for (var e : delta.entrySet()) {
                    inUse += e.getValue();
                    peak = Math.max(peak, inUse);
                    int minute = e.getKey();
                    long working = hub.chargers().stream().filter(ch -> ch.failedFrom() > minute).count();
                    check(inUse <= working, Rule.HUB_CAPACITY_EXCEEDED, hub.id(),
                            inUse + " cabs charging at " + Clock.format(minute) + " but only " + working + " chargers");
                }
                peakByHub.put(hub.id(), peak);
            }
            return peakByHub;
        }

        private boolean check(boolean ok, Rule rule, String subject, String detail) {
            checks++;
            if (!ok) {
                violationCount++;
                byRule.merge(rule, 1L, Long::sum);
                if (violations.size() < MAX_LISTED) violations.add(new Violation(rule, subject, detail));
            }
            return ok;
        }

        private static boolean near(Location a, Location b) {
            return Math.abs(a.x() - b.x()) <= LOC_EPS && Math.abs(a.y() - b.y()) <= LOC_EPS;
        }
    }
}
