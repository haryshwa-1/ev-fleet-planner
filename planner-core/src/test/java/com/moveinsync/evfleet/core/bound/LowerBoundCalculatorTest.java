package com.moveinsync.evfleet.core.bound;

import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.moveinsync.evfleet.core.Fixtures.ORIGIN;
import static com.moveinsync.evfleet.core.Fixtures.cab;
import static com.moveinsync.evfleet.core.Fixtures.hub;
import static com.moveinsync.evfleet.core.Fixtures.scenario;
import static com.moveinsync.evfleet.core.Fixtures.trip;
import static org.assertj.core.api.Assertions.assertThat;

class LowerBoundCalculatorTest {

    private static final Location A = new Location(6, 8);
    private static final Location B = new Location(12, 16);
    private final LowerBoundCalculator calc = new LowerBoundCalculator();

    @Test
    void threeOverlappingTripsAndTwoCabsMeansAtLeastOneBackup() {
        Scenario s = scenario(List.of(cab("C1", 20, ORIGIN), cab("C2", 20, ORIGIN)), List.of(hub("H1", ORIGIN, 2)),
                List.of(trip("T1", 640, A, B, 10), trip("T2", 641, A, B, 10), trip("T3", 642, A, B, 10)));
        LowerBound lb = calc.compute(s);
        assertThat(lb.peakConcurrentTrips()).isEqualTo(3);
        assertThat(lb.concurrencyExcess()).isEqualTo(1);
        assertThat(lb.unassignedLowerBound()).isEqualTo(1);
    }

    @Test
    void chainBoundCountsTheEmptyDriveBetweenTrips() {
        // T2 starts after T1 ends but the cab cannot get from B back to A in 5 minutes,
        // so one cab cannot do both even though the trips do not overlap.
        Scenario s = scenario(List.of(cab("C1", 20, ORIGIN)), List.of(hub("H1", ORIGIN, 1)),
                List.of(trip("T1", 640, A, B, 10), trip("T2", 665, A, B, 10)));
        LowerBound lb = calc.compute(s);
        assertThat(lb.concurrencyExcess()).isZero();
        assertThat(lb.chainExcess()).isEqualTo(1);
    }

    @Test
    void unreachableTripsAreCounted() {
        Scenario s = scenario(List.of(cab("C1", 20, ORIGIN)), List.of(hub("H1", ORIGIN, 1)),
                List.of(trip("T1", 605, A, B, 10)));
        assertThat(calc.compute(s).unreachable()).isEqualTo(1);
    }

    @Test
    void noBoundWhenEverythingFits() {
        Scenario s = scenario(List.of(cab("C1", 20, ORIGIN)), List.of(hub("H1", ORIGIN, 1)),
                List.of(trip("T1", 640, A, B, 10), trip("T2", 720, A, B, 10)));
        assertThat(calc.compute(s).unassignedLowerBound()).isZero();
    }
}
