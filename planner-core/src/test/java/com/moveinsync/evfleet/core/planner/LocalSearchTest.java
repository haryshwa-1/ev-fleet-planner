package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LocalSearchTest {

    private static Scenario scenario(long seed) {
        return new ScenarioGenerator().generate(new ScenarioGenerator.Params(120, 3, 4, 600, seed));
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 1, 2})
    void theResultIsValidAndNeverWorse(long seed) {
        Scenario s = scenario(seed);
        Plan start = new GreedyPlanner().plan(s);

        LocalSearch.Result r = new LocalSearch().improve(s, start);

        assertThat(new PlanValidator().validate(s, r.plan()).violations()).isEmpty();
        assertThat(r.plan().unassigned().size()).isLessThanOrEqualTo(start.unassigned().size())
                .isGreaterThanOrEqualTo(new LowerBoundCalculator().compute(s).unassignedLowerBound());
        assertThat(MultiStartPlanner.netCostRupees(s, r.plan())).isLessThanOrEqualTo(MultiStartPlanner.netCostRupees(s, start));
    }

    @Test
    void itFindsImprovementsAndKeepsEveryCabsChargerBookingsConsistent() {
        Scenario s = scenario(42);
        LocalSearch.Result r = new LocalSearch().improve(s, new GreedyPlanner().plan(s));
        assertThat(r.kept()).isPositive();
        // Booking every session again would throw if two overlapped on one charger.
        ChargerBook.of(s, r.plan());
    }

    @Test
    void theSameInputAlwaysGivesTheSamePlan() {
        Scenario s = scenario(1);
        Plan start = new GreedyPlanner().plan(s);
        assertThat(new LocalSearch().improve(s, start).plan()).isEqualTo(new LocalSearch().improve(s, start).plan());
    }
}
