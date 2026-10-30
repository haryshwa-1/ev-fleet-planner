package com.moveinsync.evfleet.core.validate;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.planner.GreedyPlanner;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownReferenceTest {

    @Test
    void aCabThatIsNotInTheScenarioIsCaught() {
        Scenario s = new ScenarioGenerator().generate(new ScenarioGenerator.Params(10, 1, 2, 20, 1));
        Plan plan = new GreedyPlanner().plan(s);
        var timelines = new LinkedHashMap<>(plan.timelines());
        timelines.put("GHOST", List.of());

        ValidationReport report = new PlanValidator().validate(s, new Plan(plan.scenarioName(), timelines, plan.unassigned()));

        assertThat(report.violationsByRule()).containsKey(Rule.UNKNOWN_REFERENCE);
    }
}
