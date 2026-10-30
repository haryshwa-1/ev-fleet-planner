package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Tariff;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Deadhead;
import com.moveinsync.evfleet.core.plan.Plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gets the fleet ready for tomorrow: after each cab's last activity, books a session in the cheapest
 * tariff window that brings it back to a target charge.
 *
 * <p>This is deliberately not a limit on the shift itself. Ending the shift low is the cheap choice
 * as long as cabs can refill at the night rate, so what must hold is that they can. It only appends
 * to a finished plan, so it can never cost a trip, and re-planning never sees it.
 *
 * <p>Cost: O(V log V + V x H x C x log S), one pass over the cabs in the order they finish.
 *
 * @see com.moveinsync.evfleet.core.validate.PlanValidator which checks the plan returned here
 */
public final class OvernightCharging {

    public static final double DEFAULT_TARGET_FRACTION = 0.6;
    private static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * @param plan       the shift plan with the overnight sessions added
     * @param notReady   cabs below the target that could not be booked, or that are out of service
     * @param lastFinish the minute the last session ends
     */
    public record Result(Plan plan, int cabsCharged, double energyKWh, double costRupees, int lastFinish,
                         List<String> notReady) {}

    private final double targetFraction;

    public OvernightCharging(double targetFraction) {
        this.targetFraction = targetFraction;
    }

    public OvernightCharging() {
        this(DEFAULT_TARGET_FRACTION);
    }

    public Result schedule(Scenario scenario, Plan plan) {
        InsertionEvaluator evaluator = new InsertionEvaluator(scenario, PlannerConfig.defaults());
        ChargerBook book = ChargerBook.of(scenario, plan);
        List<CabState> cabs = new ArrayList<>();
        for (Vehicle v : scenario.vehicles()) cabs.add(new CabState(v, plan.timeline(v.id())));
        cabs.sort(Comparator.comparingInt(CabState::freeAt).thenComparing(CabState::id));

        Map<String, List<Activity>> timelines = new LinkedHashMap<>(plan.timelines());
        List<String> notReady = new ArrayList<>();
        int charged = 0;
        int lastFinish = 0;
        double energy = 0;
        double cost = 0;
        for (CabState cab : cabs) {
            double target = cab.vehicle().batteryKWh() * targetFraction;
            if (cab.socKWh() >= target) continue;
            boolean outOfService = cab.timeline().isEmpty() && cab.freeAt() >= scenario.shiftEnd();
            List<Activity> added = outOfService ? null : book(cab, target, evaluator, book);
            if (added == null) {
                notReady.add(cab.id());
                continue;
            }
            ChargingSession session = (ChargingSession) added.get(added.size() - 1);
            book.book(session);
            List<Activity> timeline = new ArrayList<>(cab.timeline());
            timeline.addAll(added);
            timelines.put(cab.id(), timeline);
            charged++;
            lastFinish = Math.max(lastFinish, session.end());
            energy += session.socEndKWh() - session.socStartKWh();
            cost += session.costRupees();
        }
        Plan withOvernight = new Plan(plan.scenarioName(), timelines, plan.unassigned());
        return new Result(withOvernight, charged, energy, cost, lastFinish, List.copyOf(notReady));
    }

    /** The drive and session that finish earliest, trying the nearest hub first; null if none fits. */
    private List<Activity> book(CabState cab, double target, InsertionEvaluator evaluator, ChargerBook book) {
        Scenario scenario = evaluator.scenario();
        FleetPhysics physics = scenario.physics();
        Vehicle v = cab.vehicle();
        List<Activity> best = null;
        int bestEnd = Integer.MAX_VALUE;
        for (Hub hub : evaluator.nearestHubs(cab.location())) {
            double km = physics.geo().roadKm(cab.location(), hub.location());
            int arrive = cab.freeAt() + physics.geo().travelMinutes(km, cab.freeAt());
            double socAtHub = cab.socKWh() - physics.driveEnergyKWh(v, km, cab.freeAt());
            if (socAtHub < physics.floorKWh(v)) continue;
            int windowStart = nextCheapMinute(scenario.tariff(), arrive);
            int windowEnd = endOfCheapWindow(scenario.tariff(), windowStart);
            for (ChargerSchedule schedule : book.atHub(hub.id())) {
                double power = physics.chargePowerKW(v, schedule.charger());
                int minutes = (int) Math.ceil(physics.curve().minutesToReach(v.batteryKWh(), socAtHub, target, power) - 1e-9);
                ChargerSchedule.Gap gap = schedule.firstGap(windowStart, windowEnd, Math.max(1, minutes));
                if (gap == null || gap.start() + minutes >= bestEnd) continue;
                bestEnd = gap.start() + minutes;
                double socEnd = physics.curve().socAfter(v.batteryKWh(), socAtHub, power, minutes);
                double cost = evaluator.chargingCost(v, socAtHub, power, gap.start(), minutes);
                best = new ArrayList<>(2);
                if (km > 0) best.add(new Deadhead(cab.freeAt(), arrive, cab.location(), hub.location(), km, cab.socKWh(), socAtHub));
                best.add(new ChargingSession(hub.id(), schedule.charger().id(), gap.start(), bestEnd, hub.location(), socAtHub, socEnd, cost));
            }
            if (best != null) break;
        }
        return best;
    }

    private static int nextCheapMinute(Tariff tariff, int from) {
        int minute = from;
        while (tariff.rateAt(minute) > tariff.cheapestRate() && minute < from + MINUTES_PER_DAY) minute++;
        return minute;
    }

    private static int endOfCheapWindow(Tariff tariff, int start) {
        int minute = start;
        while (tariff.rateAt(minute) <= tariff.cheapestRate() && minute < start + MINUTES_PER_DAY) minute++;
        return minute;
    }
}
