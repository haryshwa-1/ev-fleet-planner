package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Tariff;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.moveinsync.evfleet.core.Fixtures.ORIGIN;
import static com.moveinsync.evfleet.core.Fixtures.PHYSICS;
import static com.moveinsync.evfleet.core.Fixtures.hub;
import static com.moveinsync.evfleet.core.Fixtures.trip;
import static com.moveinsync.evfleet.core.util.Clock.hm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Charging against a tariff of Rs 8 by day, Rs 12 from 18:00 to 22:00 and Rs 5 after 22:00. */
class TariffAwareChargingTest {

    private static final Tariff EVENING = new Tariff(List.of(
            new Tariff.Window("PEAK", hm(18, 0), hm(22, 0), 12.0),
            new Tariff.Window("OFF_PEAK", hm(22, 0), hm(6, 0), 5.0)), 8.0);
    private static final Location A = new Location(6, 8); // 10 km from the hub
    private final Hub hub = hub("H1", ORIGIN, 1);

    private Scenario scenario(Vehicle cab, Trip trip) {
        return new Scenario("tariff", hm(17, 0), hm(26, 0), List.of(cab), List.of(hub), List.of(trip), EVENING, PHYSICS);
    }

    @Test
    void aSessionThatRunsIntoOffPeakPaysEachMinuteAtItsOwnRate() {
        Vehicle cab = new Vehicle("C1", 20, 3, 0.2, 30, false, ORIGIN, hm(21, 0));
        Trip trip = trip("T1", hm(23, 0), ORIGIN, A, 30);
        InsertionEvaluator evaluator = new InsertionEvaluator(scenario(cab, trip), PlannerConfig.defaults());
        double power = PHYSICS.chargePowerKW(cab, hub.chargers().get(0));

        // 21:50 to 22:10: ten minutes at Rs 12, then ten at Rs 5.
        double before22 = PHYSICS.curve().socAfter(20, 3, power, 10) - 3;
        double after22 = PHYSICS.curve().socAfter(20, 3, power, 20) - 3 - before22;
        double cost = evaluator.chargingCost(cab, 3, power, hm(21, 50), 20);

        assertThat(cost).isCloseTo(before22 * 12 + after22 * 5, within(1e-9));
        assertThat(cost).isLessThan((before22 + after22) * 12).isGreaterThan((before22 + after22) * 5);
    }

    @Test
    void aCabWithTimeToSpareWaitsForCheapPowerAndFillsUp() {
        // Low cab at the hub at 21:30, trip at 23:30: plugging in now would pay the peak rate.
        Vehicle cab = new Vehicle("C1", 20, 4, 0.2, 30, false, ORIGIN, hm(21, 30));
        Scenario s = scenario(cab, trip("T1", hm(23, 30), ORIGIN, A, 30));
        Plan plan = new GreedyPlanner().plan(s);

        assertThat(new PlanValidator().validate(s, plan).violations()).isEmpty();
        ChargingSession session = (ChargingSession) plan.timeline("C1").get(0);
        assertThat(session.start()).isEqualTo(hm(22, 0));
        assertThat(session.costRupees()).isCloseTo((session.socEndKWh() - session.socStartKWh()) * 5, within(1e-9));
        assertThat(session.socEndKWh()).isGreaterThanOrEqualTo(20 * 0.8 - 1e-9);
    }

    @Test
    void atPeakPricesACabBuysLittleMoreThanItNeeds() {
        // Same cab, but the trip is at 22:00 so it must charge in the Rs 12 window.
        Vehicle cab = new Vehicle("C1", 20, 4, 0.2, 30, false, ORIGIN, hm(20, 30));
        Scenario s = scenario(cab, trip("T1", hm(22, 0), ORIGIN, A, 30));
        Plan plan = new GreedyPlanner().plan(s);

        assertThat(new PlanValidator().validate(s, plan).violations()).isEmpty();
        ChargingSession session = (ChargingSession) plan.timeline("C1").get(0);
        assertThat(EVENING.rateAt(session.start())).isEqualTo(12.0);
        assertThat(session.socEndKWh()).isLessThan(20 * 0.8); // stops at the peak target, not 80%
    }
}
