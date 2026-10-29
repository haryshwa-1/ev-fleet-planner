package com.moveinsync.evfleet.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.moveinsync.evfleet.core.util.Clock.hm;
import static org.assertj.core.api.Assertions.assertThat;

class TariffTest {

    private final Tariff tariff = new Tariff(List.of(
            new Tariff.Window("PEAK", hm(18, 0), hm(22, 0), 12.0),
            new Tariff.Window("OFF_PEAK", hm(22, 0), hm(6, 0), 5.0)), 8.0);

    @Test
    void picksTheWindowRate() {
        assertThat(tariff.rateAt(hm(17, 30))).isEqualTo(8.0);
        assertThat(tariff.rateAt(hm(18, 0))).isEqualTo(12.0);
        assertThat(tariff.rateAt(hm(21, 59))).isEqualTo(12.0);
        assertThat(tariff.rateAt(hm(22, 0))).isEqualTo(5.0);
    }

    @Test
    void windowsWrapPastMidnightAndMinutesPastADay() {
        assertThat(tariff.rateAt(hm(25, 30))).isEqualTo(5.0); // 01:30 next day
        assertThat(tariff.rateAt(hm(30, 0))).isEqualTo(8.0);  // 06:00 next day
        assertThat(tariff.cheapestRate()).isEqualTo(5.0);
    }
}
