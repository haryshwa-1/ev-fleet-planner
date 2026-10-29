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
import java.util.List;

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
        double best = Double.MAX_VALUE;
        for (Hub h : hubs) {
            best = Math.min(best, physics.consumption().worstCaseEnergyKWh(v, physics.geo().roadKm(at, h.location())));
        }
        return best;
    }

    /** True if even a full battery cannot do this trip and still reach a charger. */
    public boolean exceedsRange(Vehicle v, Trip t) {
        double need = physics.tripEnergyKWh(v, t) + reserveKWh(v, t.end()) + physics.floorKWh(v);
        return need > v.batteryKWh();
    }

    /** Best option for this cab, or null. Updates {@code diag} with why it failed. */
    public Insertion evaluate(CabState cab, Trip trip, ChargerBook book, Diagnosis diag) {
        Insertion direct = direct(cab, trip, diag);
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

        double socAtPickup = cab.socKWh() - physics.consumption().energyKWh(v, km, depart);
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

        List<Hub> nearest = hubs.stream()
                .sorted(Comparator.comparingDouble(h -> physics.geo().roadKm(cab.location(), h.location())))
                .limit(config.hubsToTry()).toList();
        for (Hub hub : nearest) {
            int depart = cab.freeAt();
            double toHubKm = physics.geo().roadKm(cab.location(), hub.location());
            int arriveHub = depart + physics.geo().travelMinutes(toHubKm, depart);
            double socAtHub = cab.socKWh() - physics.consumption().energyKWh(v, toHubKm, depart);
            if (socAtHub < floor) continue;

            double toPickupKm = physics.geo().roadKm(hub.location(), trip.start());
            int leaveBy = trip.startTime() - physics.geo().worstCaseTravelMinutes(toPickupKm);
            double required = afterTripNeed + tripEnergy + physics.consumption().worstCaseEnergyKWh(v, toPickupKm);
            if (required > v.batteryKWh()) continue;
            diag.anyReachable |= arriveHub <= leaveBy;

            for (ChargerSchedule schedule : book.atHub(hub.id())) {
                double power = physics.chargePowerKW(v, schedule.charger());
                int minMinutes = Math.max(config.minChargeMinutes(),
                        (int) Math.ceil(physics.curve().minutesToReach(v.batteryKWh(), socAtHub, required, power) - 1e-9));
                if (arriveHub + minMinutes > leaveBy) continue;
                diag.anyChargeFitsInTime = true;

                ChargerSchedule.Gap gap = schedule.firstGap(arriveHub, leaveBy, minMinutes);
                if (gap == null) continue;
                double target = Math.max(required, v.batteryKWh() * config.chargeTargetFraction());
                int wanted = (int) Math.ceil(physics.curve().minutesToReach(v.batteryKWh(), socAtHub,
                        Math.min(target, v.batteryKWh()), power) - 1e-9);
                int duration = Math.max(minMinutes, Math.min(wanted, gap.length()));
                Insertion option = build(cab, trip, hub, schedule, depart, toHubKm, arriveHub, socAtHub,
                        gap.start(), duration, toPickupKm, power);
                if (option != null && (best == null || option.score() < best.score())) best = option;
            }
        }
        return best;
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
        double socAtPickup = socCharged - physics.consumption().energyKWh(v, toPickupKm, chargeEnd);
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
