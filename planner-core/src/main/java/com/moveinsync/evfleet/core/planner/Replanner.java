package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Charger;
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
import com.moveinsync.evfleet.core.validate.PlanValidator;
import com.moveinsync.evfleet.core.validate.ValidationReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repairs a plan after something changes mid-shift, touching as few cabs as possible.
 *
 * <p>Steps: take a {@link Snapshot} at the event time, apply the event to it, free the future work
 * of the affected cabs plus the cabs nearest to the event, and re-insert those trips (and any
 * not-yet-started backup trips) with the same insertion rule as initial planning. A trip stays with
 * its original cab whenever that cab can still do it, and a cab whose situation did not change keeps
 * its exact original steps, so drivers are not reshuffled for nothing.
 * If a trip is lost, a second pass widens the neighbourhood while time budget remains.
 *
 * <p>Cost per pass: O(T_n x N_n x H x C) for T_n freed trips and N_n cabs in the neighbourhood,
 * independent of fleet size apart from building the snapshot and charger book (O(activities)).
 */
public final class Replanner {

    /** A cab reports its state of charge, which differs from what the plan expected. */
    public record DriftEvent(String cabId, int minute, double socKWh) {}

    /** A charger goes out of service from {@code minute} for the rest of the shift. */
    public record ChargerFailure(String chargerId, int minute) {}

    /**
     * @param before   the snapshot's plan, judged against the changed situation (usually not valid)
     * @param after    the repaired plan for the rest of the shift
     * @param report   the validator's verdict on {@code after}
     */
    public record Result(Snapshot snapshot, Plan before, Plan after, PlanDiff diff, ValidationReport report,
                         int neighbourhood, long millis) {}

    private static final int[] NEIGHBOURHOODS = {10, 40};

    private final PlannerConfig config;
    private final long budgetMillis;

    public Replanner(PlannerConfig config, long budgetMillis) {
        this.config = config;
        this.budgetMillis = budgetMillis;
    }

    public Replanner() {
        this(PlannerConfig.defaults(), 2_000);
    }

    public Result onDrift(Scenario scenario, Plan plan, DriftEvent e) {
        long t0 = System.nanoTime();
        Snapshot snap = Snapshot.of(scenario, plan, e.minute());
        Vehicle v = snap.scenario().vehiclesById().get(e.cabId());
        if (v == null) throw new IllegalArgumentException("unknown cab " + e.cabId());

        // The cab's start state in the snapshot is before any activity in progress; shift it by the
        // drift measured now, and replay the activity in progress from the corrected charge.
        Activity current = snap.inProgress(e.cabId());
        double plannedNow = current == null ? v.initialSocKWh() : socAt(current, e.minute());
        double corrected = clamp(v.initialSocKWh() + (e.socKWh() - plannedNow), 0, v.batteryKWh());
        Vehicle actual = v.withSoc(corrected);
        List<Vehicle> vehicles = snap.scenario().vehicles().stream()
                .map(x -> x.id().equals(e.cabId()) ? actual : x).toList();
        Snapshot changed = snap.withScenario(snap.scenario().withVehicles(vehicles));

        InsertionEvaluator evaluator = new InsertionEvaluator(changed.scenario(), config);
        Map<String, List<Activity>> frozen = new HashMap<>();
        frozen.put(e.cabId(), current == null ? List.of() : List.of(replay(current, corrected, actual, evaluator)));
        Location focus = current == null ? actual.location() : current.to();
        return repair(changed, frozen, focus, t0);
    }

    public Result onChargerFailure(Scenario scenario, Plan plan, ChargerFailure e) {
        long t0 = System.nanoTime();
        Snapshot snap = Snapshot.of(scenario, plan, e.minute());
        Charger failed = snap.scenario().chargersById().get(e.chargerId());
        if (failed == null) throw new IllegalArgumentException("unknown charger " + e.chargerId());

        List<Hub> hubs = snap.scenario().hubs().stream().map(h -> new Hub(h.id(), h.location(),
                h.chargers().stream().map(c -> c.id().equals(e.chargerId()) ? c.failedAt(e.minute()) : c).toList()))
                .toList();
        Snapshot changed = snap.withScenario(snap.scenario().withHubs(hubs));
        InsertionEvaluator evaluator = new InsertionEvaluator(changed.scenario(), config);
        Map<String, Vehicle> vehicles = changed.scenario().vehiclesById();

        // Every cab with a session on the charger that runs past the failure must be re-planned.
        // One charging there right now keeps what it gained until the failure.
        Map<String, List<Activity>> frozen = new HashMap<>();
        changed.plan().timelines().forEach((cab, rest) -> {
            boolean hit = rest.stream().anyMatch(a -> a instanceof ChargingSession c
                    && c.chargerId().equals(e.chargerId()) && c.end() > e.minute());
            if (!hit) return;
            Activity current = changed.inProgress(cab);
            if (current instanceof ChargingSession c && c.chargerId().equals(e.chargerId())) {
                frozen.put(cab, List.of(truncate(c, e.minute(), vehicles.get(cab), failed, evaluator)));
            } else {
                frozen.put(cab, current == null ? List.of() : List.of(current));
            }
        });
        Location focus = changed.scenario().hubsById().get(failed.hubId()).location();
        return repair(changed, frozen, focus, t0);
    }

