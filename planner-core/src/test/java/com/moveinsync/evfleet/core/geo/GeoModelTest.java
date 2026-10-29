package com.moveinsync.evfleet.core.geo;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.moveinsync.evfleet.core.util.Clock.hm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GeoModelTest {

    /** One band so the arithmetic is easy to follow: 20 km/h, x1.15 from 17:30 to 21:00, else 32 km/h. */
    private static final TrafficProfile SIMPLE = new TrafficProfile(
            List.of(new TrafficProfile.Band("PEAK", hm(17, 30), hm(21, 0), 20, 1.15)), 32);
    private final GeoModel geo = new GeoModel(1.3, SIMPLE);

    static Stream<GeoModel> profiles() {
        return Stream.of(new GeoModel(1.3, SIMPLE), new GeoModel(1.3, ScenarioGenerator.standardTraffic()));
    }

    @Test
    void slowsDownWhenPeakStartsMidDrive() {
        // 10 min at 32 km/h covers 5.33 km; the other 9.67 km at 20 km/h take 29 min.
        assertThat(geo.travelMinutes(15, hm(17, 20))).isEqualTo(39);
    }

    @Test
    void speedsUpWhenPeakEndsMidDrive() {
        // 20 min at 20 km/h covers 6.67 km; the other 8.33 km at 32 km/h take 15.6 min.
        assertThat(geo.travelMinutes(15, hm(20, 40))).isEqualTo(36);
        // energy: 6.67 km x 1.15 + 8.33 km x 1.0
        assertThat(geo.effectiveKm(15, hm(20, 40))).isCloseTo(15 + 0.15 * 20.0 / 3, within(1e-9));
    }

    @Test
    void drivesInsideOneBandUseThatBandsSpeed() {
        assertThat(geo.travelMinutes(10, hm(18, 0))).isEqualTo(30);
        assertThat(geo.travelMinutes(16, hm(22, 0))).isEqualTo(30);
        assertThat(geo.effectiveKm(16, hm(22, 0))).isCloseTo(16, within(1e-9));
    }

    @Test
    void crossesSeveralBandsInOneDrive() {
        GeoModel city = new GeoModel(1.3, ScenarioGenerator.standardTraffic());
        // From 18:50: 10 min PEAK at 18 km/h (3 km), 90 min HEAVY at 22 km/h would cover 33 km,
        // so a 20 km drive ends in HEAVY: 3 km + 17 km at 22 km/h = 10 + 46.4 min.
        assertThat(city.travelMinutes(20, hm(18, 50))).isEqualTo(57);
        assertThat(city.effectiveKm(20, hm(18, 50))).isCloseTo(3 * 1.18 + 17 * 1.12, within(1e-9));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void leavingLaterNeverArrivesEarlier(GeoModel model) {
        for (int depart = hm(16, 0); depart <= hm(26, 0); depart++) {
            for (double km = 0.5; km <= 40; km += 0.5) {
                int now = depart + model.travelMinutes(km, depart);
                int later = depart + 1 + model.travelMinutes(km, depart + 1);
                assertThat(later).as("km=%s depart=%d", km, depart).isGreaterThanOrEqualTo(now);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void splittingOrWaitingNeverBeatsDrivingStraightAway(GeoModel model) {
        // The lower bound relies on this: no sequence of drives and waits reaches a place sooner.
        Random rnd = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            double km = 0.5 + rnd.nextDouble() * 30;
            int depart = hm(16, 0) + rnd.nextInt(8 * 60);
            double firstLegKm = km * rnd.nextDouble();
            int wait = rnd.nextInt(3) == 0 ? rnd.nextInt(40) : 0;

            int midpoint = depart + model.travelMinutes(firstLegKm, depart) + wait;
            int split = midpoint + model.travelMinutes(km - firstLegKm, midpoint);
            assertThat(split).as("km=%s depart=%d first=%s wait=%d", km, depart, firstLegKm, wait)
                    .isGreaterThanOrEqualTo(model.earliestArrival(km, depart));
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void worstCaseIsNeverBetterThanTheRealDrive(GeoModel model) {
        double worstFactor = model.traffic().highestEnergyFactor();
        for (int depart = hm(16, 0); depart <= hm(26, 0); depart += 7) {
            for (double km = 0.5; km <= 40; km += 1.5) {
                assertThat(model.worstCaseTravelMinutes(km)).isGreaterThanOrEqualTo(model.travelMinutes(km, depart));
                assertThat(km * worstFactor).isGreaterThanOrEqualTo(model.effectiveKm(km, depart) - 1e-9);
            }
        }
    }

    @Test
    void trafficChangesAreFoundAcrossMidnight() {
        assertThat(SIMPLE.nextChange(hm(17, 0))).isEqualTo(hm(17, 30));
        assertThat(SIMPLE.nextChange(hm(17, 30))).isEqualTo(hm(21, 0));
        assertThat(SIMPLE.nextChange(hm(23, 0))).isEqualTo(hm(41, 30)); // 17:30 the next day
        assertThat(TrafficProfile.freeFlow(30).nextChange(500)).isEqualTo(Integer.MAX_VALUE);
        assertThat(SIMPLE.labelAt(hm(25, 0))).isEqualTo("FREE_FLOW");
    }

    @Test
    void rejectsOverlappingBands() {
        assertThatThrownBy(() -> new TrafficProfile(List.of(
                new TrafficProfile.Band("A", hm(17, 0), hm(19, 0), 20, 1.1),
                new TrafficProfile.Band("B", hm(18, 0), hm(20, 0), 25, 1.1)), 32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
