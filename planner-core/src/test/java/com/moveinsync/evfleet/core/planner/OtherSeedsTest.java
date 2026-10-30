package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The guarantees hold on shifts nothing was tuned on, not just the demo's seed 42. */
class OtherSeedsTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 201, 202})
    void plansAreValidInTimeAndNeverBeatTheLowerBound(long seed) {
        Scenario scenario = new ScenarioGenerator().generate(new ScenarioGenerator.Params(300, 5, 8, 1500, seed));

        long t0 = System.nanoTime();
        MultiStartPlanner.Run best = MultiStartPlanner.standard().plan(scenario).best();
        long seconds = (System.nanoTime() - t0) / 1_000_000_000;

        assertThat(new PlanValidator().validate(scenario, best.plan()).violations()).isEmpty();
        assertThat(seconds).isLessThan(30);
        assertThat(best.backupTrips()).isGreaterThanOrEqualTo(new LowerBoundCalculator().compute(scenario).unassignedLowerBound());
    }
}
