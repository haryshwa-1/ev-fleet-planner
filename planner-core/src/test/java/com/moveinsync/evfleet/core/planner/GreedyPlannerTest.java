package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.ReasonCode;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import com.moveinsync.evfleet.core.validate.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.moveinsync.evfleet.core.Fixtures.ORIGIN;
import static com.moveinsync.evfleet.core.Fixtures.cab;
import static com.moveinsync.evfleet.core.Fixtures.hub;
import static com.moveinsync.evfleet.core.Fixtures.scenario;
import static com.moveinsync.evfleet.core.Fixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

class GreedyPlannerTest {

    private static final Location A = new Location(6, 8);      // 10 km from origin
    private static final Location B = new Location(12, 16);    // 10 km from A
    private final GreedyPlanner planner = new GreedyPlanner();
    private final PlanValidator validator = new PlanValidator();
    private final Hub hub = hub("H1", ORIGIN, 1);

    private Plan planAndValidate(Scenario s) {
        Plan plan = planner.plan(s);
        ValidationReport report = validator.validate(s, plan);
        assertThat(report.violations()).isEmpty();
        return plan;
    }

    @Test
    void assignsAReachableTripToACabWithEnoughCharge() {
        Scenario s = scenario(List.of(cab("CAB-1", 18, ORIGIN)), List.of(hub), List.of(trip("T1", 640, A, B, 10)));
        Plan plan = planAndValidate(s);
        assertThat(plan.unassigned()).isEmpty();
        assertThat(plan.timeline("CAB-1")).last().isInstanceOf(TripLeg.class);
    }

    @Test
    void picksTheNearerCab() {
        Scenario s = scenario(List.of(cab("FAR", 18, ORIGIN), cab("NEAR", 18, new Location(6, 7))),
                List.of(hub), List.of(trip("T1", 640, A, B, 10)));
        Plan plan = planAndValidate(s);
        assertThat(plan.timeline("NEAR")).hasSize(2);
        assertThat(plan.timeline("FAR")).isEmpty();
    }

    @Test
    void noCabInTime_whenThePickupIsTooFarForTheStartTime() {
        Scenario s = scenario(List.of(cab("CAB-1", 18, ORIGIN)), List.of(hub), List.of(trip("T1", 605, A, B, 10)));
        Plan plan = planAndValidate(s);
        assertThat(plan.unassigned()).singleElement()
                .satisfies(u -> assertThat(u.reason()).isEqualTo(ReasonCode.NO_CAB_AVAILABLE_IN_TIME));
    }

    @Test
    void chargesFirstWhenThereIsTimeAndTheBatteryIsLow() {
        // 6 kWh: driving 10 + 10 km uses 4 kWh -> 2 kWh, under the 3 kWh floor. Two hours to spare.
        Scenario s = scenario(List.of(cab("CAB-1", 6, ORIGIN)), List.of(hub), List.of(trip("T1", 760, A, B, 10)));
        Plan plan = planAndValidate(s);
        assertThat(plan.unassigned()).isEmpty();
        assertThat(plan.timeline("CAB-1")).anyMatch(a -> a instanceof ChargingSession);
    }

    @Test
    void insufficientCharge_whenThereIsNoTimeToCharge() {
        Scenario s = scenario(List.of(cab("CAB-1", 6, ORIGIN)), List.of(hub), List.of(trip("T1", 625, A, B, 10)));
        Plan plan = planAndValidate(s);
        assertThat(plan.unassigned()).singleElement()
                .satisfies(u -> assertThat(u.reason()).isEqualTo(ReasonCode.INSUFFICIENT_CHARGE));
    }

    @Test
    void chargerCongestion_whenTheOnlyChargerIsTaken() {
        // Both cabs are low and must charge 11+ min in the same 20-minute window; one charger only.
        Location a2 = new Location(6, 8.01);
        Scenario s = scenario(List.of(cab("CAB-1", 6, ORIGIN), cab("CAB-2", 6, ORIGIN)), List.of(hub),
                List.of(trip("T1", 640, A, B, 10), trip("T2", 641, a2, B, 10)));
        Plan plan = planAndValidate(s);
        assertThat(plan.unassigned()).singleElement()
                .satisfies(u -> assertThat(u.reason()).isEqualTo(ReasonCode.CHARGER_CONGESTION));
    }

    @Test
    void tripTooLongForAnyBattery_isRejectedUpFront() {
        Location far = new Location(60, 80); // 100 km: 20 kWh, the whole battery
        Scenario s = scenario(List.of(cab("CAB-1", 20, ORIGIN)), List.of(hub), List.of(trip("T1", 640, ORIGIN, far, 30)));
        Plan plan = planAndValidate(s);
        assertThat(plan.unassigned()).singleElement()
                .satisfies(u -> assertThat(u.reason()).isEqualTo(ReasonCode.TRIP_EXCEEDS_EV_RANGE));
    }

    @Test
    void keepsEnoughChargeToReachAChargerAfterTheTrip() {
        // Trip ends 30 km from the only hub, so the cab needs 6 kWh reserve on top of the 3 kWh floor.
        // Start 621: the cab must leave at once, no time to charge.
        Location far = new Location(18, 24);
        Trip t = trip("T1", 621, A, far, 10);

        // 13.5 - 2 (to A) - 4 (trip) = 7.5 kWh: above the floor, but it could not get back to a charger.
        Plan refused = planAndValidate(scenario(List.of(cab("CAB-1", 13.5, ORIGIN)), List.of(hub), List.of(t)));
        assertThat(refused.unassigned()).singleElement()
                .satisfies(u -> assertThat(u.reason()).isEqualTo(ReasonCode.INSUFFICIENT_CHARGE));

        // 15.5 kWh ends at 9.5 >= 3 + 6: accepted.
        Plan accepted = planAndValidate(scenario(List.of(cab("CAB-1", 15.5, ORIGIN)), List.of(hub), List.of(t)));
        assertThat(accepted.unassigned()).isEmpty();
    }
}
