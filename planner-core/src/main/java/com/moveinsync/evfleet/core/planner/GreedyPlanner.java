package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.ReasonCode;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy construction: trips in start-time order, each given to the cheapest cab that can
 * serve it (directly or after charging), else sent to a backup cab with a reason.
 *
 * <p>Complexity: O(T log T + T x V x (1 + H x C x log S)) where T trips, V cabs, H hubs tried
 * per cab, C chargers per hub, S bookings per charger. The charge branch only runs for cabs
 * that cannot serve directly or would end low, so in practice it is close to O(T x V).
 * Memory: O(V + T + S + L x H) with L distinct locations, whose hubs are kept sorted by distance.
 */
public final class GreedyPlanner {

    private final PlannerConfig config;

    public GreedyPlanner(PlannerConfig config) {
        this.config = config;
    }

    public GreedyPlanner() {
        this(PlannerConfig.defaults());
    }

    public Plan plan(Scenario scenario) {
        InsertionEvaluator evaluator = new InsertionEvaluator(scenario, config);
        ChargerBook book = new ChargerBook(scenario);
        Map<String, CabState> cabs = new LinkedHashMap<>();
        for (Vehicle v : scenario.vehicles()) cabs.put(v.id(), new CabState(v));

        List<Trip> trips = new ArrayList<>(scenario.trips());
        trips.sort(Comparator.comparingInt(Trip::startTime).thenComparing(Trip::id));
        List<UnassignedTrip> unassigned = new ArrayList<>();

        for (Trip trip : trips) {
            UnassignedTrip rejected = assign(trip, cabs.values(), evaluator, book);
            if (rejected != null) unassigned.add(rejected);
        }

        Map<String, List<com.moveinsync.evfleet.core.plan.Activity>> timelines = new LinkedHashMap<>();
        cabs.forEach((id, cab) -> timelines.put(id, cab.timeline()));
        return new Plan(scenario.name(), timelines, unassigned);
    }

    /**
     * Gives {@code trip} to the best cab among {@code candidates} and commits it, or returns why
     * no cab can take it. Package-visible so local search and re-planning reuse the same rule.
     */
    static UnassignedTrip assign(Trip trip, Iterable<CabState> candidates, InsertionEvaluator evaluator,
                                 ChargerBook book) {
        UnassignedTrip intrinsic = intrinsicReason(trip, evaluator);
        if (intrinsic != null) return intrinsic;

        InsertionEvaluator.Diagnosis diag = new InsertionEvaluator.Diagnosis();
        Insertion best = null;
        for (CabState cab : candidates) {
            Insertion option = evaluator.evaluate(cab, trip, book, diag);
            if (option != null && (best == null || option.score() < best.score()
                    || (option.score() == best.score() && option.socAfterKWh() > best.socAfterKWh()))) {
                best = option;
            }
        }
        if (best == null) return explain(trip, diag);
        commit(best, book);
        return null;
    }

    static void commit(Insertion insertion, ChargerBook book) {
        insertion.chargingSession().ifPresent(book::book);
        insertion.cab().appendAll(insertion.activities());
    }

    static UnassignedTrip intrinsicReason(Trip trip, InsertionEvaluator evaluator) {
        int minutes = evaluator.tripMinutes(trip);
        if (trip.startTime() + minutes > trip.latestFinish()) {
            return new UnassignedTrip(trip.id(), ReasonCode.LATEST_FINISH_INFEASIBLE,
                    "trip needs " + minutes + " min but window allows " + (trip.latestFinish() - trip.startTime()));
        }
        boolean anyCabCan = evaluator.scenario().vehicles().stream().anyMatch(v -> !evaluator.exceedsRange(v, trip));
        if (!anyCabCan) {
            return new UnassignedTrip(trip.id(), ReasonCode.TRIP_EXCEEDS_EV_RANGE,
                    String.format("%.1f km trip plus return to a charger exceeds every cab's usable battery",
                            trip.distanceKm()));
        }
        return null;
    }

    static UnassignedTrip explain(Trip trip, InsertionEvaluator.Diagnosis diag) {
        if (!diag.anyReachable) {
            String detail = diag.bestLatenessMinutes == Integer.MAX_VALUE
                    ? "no cab can reach the pickup in time"
                    : "earliest electric cab would arrive " + diag.bestLatenessMinutes + " min late";
            return new UnassignedTrip(trip.id(), ReasonCode.NO_CAB_AVAILABLE_IN_TIME, detail);
        }
        if (diag.anyChargeFitsInTime) {
            return new UnassignedTrip(trip.id(), ReasonCode.CHARGER_CONGESTION,
                    "a cab could charge in time but every charger at its nearest hubs is booked");
        }
        String detail = diag.smallestEnergyShortfallKWh == Double.MAX_VALUE
                ? "reachable cabs lack charge and there is no time to charge first"
                : String.format("best reachable cab is %.1f kWh short and cannot charge in time",
                diag.smallestEnergyShortfallKWh);
        return new UnassignedTrip(trip.id(), ReasonCode.INSUFFICIENT_CHARGE, detail);
    }
}
