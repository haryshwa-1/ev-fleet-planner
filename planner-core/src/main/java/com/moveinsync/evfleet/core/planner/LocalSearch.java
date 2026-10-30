package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.ReasonCode;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import com.moveinsync.evfleet.core.validate.PlanValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Improves a finished plan. Each step picks a backup trip and a few cabs that are near its pickup,
 * clears those cabs' work from a moment before it, and rebuilds that work with the same insertion
 * rule as initial planning, with some randomness. A rebuild is kept only if it means fewer backup
 * trips, or the same number at a lower net cost.
 *
 * <p>Takes a plan and returns a plan, so nothing else depends on it. A fixed seed and step count, not
 * a timer, make the result repeatable on any machine. If the result ever fails the validator, the
 * original plan is returned.
 *
 * <p>Cost: O(S x (V + F x K x H x C x log B)) for S steps, F trips freed per step, K cabs per step,
 * and H, C and B as in insertion. Memory is O(V + T).
 */
public final class LocalSearch {

    /**
     * @param plan  the improved plan, or the original if nothing better was found
     * @param steps steps attempted
     * @param kept  rebuilds that were an improvement
     */
    public record Result(Plan plan, int steps, int kept) {}

    private static final Set<ReasonCode> HOPELESS = Set.of(ReasonCode.TRIP_EXCEEDS_EV_RANGE, ReasonCode.LATEST_FINISH_INFEASIBLE);
    private static final double[] IDLE_WEIGHTS = {1, 2, 4, 8};
    private static final int NEAREST_POOL = 25;
    private static final double SCORE_NOISE = 0.25;

    private final int steps;
    private final int cabsPerStep;
    private final long seed;

    public LocalSearch(int steps, int cabsPerStep, long seed) {
        this.steps = steps;
        this.cabsPerStep = cabsPerStep;
        this.seed = seed;
    }

    public LocalSearch() {
        this(5_000, 5, 1L);
    }

    public Result improve(Scenario scenario, Plan start) {
        Random rnd = new Random(seed);
        Map<String, Trip> trips = scenario.tripsById();
        Map<String, Vehicle> vehicles = scenario.vehiclesById();
        List<InsertionEvaluator> evaluators = new ArrayList<>();
        for (double idle : IDLE_WEIGHTS) evaluators.add(new InsertionEvaluator(scenario, PlannerConfig.defaults().with("idle", idle)));

        Map<String, List<Activity>> timelines = new LinkedHashMap<>(start.timelines());
        Map<String, UnassignedTrip> backups = new LinkedHashMap<>();
        start.unassigned().forEach(u -> backups.put(u.tripId(), u));
        ChargerBook book = ChargerBook.of(scenario, start);

        int attempted = 0;
        int kept = 0;
        for (int step = 0; step < steps; step++) {
            List<String> targets = backups.values().stream().filter(u -> !HOPELESS.contains(u.reason()))
                    .map(UnassignedTrip::tripId).toList();
            if (targets.isEmpty()) break;
            attempted++;
            Trip target = trips.get(targets.get(rnd.nextInt(targets.size())));
            int cutoff = target.startTime() - 30 - rnd.nextInt(121);
            List<String> cabs = nearbyCabs(scenario, timelines, target, rnd);
            InsertionEvaluator evaluator = evaluators.get(rnd.nextInt(evaluators.size()));
            if (rebuild(scenario, vehicles, trips, timelines, backups, book, cabs, cutoff, evaluator, rnd)) kept++;
        }

        Plan improved = new Plan(start.scenarioName(), orderedLike(start, timelines), List.copyOf(backups.values()));
        boolean valid = new PlanValidator().validate(scenario, improved).valid();
        return valid ? new Result(improved, attempted, kept) : new Result(start, attempted, 0);
    }

