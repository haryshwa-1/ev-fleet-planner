package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Charger;

import java.util.Map;
import java.util.TreeMap;

/**
 * Bookings of a single charger as non-overlapping [start, end) intervals, keyed by start.
 * Finding the first free gap is O(k log n) where k is the number of bookings skipped.
 */
public final class ChargerSchedule {

    /** A free interval: the cab may charge from {@code start} until at most {@code end}. */
    public record Gap(int start, int end) {
        public int length() {
            return end - start;
        }
    }

    private final Charger charger;
    private final TreeMap<Integer, Integer> bookings = new TreeMap<>();

    public ChargerSchedule(Charger charger) {
        this.charger = charger;
    }

    public Charger charger() {
        return charger;
    }

    /**
     * First gap starting at or after {@code from}, at least {@code minMinutes} long,
     * that can start no later than {@code latestEnd - minMinutes}. The gap end is clipped to
     * {@code latestEnd} and to the charger's failure time.
     */
    public Gap firstGap(int from, int latestEnd, int minMinutes) {
        int limit = Math.min(latestEnd, charger.failedFrom());
        int start = from;
        Map.Entry<Integer, Integer> before = bookings.floorEntry(start);
        if (before != null && before.getValue() > start) start = before.getValue();
        while (start + minMinutes <= limit) {
            Map.Entry<Integer, Integer> next = bookings.higherEntry(start - 1);
            int gapEnd = next == null ? limit : Math.min(limit, next.getKey());
            if (gapEnd - start >= minMinutes) return new Gap(start, gapEnd);
            if (next == null) return null;
            start = Math.max(start, next.getValue());
        }
        return null;
    }

    public boolean isFree(int start, int end) {
        if (end > charger.failedFrom()) return false;
        Map.Entry<Integer, Integer> before = bookings.lowerEntry(end);
        return before == null || before.getValue() <= start;
    }

    public void book(int start, int end) {
        if (!isFree(start, end)) {
            throw new IllegalStateException("charger " + charger.id() + " already booked in [" + start + "," + end + ")");
        }
        bookings.put(start, end);
    }

    public void release(int start, int end) {
        bookings.remove(start, end);
    }

    public int bookingCount() {
        return bookings.size();
    }
}
