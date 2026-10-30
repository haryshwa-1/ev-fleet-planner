package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Tariff;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Deadhead;
import com.moveinsync.evfleet.core.plan.TripLeg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out whether a cab can serve a trip next, either directly or by charging first, and
 * records why not when it cannot. Shared by initial planning and live re-planning.
 *
 * <p>Every option it returns already satisfies the hard rules for the activities it adds,
 * plus the reachability reserve: after the trip the cab can still get to the nearest hub
 * without dropping below the floor, so no later step can strand it.
 */
public final class InsertionEvaluator {

    /** What went wrong across all cabs for one trip, used to pick a reason code. */
    public static final class Diagnosis {
        boolean anyReachable;
        boolean anyChargeFitsInTime;
        int bestLatenessMinutes = Integer.MAX_VALUE;
        double smallestEnergyShortfallKWh = Double.MAX_VALUE;
    }

    private final Scenario scenario;
    private final FleetPhysics physics;
    private final Tariff tariff;
    private final PlannerConfig config;
    private final List<Hub> hubs;
    private final Map<Location, List<Hub>> hubsByDistance = new HashMap<>();

    public InsertionEvaluator(Scenario scenario, PlannerConfig config) {
        this.scenario = scenario;
        this.physics = scenario.physics();
        this.tariff = scenario.tariff();
        this.config = config;
        this.hubs = scenario.hubs();
    }

    public int tripMinutes(Trip t) {
        return physics.tripMinutes(t);
    }

    /** Energy to the nearest hub from {@code at}, assuming the worst traffic. */
    public double reserveKWh(Vehicle v, Location at) {
        return physics.worstCaseDriveEnergyKWh(v, physics.geo().roadKm(at, nearestHubs(at).get(0).location()));
    }

    /** All hubs, nearest first. Cabs revisit the same few places, so each location is sorted only once. */
    private List<Hub> nearestHubs(Location at) {
        return hubsByDistance.computeIfAbsent(at, l -> hubs.stream()
                .sorted(Comparator.comparingDouble(h -> physics.geo().roadKm(l, h.location()))).toList());
    }

    /** True if even a full battery cannot do this trip and still reach a charger. */
    public boolean exceedsRange(Vehicle v, Trip t) {
        double need = physics.tripEnergyKWh(v, t) + reserveKWh(v, t.end()) + physics.floorKWh(v);
        return need > v.batteryKWh();
    }

    /** Best option for this cab, or null. Updates {@code diag} with why it failed. */
    public Insertion evaluate(CabState cab, Trip trip, ChargerBook book, Diagnosis diag) {
        Insertion direct = direct(cab, trip, diag);
        if (direct == null && cab.freeAt() > trip.startTime()) return null; // already too late to charge first
        boolean wantsCharge = direct == null || direct.socAfterKWh() < cab.vehicle().batteryKWh() * config.lowSocFraction();
        if (!wantsCharge) return direct;
        Insertion viaHub = chargeThenServe(cab, trip, book, diag);
        if (direct == null) return viaHub;
        if (viaHub == null) return direct;
        return viaHub.score() < direct.score() ? viaHub : direct;
    }

    private Insertion direct(CabState cab, Trip trip, Diagnosis diag) {
        Vehicle v = cab.vehicle();
        double floor = physics.floorKWh(v);
        int depart = cab.freeAt();
        double km = physics.geo().roadKm(cab.location(), trip.start());
        int arrive = depart + physics.geo().travelMinutes(km, depart);
        if (arrive > trip.startTime()) {
            diag.bestLatenessMinutes = Math.min(diag.bestLatenessMinutes, arrive - trip.startTime());
            return null;
        }
        diag.anyReachable = true;

        double socAtPickup = cab.socKWh() - physics.driveEnergyKWh(v, km, depart);
        double socAfter = socAtPickup - physics.tripEnergyKWh(v, trip);
        double needed = floor + reserveKWh(v, trip.end());
        if (socAtPickup < floor || socAfter < needed) {
            diag.smallestEnergyShortfallKWh = Math.min(diag.smallestEnergyShortfallKWh, needed - socAfter);
            return null;
        }

        List<Activity> acts = new ArrayList<>(2);
        if (km > 0) acts.add(new Deadhead(depart, arrive, cab.location(), trip.start(), km, cab.socKWh(), socAtPickup));
        acts.add(tripLeg(trip, socAtPickup, socAfter));
        double score = km + lowSocPenalty(v, socAfter) + idlePenalty(trip.startTime() - arrive);
        return new Insertion(cab, trip.id(), acts, score, socAfter);
    }