    /**
     * Re-plans the cabs in {@code mustReplan} (keys: cab, values: what it keeps doing first) and
     * their neighbours; every other cab keeps its plan unchanged.
     */
    private Result repair(Snapshot snap, Map<String, List<Activity>> mustReplan, Location focus, long t0) {
        Plan before = snap.plan();
        Result best = null;
        for (int size : NEIGHBOURHOODS) {
            if (best != null && (lostTrips(best) == 0 || elapsed(t0) > budgetMillis / 2)) break;
            Plan after = pass(snap, mustReplan, neighbours(snap, mustReplan.keySet(), focus, size));
            PlanDiff diff = PlanDiff.between(before, after);
            Result r = new Result(snap, before, after, diff, null, size, 0);
            if (best == null || after.unassigned().size() < best.after().unassigned().size()) best = r;
        }
        ValidationReport report = new PlanValidator().validate(snap.scenario(), best.after());
        return new Result(snap, before, best.after(), best.diff(), report, best.neighbourhood(), elapsed(t0));
    }

    private Plan pass(Snapshot snap, Map<String, List<Activity>> mustReplan, Set<String> replanned) {
        Scenario scenario = snap.scenario();
        Plan before = snap.plan();
        InsertionEvaluator evaluator = new InsertionEvaluator(scenario, config);
        Map<String, Vehicle> vehicles = scenario.vehiclesById();
        Map<String, Trip> trips = scenario.tripsById();

        // Cabs being re-planned keep only what they are doing right now (or what the event left them).
        ChargerBook book = new ChargerBook(scenario);
        Map<String, CabState> states = new LinkedHashMap<>();
        Map<String, String> owner = new HashMap<>();
        List<Trip> freed = new ArrayList<>();
        Map<String, List<Activity>> segments = new HashMap<>();
        before.timelines().forEach((cab, rest) -> {
            if (!replanned.contains(cab)) {
                rest.forEach(a -> { if (a instanceof ChargingSession c) book.book(c); });
                return;
            }
            List<Activity> keep = mustReplan.containsKey(cab) ? mustReplan.get(cab)
                    : snap.inProgress(cab) == null ? List.of() : List.of(snap.inProgress(cab));
            keep.forEach(a -> { if (a instanceof ChargingSession c) book.book(c); });
            states.put(cab, new CabState(vehicles.get(cab), keep));
            List<Activity> segment = new ArrayList<>();
            for (Activity a : rest) {
                if (a.start() < snap.minute()) continue; // in progress: kept above
                segment.add(a);
                if (a instanceof TripLeg leg) {
                    freed.add(trips.get(leg.tripId()));
                    owner.put(leg.tripId(), cab);
                    segments.put(leg.tripId(), List.copyOf(segment));
                    segment.clear();
                }
            }
        });
        for (UnassignedTrip u : before.unassigned()) freed.add(trips.get(u.tripId()));
        freed.sort(Comparator.comparingInt(Trip::startTime).thenComparing(Trip::id));

        // Each cab's own trips still waiting to be re-inserted, in time order.
        Map<String, java.util.ArrayDeque<Trip>> pending = new HashMap<>();
        for (Trip t : freed) {
            String cab = owner.get(t.id());
            if (cab != null) pending.computeIfAbsent(cab, k -> new java.util.ArrayDeque<>()).add(t);
        }

        List<UnassignedTrip> unassigned = new ArrayList<>();
        for (Trip trip : freed) {
            CabState original = states.get(owner.get(trip.id()));
            if (original != null) pending.get(original.id()).poll();
            if (original != null && reuse(original, segments.get(trip.id()), book)) continue;
            UnassignedTrip rejected = assignSticky(trip, states.values(), original, pending, evaluator, book);
            if (rejected != null) unassigned.add(rejected);
        }

        Map<String, List<Activity>> timelines = new LinkedHashMap<>();
        before.timelines().forEach((cab, rest) ->
                timelines.put(cab, states.containsKey(cab) ? states.get(cab).timeline() : rest));
        return new Plan(before.scenarioName(), timelines, unassigned);
    }

    /**
     * Keeps the original steps to a trip unchanged when the cab is exactly where, when and as charged
     * as the old plan expected and the charger slot is still free. Returns true if it did.
     */
    private static boolean reuse(CabState cab, List<Activity> segment, ChargerBook book) {
        Activity first = segment.get(0);
        if (first.start() < cab.freeAt() || !first.from().equals(cab.location())
                || Math.abs(first.socStartKWh() - cab.socKWh()) > 1e-6) {
            return false;
        }
        for (Activity a : segment) {
            if (a instanceof ChargingSession c && !book.charger(c.chargerId()).isFree(c.start(), c.end())) return false;
        }
        segment.forEach(a -> { if (a instanceof ChargingSession c) book.book(c); });
        cab.appendAll(segment);
        return true;
    }

