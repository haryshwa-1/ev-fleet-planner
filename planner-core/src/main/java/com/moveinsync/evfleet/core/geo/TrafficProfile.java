package com.moveinsync.evfleet.core.geo;

/**
 * When the city is congested. Both travel speed and energy consumption depend on it.
 * The whole of a drive is evaluated at its departure minute (a deliberate simplification:
 * drives here are short compared to how long a traffic window lasts).
 */
public record TrafficProfile(int peakStartMinuteOfDay, int peakEndMinuteOfDay) {

    /** First minute at or after {@code minute} that is not in the peak window. */
    public int nextOffPeak(int minute) {
        if (!isPeak(minute)) return minute;
        int day = Math.floorDiv(minute, 24 * 60) * 24 * 60;
        return day + peakEndMinuteOfDay;
    }

    public boolean isPeak(int minute) {
        int m = Math.floorMod(minute, 24 * 60);
        return m >= peakStartMinuteOfDay && m < peakEndMinuteOfDay;
    }
}