    private Insertion chargeThenServe(CabState cab, Trip trip, ChargerBook book, Diagnosis diag) {
        Vehicle v = cab.vehicle();
        double floor = physics.floorKWh(v);
        double tripEnergy = physics.tripEnergyKWh(v, trip);
        double afterTripNeed = floor + reserveKWh(v, trip.end());
        Insertion best = null;

        List<Hub> nearest = nearestHubs(cab.location()).subList(0, Math.min(config.hubsToTry(), hubs.size()));
        for (Hub hub : nearest) {
            int depart = cab.freeAt();
            double toHubKm = physics.geo().roadKm(cab.location(), hub.location());
            int arriveHub = depart + physics.geo().travelMinutes(toHubKm, depart);
            double socAtHub = cab.socKWh() - physics.driveEnergyKWh(v, toHubKm, depart);
            if (socAtHub < floor) continue;

            double toPickupKm = physics.geo().roadKm(hub.location(), trip.start());
            int leaveBy = trip.startTime() - physics.geo().worstCaseTravelMinutes(toPickupKm);
            double required = afterTripNeed + tripEnergy + physics.worstCaseDriveEnergyKWh(v, toPickupKm);
            if (required > v.batteryKWh()) continue;
            diag.anyReachable |= arriveHub <= leaveBy;
            if (arriveHub > leaveBy) continue;

            for (ChargerSchedule schedule : book.atHub(hub.id())) {
                double power = physics.chargePowerKW(v, schedule.charger());
                int minMinutes = Math.max(config.minChargeMinutes(),
                        (int) Math.ceil(physics.curve().minutesToReach(v.batteryKWh(), socAtHub, required, power) - 1e-9));
                if (arriveHub + minMinutes > leaveBy) continue;
                diag.anyChargeFitsInTime = true;

                ChargerSchedule.Gap gap = schedule.firstGap(arriveHub, leaveBy, minMinutes);
                if (gap == null) continue;
                // Try plugging in now and at every later tariff change the gap allows; the score
                // keeps the cheapest. How much to charge depends on the price at the start.
                for (int start : candidateStarts(gap.start(), gap.end() - minMinutes)) {
                    int duration = sessionMinutes(v, socAtHub, required, power, start, gap.end(), minMinutes);
                    Insertion option = build(cab, trip, hub, schedule, depart, toHubKm, arriveHub, socAtHub,
                            start, duration, toPickupKm, power);
                    if (option != null && (best == null || option.score() < best.score())) best = option;
                }
            }
        }
        return best;
    }

    /**
     * Start times worth trying in a gap: its first minute and every minute in (from, latest] where
     * the tariff changes. Between two changes the price is flat, so starting later gains nothing.
     */
    private List<Integer> candidateStarts(int from, int latest) {
        List<Integer> starts = new ArrayList<>(4);
        starts.add(from);
        for (int m = from + 1; m <= latest; m++) {
            if (tariff.rateAt(m) != tariff.rateAt(m - 1)) starts.add(m);
        }
        return starts;
    }

