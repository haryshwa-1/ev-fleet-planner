package com.moveinsync.evfleet.core.validate;

import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Deadhead;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.ReasonCode;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.moveinsync.evfleet.core.Fixtures.ORIGIN;
import static com.moveinsync.evfleet.core.Fixtures.cab;
import static com.moveinsync.evfleet.core.Fixtures.hub;
import static com.moveinsync.evfleet.core.Fixtures.scenario;
import static com.moveinsync.evfleet.core.Fixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * One valid hand-made plan, then one broken variant per hard rule.
 * Cab at origin, 10 kWh; pickup 10 km away (20 min, 2 kWh); trip of 10 km (20 min, 2 kWh).
 */
class PlanValidatorTest {

    private static final Location PICKUP = new Location(6, 8);   // 10 km from origin
    private static final Location DROP = new Location(12, 16);   // 10 km from pickup
    private final PlanValidator validator = new PlanValidator();
    private final Hub hub = hub("H1", ORIGIN, 1);
    private final Trip t1 = trip("T1", 630, PICKUP, DROP, 10);

    private Scenario world(double socKWh) {
        return scenario(List.of(cab("CAB-1", socKWh, ORIGIN)), List.of(hub), List.of(t1));
    }

    private static Plan plan(Map<String, List<Activity>> timelines, List<UnassignedTrip> unassigned) {
        return new Plan("fixture", timelines, unassigned);
    }

    private List<Activity> goodDay(double soc) {
        return List.of(
                new Deadhead(600, 620, ORIGIN, PICKUP, 10, soc, soc - 2),
                new TripLeg("T1", 630, 650, PICKUP, DROP, 10, soc - 2, soc - 4));
    }

    @Test
    void acceptsAValidPlan() {
        ValidationReport r = validator.validate(world(10), plan(Map.of("CAB-1", goodDay(10)), List.of()));
        assertThat(r.violations()).isEmpty();
        assertThat(r.valid()).isTrue();
        assertThat(r.stats().tripsAssigned()).isEqualTo(1);
        assertThat(r.stats().lowestSocPercent()).isEqualTo(30.0);
    }

    @Test
    void batteryFloor_rejectsChargeBelowFifteenPercent() {
        // 6 kWh -> 4 after deadhead -> 2 after trip; floor is 3 kWh
        ValidationReport r = validator.validate(world(6), plan(Map.of("CAB-1", goodDay(6)), List.of()));
        assertThat(r.violationsByRule()).containsKey(Rule.BATTERY_FLOOR);
    }

    @Test
    void batteryFloor_countsTheEmptyKilometresToThePickup() {
        // 4.5 kWh: the deadhead alone drops it to 2.5, under the 3 kWh floor
        List<Activity> day = List.of(new Deadhead(600, 620, ORIGIN, PICKUP, 10, 4.5, 2.5));
        ValidationReport r = validator.validate(world(4.5),
                plan(Map.of("CAB-1", day), List.of(new UnassignedTrip("T1", ReasonCode.INSUFFICIENT_CHARGE, ""))));
        assertThat(r.violationsByRule()).containsOnlyKeys(Rule.BATTERY_FLOOR);
    }

    @Test
    void tripTiming_rejectsLateStart() {
        List<Activity> day = List.of(
                new Deadhead(600, 620, ORIGIN, PICKUP, 10, 10, 8),
                new TripLeg("T1", 635, 655, PICKUP, DROP, 10, 8, 6));
        ValidationReport r = validator.validate(world(10), plan(Map.of("CAB-1", day), List.of()));
        assertThat(r.violationsByRule()).containsOnlyKeys(Rule.TRIP_TIMING);
    }

    @Test
    void tripTiming_rejectsFinishAfterLatestFinish() {
        Trip tight = new Trip("T1", 630, PICKUP, DROP, 10, 645); // needs 20 min, allows 15
        Scenario s = scenario(List.of(cab("CAB-1", 10, ORIGIN)), List.of(hub), List.of(tight));
        ValidationReport r = validator.validate(s, plan(Map.of("CAB-1", goodDay(10)), List.of()));
        assertThat(r.violationsByRule()).containsKey(Rule.TRIP_TIMING);
    }

    @Test
    void travelTime_rejectsDrivingFasterThanTheRoadAllows() {
        List<Activity> day = List.of(
                new Deadhead(600, 610, ORIGIN, PICKUP, 10, 10, 8),
                new TripLeg("T1", 630, 650, PICKUP, DROP, 10, 8, 6));
        ValidationReport r = validator.validate(world(10), plan(Map.of("CAB-1", day), List.of()));
        assertThat(r.violationsByRule()).containsOnlyKeys(Rule.TRAVEL_TIME);
    }

    @Test
    void locationContinuity_rejectsTeleporting() {
        List<Activity> day = List.of(new TripLeg("T1", 630, 650, PICKUP, DROP, 10, 10, 8));
        ValidationReport r = validator.validate(world(10), plan(Map.of("CAB-1", day), List.of()));
        assertThat(r.violationsByRule()).containsOnlyKeys(Rule.LOCATION_CONTINUITY);
    }

    @Test
    void timeOrder_rejectsOverlappingActivities() {
        List<Activity> day = List.of(
                new Deadhead(600, 640, ORIGIN, PICKUP, 10, 10, 8),
                new TripLeg("T1", 630, 650, PICKUP, DROP, 10, 8, 6));
        ValidationReport r = validator.validate(world(10), plan(Map.of("CAB-1", day), List.of()));
        assertThat(r.violationsByRule()).containsKey(Rule.TIME_ORDER);
    }

