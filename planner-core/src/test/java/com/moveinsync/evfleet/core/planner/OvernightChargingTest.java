package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OvernightChargingTest {

    @ParameterizedTest
    @ValueSource(longs = {42, 1, 2})
    void everyCabIsReadyByMorningAtTheNightRateWithoutLosingATrip(long seed) {
        Scenario scenario = new ScenarioGenerator().generate(new ScenarioGenerator.Params(300, 5, 8, 1500, seed));
        Plan shift = new GreedyPlanner().plan(scenario);

        OvernightCharging.Result night = new OvernightCharging().schedule(scenario, shift);

        // The whole plan, overnight sessions included, passes the same validator as any other.
        assertThat(new PlanValidator().validate(scenario, night.plan()).violations()).isEmpty();
        assertThat(night.plan().unassigned()).isEqualTo(shift.unassigned());
        assertThat(night.notReady()).isEmpty();
        assertThat(night.cabsCharged()).isPositive();
        for (Vehicle v : scenario.vehicles()) {
            List<Activity> before = shift.timeline(v.id());
            List<Activity> after = night.plan().timeline(v.id());
            assertThat(after.subList(0, before.size())).isEqualTo(before);
            for (Activity a : after.subList(before.size(), after.size())) {
                if (a instanceof ChargingSession c) {
                    assertThat(c.socEndKWh()).isGreaterThanOrEqualTo(v.batteryKWh() * OvernightCharging.DEFAULT_TARGET_FRACTION - 1e-6);
                    assertThat(scenario.tariff().rateAt(c.start())).isEqualTo(scenario.tariff().cheapestRate());
                    assertThat(scenario.tariff().rateAt(c.end() - 1)).isEqualTo(scenario.tariff().cheapestRate());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.8})
    void aTargetTooHighForTheChargersIsReportedInsteadOfBroken(double target) {
        Scenario scenario = new ScenarioGenerator().generate(new ScenarioGenerator.Params(300, 1, 2, 1500, 42));
        OvernightCharging.Result night = new OvernightCharging(target).schedule(scenario, new GreedyPlanner().plan(scenario));
        assertThat(night.notReady()).isNotEmpty();
        assertThat(new PlanValidator().validate(scenario, night.plan()).violations()).isEmpty();
    }
}
