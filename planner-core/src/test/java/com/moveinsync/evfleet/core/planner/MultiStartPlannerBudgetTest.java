package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A slow machine must get a valid plan by the deadline, and a fast one exactly the usual plan. */
class MultiStartPlannerBudgetTest {

    private static Scenario scenario() {
        return new ScenarioGenerator().generate(new ScenarioGenerator.Params(120, 3, 4, 600, 42));
    }

    @Test
    void anAlreadyExpiredBudgetStillGivesAValidPlan() {
        Scenario s = scenario();
        MultiStartPlanner.TimedPlan timed = MultiStartPlanner.standard().planWithin(s, 0);
        assertThat(timed.runsFinished()).isBetween(1, timed.runsTotal());
        assertThat(timed.searchSteps()).isZero();
        assertThat(new PlanValidator().validate(s, timed.plan()).violations()).isEmpty();
    }

    @Test
    void withTimeToSpareTheResultIsTheSameAsWithNoBudget() {
        Scenario s = scenario();
        Plan unlimited = MultiStartPlanner.standard().planAndImprove(s);
        MultiStartPlanner.TimedPlan timed = MultiStartPlanner.standard().planWithin(s, 600_000);
        assertThat(timed.runsFinished()).isEqualTo(timed.runsTotal());
        assertThat(timed.plan()).isEqualTo(unlimited);
    }

    @Test
    void aCancelledPlannerStopsInsteadOfRunningOn() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new GreedyPlanner().plan(scenario())).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
