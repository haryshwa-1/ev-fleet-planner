package com.moveinsync.evfleet.core.geo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.moveinsync.evfleet.core.util.Clock.hm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoModelTest {

    private final GeoModel geo = new GeoModel(1.3, 20.0, 32.0, new TrafficProfile(hm(17, 30), hm(21, 0)));

    @Test
    void slowsDownWhenPeakStartsMidDrive() {
        // 10 min at 32 km/h covers 5.33 km; the other 9.67 km at 20 km/h take 29 min.
        assertThat(geo.travelMinutes(15, hm(17, 20))).isEqualTo(39);
    }

    @Test
    void speedsUpWhenPeakEndsMidDrive() {
        // 20 min at 20 km/h covers 6.67 km; the other 8.33 km at 32 km/h take 15.6 min.
        assertThat(geo.travelMinutes(15, hm(20, 40))).isEqualTo(36);
        assertThat(geo.peakKm(15, hm(20, 40))).isCloseTo(20.0 / 3, within(1e-9));
    }

    @Test
    void drivesInsideOneWindowUseThatWindowsSpeed() {
        assertThat(geo.travelMinutes(10, hm(18, 0))).isEqualTo(30);
        assertThat(geo.travelMinutes(16, hm(22, 0))).isEqualTo(30);
        assertThat(geo.peakKm(16, hm(22, 0))).isZero();
    }

    @Test
    void leavingLaterNeverArrivesEarlier() {
        for (int depart = hm(16, 0); depart <= hm(26, 0); depart++) {
            for (double km = 0.5; km <= 40; km += 0.5) {
                int now = depart + geo.travelMinutes(km, depart);
                int later = depart + 1 + geo.travelMinutes(km, depart + 1);
                assertThat(later).as("km=%s depart=%d", km, depart).isGreaterThanOrEqualTo(now);
            }
        }
    }

    @Test
    void splittingOrWaitingNeverBeatsDrivingStraightAway() {
        // The lower bound relies on this: no sequence of drives and waits reaches a place sooner.
        Random rnd = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            double km = 0.5 + rnd.nextDouble() * 30;
            int depart = hm(16, 30) + rnd.nextInt(6 * 60);
            double firstLegKm = km * rnd.nextDouble();
            int wait = rnd.nextInt(3) == 0 ? rnd.nextInt(40) : 0;

            int midpoint = depart + geo.travelMinutes(firstLegKm, depart) + wait;
            int split = midpoint + geo.travelMinutes(km - firstLegKm, midpoint);
            assertThat(split).as("km=%s depart=%d first=%s wait=%d", km, depart, firstLegKm, wait)
                    .isGreaterThanOrEqualTo(geo.earliestArrival(km, depart));
        }
    }

    @Test
    void worstCaseIsNeverFasterThanTheRealDrive() {
        for (int depart = hm(16, 0); depart <= hm(26, 0); depart += 7) {
            for (double km = 0.5; km <= 40; km += 1.5) {
                assertThat(geo.worstCaseTravelMinutes(km)).isGreaterThanOrEqualTo(geo.travelMinutes(km, depart));
            }
        }
    }

    @Test
    void trafficChangesAreFoundAcrossMidnight() {
        TrafficProfile t = geo.traffic();
        assertThat(t.nextChange(hm(17, 0))).isEqualTo(hm(17, 30));
        assertThat(t.nextChange(hm(17, 30))).isEqualTo(hm(21, 0));
        assertThat(t.nextChange(hm(23, 0))).isEqualTo(hm(41, 30)); // 17:30 the next day
        assertThat(new TrafficProfile(0, 0).nextChange(500)).isEqualTo(Integer.MAX_VALUE);
    }
}
