package com.moveinsync.evfleet.core.data;

import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioGeneratorTest {

    private final ScenarioGenerator generator = new ScenarioGenerator();

    @Test
    void producesTheRequestedSizes() {
        Scenario s = generator.generate(ScenarioGenerator.Params.standard());
        assertThat(s.vehicles()).hasSize(300);
        assertThat(s.chargerCount()).isEqualTo(40);
        assertThat(s.trips()).hasSize(1500);
    }

    @Test
    void sameSeedSameScenario() {
        var p = new ScenarioGenerator.Params(20, 2, 3, 80, 7L);
        assertThat(generator.generate(p)).isEqualTo(generator.generate(p));
    }

    @Test
    void tripsAreSortedAndUniquelyNamed() {
        Scenario s = generator.generate(ScenarioGenerator.Params.standard());
        assertThat(s.trips()).isSortedAccordingTo((a, b) -> Integer.compare(a.startTime(), b.startTime()));
        assertThat(s.trips().stream().map(Trip::id).distinct().count()).isEqualTo(1500);
        assertThat(s.trips()).allSatisfy(t -> assertThat(t.latestFinish()).isGreaterThan(t.startTime()));
    }
}
