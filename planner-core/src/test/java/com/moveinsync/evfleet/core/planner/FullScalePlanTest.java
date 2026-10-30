package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.bound.LowerBound;
import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.validate.ValidationReport;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The "prove it" checks: 300 cabs within 30 s, always valid, never better than the lower bound. */
class FullScalePlanTest {

    @Test
    void solvesThe300CabScenarioWellUnder30Seconds() {
        Scenario s = new ScenarioGenerator().generate(ScenarioGenerator.Params.standard());
        long start = System.nanoTime();
        Plan plan = new GreedyPlanner().plan(s);
        long millis = (System.nanoTime() - start) / 1_000_000;

        ValidationReport report = new PlanValidator().validate(s, plan);
        assertThat(report.violations()).isEmpty();
        assertThat(millis).isLessThan(30_000);
        assertThat(plan.assignedTripCount()).isGreaterThan(1400);
    }

    @Test
    void multiStartStaysUnder30SecondsAndIsValid() {
        Scenario s = new ScenarioGenerator().generate(ScenarioGenerator.Params.standard());
        long start = System.nanoTime();
        Plan plan = MultiStartPlanner.standard().planWithin(s, 24_000).plan();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(new PlanValidator().validate(s, plan).violations()).isEmpty();
        assertThat(millis).isLessThan(30_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void multiStartIsNeverWorseThanTheDefaultAndRepeatsExactly(long seed) {
        Scenario s = new ScenarioGenerator().generate(new ScenarioGenerator.Params(120, 3, 4, 600, seed));
        Plan single = new GreedyPlanner().plan(s);
        MultiStartPlanner.Run best = MultiStartPlanner.standard().plan(s).best();

        assertThat(best.backupTrips()).isLessThanOrEqualTo(single.unassigned().size());
        if (best.backupTrips() == single.unassigned().size()) {
            assertThat(best.netCostRupees()).isLessThanOrEqualTo(MultiStartPlanner.netCostRupees(s, single) + 1e-6);
        }
        assertThat(new PlanValidator().validate(s, best.plan()).violations()).isEmpty();
        assertThat(MultiStartPlanner.standard().plan(s).best().plan()).isEqualTo(best.plan());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void everySeedGivesAValidPlanThatRespectsTheLowerBound(long seed) {
        Scenario s = new ScenarioGenerator().generate(new ScenarioGenerator.Params(120, 3, 4, 600, seed));
        Plan plan = new GreedyPlanner().plan(s);
        ValidationReport report = new PlanValidator().validate(s, plan);
        LowerBound lb = new LowerBoundCalculator().compute(s);

        assertThat(report.violations()).isEmpty();
        assertThat(plan.unassigned().size()).isGreaterThanOrEqualTo(lb.unassignedLowerBound());
    }
}