    /** Clears the cabs from {@code cutoff}, re-inserts the trips, and keeps the result only if better. */
    private static boolean rebuild(Scenario scenario, Map<String, Vehicle> vehicles, Map<String, Trip> trips,
                                   Map<String, List<Activity>> timelines, Map<String, UnassignedTrip> backups,
                                   ChargerBook book, List<String> cabs, int cutoff, InsertionEvaluator evaluator, Random rnd) {
        Map<String, CabState> states = new LinkedHashMap<>();
        Map<String, Integer> keptSizes = new HashMap<>();
        List<Trip> freed = new ArrayList<>();
        List<ChargingSession> released = new ArrayList<>();
        for (String id : cabs) {
            List<Activity> timeline = timelines.get(id);
            int keep = 0;
            while (keep < timeline.size() && timeline.get(keep).end() <= cutoff) keep++;
            // A drive or charge kept without the trip it led to would be pointless.
            while (keep > 0 && !(timeline.get(keep - 1) instanceof TripLeg)) keep--;
            for (Activity a : timeline.subList(keep, timeline.size())) {
                if (a instanceof TripLeg leg) freed.add(trips.get(leg.tripId()));
                if (a instanceof ChargingSession c) released.add(c);
            }
            states.put(id, new CabState(vehicles.get(id), timeline.subList(0, keep)));
            keptSizes.put(id, keep);
        }
        List<Trip> oldBackups = new ArrayList<>();
        for (UnassignedTrip u : backups.values()) {
            Trip t = trips.get(u.tripId());
            if (!HOPELESS.contains(u.reason()) && t.startTime() >= cutoff) oldBackups.add(t);
        }
        freed.addAll(oldBackups);
        freed.sort(Comparator.comparingInt(Trip::startTime).thenComparing(Trip::id));
        released.forEach(book::release);

        List<UnassignedTrip> newBackups = new ArrayList<>();
        for (Trip trip : freed) {
            InsertionEvaluator.Diagnosis diag = new InsertionEvaluator.Diagnosis();
            Insertion best = null;
            double bestScore = Double.MAX_VALUE;
            for (CabState cab : states.values()) {
                Insertion option = evaluator.evaluate(cab, trip, book, diag);
                if (option == null) continue;
                double score = option.score() * (1 + SCORE_NOISE * rnd.nextDouble());
                if (score < bestScore) {
                    best = option;
                    bestScore = score;
                }
            }
            if (best == null) newBackups.add(GreedyPlanner.explain(trip, diag));
            else GreedyPlanner.commit(best, book);
        }

        double oldCost = 0;
        double newCost = 0;
        for (Map.Entry<String, CabState> e : states.entrySet()) {
            Vehicle v = vehicles.get(e.getKey());
            oldCost += netCost(scenario, v, timelines.get(e.getKey()));
            newCost += netCost(scenario, v, e.getValue().timeline());
        }
        boolean better = newBackups.size() < oldBackups.size()
                || (newBackups.size() == oldBackups.size() && newCost < oldCost - 1e-6);
        if (!better) {
            states.forEach((id, cab) -> {
                List<Activity> timeline = cab.timeline();
                for (Activity a : timeline.subList(keptSizes.get(id), timeline.size())) {
                    if (a instanceof ChargingSession c) book.release(c);
                }
            });
            released.forEach(book::book);
            return false;
        }

        states.forEach((id, cab) -> timelines.put(id, cab.timeline()));
        Map<String, UnassignedTrip> previous = new HashMap<>(backups);
        oldBackups.forEach(t -> backups.remove(t.id()));
        // A trip that is still a backup keeps the reason from when every cab was considered.
        for (UnassignedTrip u : newBackups) backups.put(u.tripId(), previous.getOrDefault(u.tripId(), u));
        return true;
    }

    /** Charging bought plus refilling at the cheapest rate; the same measure multi-start ranks by. */
    private static double netCost(Scenario scenario, Vehicle v, List<Activity> timeline) {
        double cost = 0;
        for (Activity a : timeline) if (a instanceof ChargingSession c) cost += c.costRupees();
        double end = timeline.isEmpty() ? v.initialSocKWh() : timeline.get(timeline.size() - 1).socEndKWh();
        return cost - end * scenario.tariff().cheapestRate();
    }

    /** A random few of the cabs closest to the pickup around the time of the trip. */
    private List<String> nearbyCabs(Scenario scenario, Map<String, List<Activity>> timelines, Trip target, Random rnd) {
        Map<String, Double> distance = new HashMap<>();
        for (Vehicle v : scenario.vehicles()) {
            var at = v.location();
            for (Activity a : timelines.get(v.id())) {
                if (a.end() > target.startTime()) break;
                at = a.to();
            }
            distance.put(v.id(), at.straightLineKm(target.start()));
        }
        List<String> pool = new ArrayList<>(distance.keySet());
        pool.sort(Comparator.comparingDouble((String id) -> distance.get(id)).thenComparing(id -> id));
        pool = new ArrayList<>(pool.subList(0, Math.min(NEAREST_POOL, pool.size())));
        java.util.Collections.shuffle(pool, rnd);
        return pool.subList(0, Math.min(cabsPerStep, pool.size()));
    }

    private static Map<String, List<Activity>> orderedLike(Plan start, Map<String, List<Activity>> timelines) {
        Map<String, List<Activity>> ordered = new LinkedHashMap<>();
        start.timelines().keySet().forEach(id -> ordered.put(id, timelines.get(id)));
        return ordered;
    }
}