    @Test
    void accounting_rejectsMissingAndDuplicatedTrips() {
        ValidationReport missing = validator.validate(world(10), plan(Map.of("CAB-1", List.of()), List.of()));
        assertThat(missing.violationsByRule()).containsOnlyKeys(Rule.TRIP_ACCOUNTING);

        ValidationReport twice = validator.validate(world(10), plan(Map.of("CAB-1", goodDay(10)),
                List.of(new UnassignedTrip("T1", ReasonCode.NO_CAB_AVAILABLE_IN_TIME, ""))));
        assertThat(twice.violationsByRule()).containsOnlyKeys(Rule.TRIP_ACCOUNTING);
    }

    @Test
    void unassignedTripsNeedAReason() {
        ValidationReport r = validator.validate(world(10),
                plan(Map.of("CAB-1", List.of()), List.of(new UnassignedTrip("T1", null, ""))));
        assertThat(r.violationsByRule()).containsOnlyKeys(Rule.MISSING_REASON);
    }

    @Test
    void stateMismatch_flagsWrongBookkeeping() {
        List<Activity> day = List.of(
                new Deadhead(600, 620, ORIGIN, PICKUP, 10, 10, 9),   // claims only 1 kWh used
                new TripLeg("T1", 630, 650, PICKUP, DROP, 10, 9, 7));
        ValidationReport r = validator.validate(world(10), plan(Map.of("CAB-1", day), List.of()));
        assertThat(r.violationsByRule()).containsKey(Rule.STATE_MISMATCH);
    }

    @Test
    void chargeCurve_rejectsChargingFasterThanPossible() {
        // 30 min at 27.6 kWh/h adds 13.8 kWh at most: 5 -> 18.8. Claiming 20 is impossible.
        List<Activity> day = List.of(new ChargingSession("H1", "H1-C1", 600, 630, ORIGIN, 5, 20, 150));
        Scenario s = scenario(List.of(cab("CAB-1", 5, ORIGIN)), List.of(hub), List.of());
        ValidationReport r = validator.validate(s, plan(Map.of("CAB-1", day), List.of()));
        assertThat(r.violationsByRule()).containsKey(Rule.CHARGE_CURVE);
    }

    @Test
    void costMismatch_rejectsAWrongSessionCost() {
        // Flat Rs 10/kWh: the reported cost must equal 10 x the energy actually added.
        Scenario s = scenario(List.of(cab("CAB-1", 5, ORIGIN)), List.of(hub), List.of());
        double after = s.physics().curve().socAfter(20, 5, 30, 30);
        Plan p = plan(Map.of("CAB-1", List.of(new ChargingSession("H1", "H1-C1", 600, 630, ORIGIN, 5, after, 1))),
                List.of());
        ValidationReport r = validator.validate(s, p);
        assertThat(r.violationsByRule()).containsOnlyKeys(Rule.COST_MISMATCH);
    }

    @Test
    void chargerCapacity_rejectsTwoCabsOnOneChargerAtOnce() {
        Scenario s = scenario(List.of(cab("CAB-1", 5, ORIGIN), cab("CAB-2", 5, ORIGIN)), List.of(hub), List.of());
        double after = s.physics().curve().socAfter(20, 5, 30, 30);
        Plan p = plan(Map.of(
                "CAB-1", List.of(new ChargingSession("H1", "H1-C1", 600, 630, ORIGIN, 5, after, (after - 5) * 10)),
                "CAB-2", List.of(new ChargingSession("H1", "H1-C1", 620, 650, ORIGIN, 5, after, (after - 5) * 10))), List.of());
        ValidationReport r = validator.validate(s, p);
        assertThat(r.violationsByRule()).containsKeys(Rule.CHARGER_DOUBLE_BOOKED, Rule.HUB_CAPACITY_EXCEEDED);
    }

    @Test
    void chargerCapacity_acceptsBackToBackSessions() {
        Scenario s = scenario(List.of(cab("CAB-1", 5, ORIGIN), cab("CAB-2", 5, ORIGIN)), List.of(hub), List.of());
        double after = s.physics().curve().socAfter(20, 5, 30, 30);
        Plan p = plan(Map.of(
                "CAB-1", List.of(new ChargingSession("H1", "H1-C1", 600, 630, ORIGIN, 5, after, (after - 5) * 10)),
                "CAB-2", List.of(new ChargingSession("H1", "H1-C1", 630, 660, ORIGIN, 5, after, (after - 5) * 10))), List.of());
        ValidationReport r = validator.validate(s, p);
        assertThat(r.valid()).isTrue();
        // 5 -> 16 kWh fast (knee), then slow; flat Rs 10/kWh on what was added
        assertThat(r.stats().recomputedChargingCostRupees()).isCloseTo(2 * (after - 5) * 10, within(0.01));
        assertThat(r.stats().peakChargersInUseByHub()).containsEntry("H1", 1);
    }

    @Test
    void chargerFailure_rejectsSessionsOnABrokenCharger() {
        Hub broken = new Hub("H1", ORIGIN, List.of(hub.chargers().get(0).failedAt(615)));
        Scenario s = scenario(List.of(cab("CAB-1", 5, ORIGIN)), List.of(broken), List.of());
        double after = s.physics().curve().socAfter(20, 5, 30, 30);
        Plan p = plan(Map.of("CAB-1", List.of(new ChargingSession("H1", "H1-C1", 600, 630, ORIGIN, 5, after, (after - 5) * 10))),
                List.of());
        ValidationReport r = validator.validate(s, p);
        assertThat(r.violationsByRule()).containsKeys(Rule.CHARGER_UNAVAILABLE, Rule.HUB_CAPACITY_EXCEEDED);
    }
}
