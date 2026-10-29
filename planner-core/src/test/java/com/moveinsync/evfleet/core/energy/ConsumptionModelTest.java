package com.moveinsync.evfleet.core.energy;

import com.moveinsync.evfleet.core.model.Vehicle;
import org.junit.jupiter.api.Test;

import static com.moveinsync.evfleet.core.Fixtures.ORIGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConsumptionModelTest {

    private final ConsumptionModel model = new ConsumptionModel(1.10);

    @Test
    void acRaisesConsumptionPerEffectiveKm() {
        Vehicle acOff = new Vehicle("C1", 20, 10, 0.2, 30, false, ORIGIN, 600);
        Vehicle acOn = new Vehicle("C2", 20, 10, 0.2, 30, true, ORIGIN, 600);
        assertThat(model.energyKWh(acOff, 15.75)).isCloseTo(15.75 * 0.2, within(1e-9));
        assertThat(model.energyKWh(acOn, 15.75)).isCloseTo(15.75 * 0.2 * 1.10, within(1e-9));
    }
}
