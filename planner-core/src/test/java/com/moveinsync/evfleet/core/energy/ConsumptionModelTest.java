package com.moveinsync.evfleet.core.energy;

import com.moveinsync.evfleet.core.model.Vehicle;
import org.junit.jupiter.api.Test;

import static com.moveinsync.evfleet.core.Fixtures.ORIGIN;
import static com.moveinsync.evfleet.core.Fixtures.cab;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConsumptionModelTest {

    private final ConsumptionModel model = new ConsumptionModel(1.15, 1.10);

    @Test
    void trafficPenaltyAppliesOnlyToPeakKilometres() {
        Vehicle v = cab("C1", 10, ORIGIN); // 0.2 kWh/km, AC off
        // 15 km with a third in peak: 10 km plain + 5 km x 1.15 = 15.75 effective km.
        assertThat(model.energyKWh(v, 15, 5)).isCloseTo(15.75 * 0.2, within(1e-9));
        assertThat(model.energyKWh(v, 15, 0)).isCloseTo(3.0, within(1e-9));
        assertThat(model.worstCaseEnergyKWh(v, 15)).isGreaterThanOrEqualTo(model.energyKWh(v, 15, 15));
    }
}