    /**
     * Like {@link GreedyPlanner#assign}, but a trip stays with its original cab whenever that cab can
     * still do it: in a live re-plan, every moved trip is a driver to call. When it must move, a cab
     * that can still do its own next trip afterwards is preferred, so one change does not knock on
     * into a chain of them.
     */
    private static UnassignedTrip assignSticky(Trip trip, Iterable<CabState> candidates, CabState original,
                                               Map<String, java.util.ArrayDeque<Trip>> pending,
                                               InsertionEvaluator evaluator, ChargerBook book) {
        UnassignedTrip intrinsic = GreedyPlanner.intrinsicReason(trip, evaluator);
        if (intrinsic != null) return intrinsic;
        InsertionEvaluator.Diagnosis diag = new InsertionEvaluator.Diagnosis();
        Insertion best = original == null ? null : evaluator.evaluate(original, trip, book, diag);
        if (best == null) {
            boolean bestBumps = true;
            for (CabState cab : candidates) {
                if (cab == original) continue;
                Insertion option = evaluator.evaluate(cab, trip, book, diag);
                if (option == null) continue;
                boolean bumps = bumpsOwnTrip(cab, option, pending.get(cab.id()), evaluator, book);
                if (best == null || (bestBumps && !bumps)
                        || (bumps == bestBumps && option.score() < best.score())) {
                    best = option;
                    bestBumps = bumps;
                }
            }
        }
        if (best == null) return GreedyPlanner.explain(trip, diag);
        GreedyPlanner.commit(best, book);
        return null;
    }

    /** True if, after taking {@code option}, the cab could no longer do the next trip it already had. */
    private static boolean bumpsOwnTrip(CabState cab, Insertion option, java.util.ArrayDeque<Trip> own,
                                        InsertionEvaluator evaluator, ChargerBook book) {
        if (own == null || own.isEmpty()) return false;
        List<Activity> after = new ArrayList<>(cab.timeline());
        after.addAll(option.activities());
        CabState trial = new CabState(cab.vehicle(), after);
        return evaluator.evaluate(trial, own.peek(), book, new InsertionEvaluator.Diagnosis()) == null;
    }

    /** The cabs that must be re-planned plus the {@code size} others closest to the event. */
    private static Set<String> neighbours(Snapshot snap, Set<String> mustReplan, Location focus, int size) {
        Set<String> chosen = new LinkedHashSet<>(mustReplan);
        Map<String, Vehicle> vehicles = snap.scenario().vehiclesById();
        snap.plan().timelines().keySet().stream()
                .filter(cab -> !mustReplan.contains(cab))
                .sorted(Comparator.comparingDouble((String cab) -> positionNow(snap, vehicles.get(cab))
                        .straightLineKm(focus)).thenComparing(cab -> cab))
                .limit(size)
                .forEach(chosen::add);
        return chosen;
    }

    /** Where a cab will be once its current activity ends. */
    private static Location positionNow(Snapshot snap, Vehicle v) {
        Activity current = snap.inProgress(v.id());
        return current == null ? v.location() : current.to();
    }

    /** Planned charge at {@code minute} inside an activity; charge changes roughly linearly within one. */
    private static double socAt(Activity a, int minute) {
        if (a.end() == a.start()) return a.socEndKWh();
        double f = (double) (minute - a.start()) / (a.end() - a.start());
        return a.socStartKWh() + f * (a.socEndKWh() - a.socStartKWh());
    }

    /** The same activity done from a different starting charge. */
    private static Activity replay(Activity a, double socStart, Vehicle v, InsertionEvaluator evaluator) {
        double shift = socStart - a.socStartKWh();
        return switch (a) {
            case Deadhead d -> new Deadhead(d.start(), d.end(), d.from(), d.to(), d.km(), socStart,
                    d.socEndKWh() + shift);
            case TripLeg t -> new TripLeg(t.tripId(), t.start(), t.end(), t.from(), t.to(), t.km(), socStart,
                    t.socEndKWh() + shift);
            case ChargingSession c -> {
                Charger charger = evaluator.scenario().chargersById().get(c.chargerId());
                yield session(c, c.end(), socStart, v, charger, evaluator);
            }
        };
    }

    /** A session cut short at {@code minute}: the cab keeps what it gained until then. */
    private static ChargingSession truncate(ChargingSession c, int minute, Vehicle v, Charger charger,
                                            InsertionEvaluator evaluator) {
        return session(c, minute, c.socStartKWh(), v, charger, evaluator);
    }

    private static ChargingSession session(ChargingSession c, int end, double socStart, Vehicle v, Charger charger,
                                           InsertionEvaluator evaluator) {
        double power = evaluator.scenario().physics().chargePowerKW(v, charger);
        int minutes = end - c.start();
        double socEnd = evaluator.scenario().physics().curve().socAfter(v.batteryKWh(), socStart, power, minutes);
        double cost = evaluator.chargingCost(v, socStart, power, c.start(), minutes);
        return new ChargingSession(c.hubId(), c.chargerId(), c.start(), end, c.location(), socStart, socEnd, cost);
    }

    /** Trips the re-plan sent to backup that had an electric cab before. */
    private static long lostTrips(Result r) {
        return r.diff().movedToBackup();
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
