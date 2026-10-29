package com.moveinsync.evfleet.core.energy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TwoSegmentChargingCurveTest {

    // 30 kW x 0.92 = 27.6 kWh per hour fast; 8.28 kWh per hour slow
    private final TwoSegmentChargingCurve curve = TwoSegmentChargingCurve.standard();
    private static final double BATTERY = 30.0;
    private static final double POWER = 30.0;

    @Test
    void chargesLinearlyBelowTheKnee() {
        assertThat(curve.socAfter(BATTERY, 6.0, POWER, 30)).isCloseTo(6.0 + 13.8, within(1e-9));
    }

    @Test
    void slowsDownAboveTheKnee() {
        // 24 kWh = 80% knee; 60 min slow = 8.28 kWh but capped at 30
        assertThat(curve.socAfter(BATTERY, 24.0, POWER, 30)).isCloseTo(24.0 + 4.14, within(1e-9));
        assertThat(curve.socAfter(BATTERY, 24.0, POWER, 600)).isEqualTo(BATTERY);
    }

    @Test
    void crossingTheKneeUsesBothSegments() {
        // 18 -> 24 takes 6/0.46 = 13.04 min, then 10 more slow minutes add 1.38
        double minutesToKnee = 6.0 / (27.6 / 60);
        double soc = curve.socAfter(BATTERY, 18.0, POWER, minutesToKnee + 10);
        assertThat(soc).isCloseTo(24.0 + 1.38, within(1e-9));
    }

    @Test
    void minutesToReachIsTheInverseOfSocAfter() {
        for (double from = 3; from < 29; from += 2.5) {
            for (double to = from; to <= 30; to += 1.7) {
                double minutes = curve.minutesToReach(BATTERY, from, to, POWER);
                assertThat(curve.socAfter(BATTERY, from, POWER, minutes)).isCloseTo(to, within(1e-6));
            }
        }
    }

    @Test
    void reachingAboveCapacityIsImpossible() {
        assertThat(curve.minutesToReach(BATTERY, 10, 31, POWER)).isInfinite();
        assertThat(curve.minutesToReach(BATTERY, 20, 10, POWER)).isZero();
    }
}
