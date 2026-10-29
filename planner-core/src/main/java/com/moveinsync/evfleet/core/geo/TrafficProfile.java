package com.moveinsync.evfleet.core.geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * How traffic changes through the day: a list of bands, each with its own average speed and
 * energy factor, and free-flowing traffic outside every band. Both speed and consumption change
 * the moment a drive crosses into another band, even halfway through.
 *
 * <p>Speeds are averages for the band, so stop-and-go is already priced in; surprises on the night
 * are handled by re-planning, not by a finer traffic model. The bands are plain data, so real
 * city speeds (e.g. from a maps provider) can replace the defaults without touching the planner.
 * Bands must not overlap or wrap past midnight.
 */
public final class TrafficProfile {

    private static final int DAY = 24 * 60;

    /**
     * One traffic condition. {@code energyFactor} scales consumption per km (1.0 = free flow);
     * slow traffic costs more per km because the AC and electronics run longer.
     */
    public record Band(String label, int startMinuteOfDay, int endMinuteOfDay, double speedKmph, double energyFactor) {
        public Band {
            if (startMinuteOfDay < 0 || endMinuteOfDay > DAY || startMinuteOfDay >= endMinuteOfDay) {
                throw new IllegalArgumentException("band " + label + " must lie within one day");
            }
            if (speedKmph <= 0 || energyFactor <= 0) {
                throw new IllegalArgumentException("band " + label + " needs positive speed and energy factor");
            }
        }
    }

    private final List<Band> bands;
    private final double freeFlowSpeedKmph;
    // Per minute of the day, computed once: drive times are evaluated millions of times per plan.
    private final Band[] bandByMinute = new Band[DAY];
    private final int[] nextChangeByMinute = new int[DAY];

    public TrafficProfile(List<Band> bands, double freeFlowSpeedKmph) {
        if (freeFlowSpeedKmph <= 0) throw new IllegalArgumentException("free-flow speed must be positive");
        List<Band> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparingInt(Band::startMinuteOfDay));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startMinuteOfDay() < sorted.get(i - 1).endMinuteOfDay()) {
                throw new IllegalArgumentException("bands " + sorted.get(i - 1).label() + " and "
                        + sorted.get(i).label() + " overlap");
            }
        }
        this.bands = List.copyOf(sorted);
        this.freeFlowSpeedKmph = freeFlowSpeedKmph;

        TreeSet<Integer> boundaries = new TreeSet<>();
        for (Band b : this.bands) {
            boundaries.add(b.startMinuteOfDay());
            boundaries.add(b.endMinuteOfDay());
            for (int m = b.startMinuteOfDay(); m < b.endMinuteOfDay(); m++) bandByMinute[m] = b;
        }
        for (int m = 0; m < DAY; m++) {
            Integer next = boundaries.higher(m);
            nextChangeByMinute[m] = next != null ? next
                    : boundaries.isEmpty() ? Integer.MAX_VALUE : DAY + boundaries.first();
        }
    }

    public List<Band> bands() {
        return bands;
    }

    /** Speed outside every band, with energy factor 1.0. */
    public double freeFlowSpeedKmph() {
        return freeFlowSpeedKmph;
    }

    /** No congestion at any time: one speed all day. */
    public static TrafficProfile freeFlow(double speedKmph) {
        return new TrafficProfile(List.of(), speedKmph);
    }

    public double speedAt(int minute) {
        Band b = bandAt(minute);
        return b == null ? freeFlowSpeedKmph : b.speedKmph();
    }

    public double energyFactorAt(int minute) {
        Band b = bandAt(minute);
        return b == null ? 1.0 : b.energyFactor();
    }

    /** Label of the band in force, or "FREE_FLOW". */
    public String labelAt(int minute) {
        Band b = bandAt(minute);
        return b == null ? "FREE_FLOW" : b.label();
    }

    /** First minute after {@code minute} at which a band starts or ends; MAX_VALUE if there are no bands. */
    public int nextChange(int minute) {
        int next = nextChangeByMinute[Math.floorMod(minute, DAY)];
        return next == Integer.MAX_VALUE ? next : minute - Math.floorMod(minute, DAY) + next;
    }

    /** Lowest speed at any time of day; used when the departure time is not known yet. */
    public double slowestSpeedKmph() {
        return bands.stream().mapToDouble(Band::speedKmph).reduce(freeFlowSpeedKmph, Math::min);
    }

    /** Highest energy factor at any time of day. */
    public double highestEnergyFactor() {
        return bands.stream().mapToDouble(Band::energyFactor).reduce(1.0, Math::max);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TrafficProfile t && bands.equals(t.bands) && freeFlowSpeedKmph == t.freeFlowSpeedKmph;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bands, freeFlowSpeedKmph);
    }

    @Override
    public String toString() {
        return "TrafficProfile[bands=" + bands + ", freeFlowSpeedKmph=" + freeFlowSpeedKmph + "]";
    }

    private Band bandAt(int minute) {
        return bandByMinute[Math.floorMod(minute, DAY)];
    }
}