    /**
     * How long to charge from {@code start}: at least enough for this trip, and up to the target
     * share while the gap lasts. At a peak rate the target is lower, so the cab buys little more than
     * it needs and tops up when power is cheaper. (Charging "just enough" at every price looks
     * cheapest per booking but leaves cabs empty for the next peak; the multi-start comparison
     * showed ~10% higher net cost.)
     */
    private int sessionMinutes(Vehicle v, double socAtHub, double required, double power, int start, int gapEnd,
                               int minMinutes) {
        boolean peak = tariff.rateAt(start) > tariff.defaultRate();
        double fraction = peak ? config.peakTargetFraction() : config.chargeTargetFraction();
        double target = Math.min(Math.max(required, v.batteryKWh() * fraction), v.batteryKWh());
        int wanted = (int) Math.ceil(physics.curve().minutesToReach(v.batteryKWh(), socAtHub, target, power) - 1e-9);
        return Math.max(minMinutes, Math.min(wanted, gapEnd - start));
    }

    private Insertion build(CabState cab, Trip trip, Hub hub, ChargerSchedule schedule, int depart, double toHubKm,
                            int arriveHub, double socAtHub, int chargeStart, int duration, double toPickupKm,
                            double power) {
        Vehicle v = cab.vehicle();
        double floor = physics.floorKWh(v);
        int chargeEnd = chargeStart + duration;
        double socCharged = physics.curve().socAfter(v.batteryKWh(), socAtHub, power, duration);
        double cost = chargingCost(v, socAtHub, power, chargeStart, duration);
        int arrivePickup = chargeEnd + physics.geo().travelMinutes(toPickupKm, chargeEnd);
        double socAtPickup = socCharged - physics.driveEnergyKWh(v, toPickupKm, chargeEnd);
        double socAfter = socAtPickup - physics.tripEnergyKWh(v, trip);
        if (arrivePickup > trip.startTime() || socAtPickup < floor
                || socAfter < floor + reserveKWh(v, trip.end())) {
            return null; // defensive: the worst-case sizing above should already exclude this
        }

        List<Activity> acts = new ArrayList<>(4);
        if (toHubKm > 0) acts.add(new Deadhead(cab.freeAt(), arriveHub, cab.location(), hub.location(), toHubKm,
                cab.socKWh(), socAtHub));
        acts.add(new ChargingSession(hub.id(), schedule.charger().id(), chargeStart, chargeEnd, hub.location(),
                socAtHub, socCharged, cost));
        if (toPickupKm > 0) acts.add(new Deadhead(chargeEnd, arrivePickup, hub.location(), trip.start(), toPickupKm,
                socCharged, socAtPickup));
        acts.add(tripLeg(trip, socAtPickup, socAfter));

        double score = toHubKm + toPickupKm + config.chargeDetourPenaltyKm()
                + cost / config.rupeesPerKmEquivalent() + lowSocPenalty(v, socAfter)
                + idlePenalty(trip.startTime() - arrivePickup);
        return new Insertion(cab, trip.id(), acts, score, socAfter);
    }

    /** Integrates the tariff minute by minute, since a session may straddle a tariff boundary. */
    public double chargingCost(Vehicle v, double socStart, double power, int start, int minutes) {
        double cost = 0;
        double soc = socStart;
        for (int m = start; m < start + minutes; m++) {
            double next = physics.curve().socAfter(v.batteryKWh(), soc, power, 1);
            cost += (next - soc) * tariff.rateAt(m);
            soc = next;
        }
        return cost;
    }

    private TripLeg tripLeg(Trip t, double socStart, double socEnd) {
        return new TripLeg(t.id(), t.startTime(), t.startTime() + physics.tripMinutes(t), t.start(), t.end(),
                t.distanceKm(), socStart, socEnd);
    }

    private double idlePenalty(int idleMinutes) {
        return config.idleKmPerHour() * idleMinutes / 60.0;
    }

    private double lowSocPenalty(Vehicle v, double socAfter) {
        return socAfter < v.batteryKWh() * config.lowSocFraction() ? config.lowSocPenaltyKm() : 0;
    }

    public Scenario scenario() {
        return scenario;
    }
}
