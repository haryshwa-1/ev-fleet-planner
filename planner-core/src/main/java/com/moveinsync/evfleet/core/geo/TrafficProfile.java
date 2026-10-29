package com.moveinsync.evfleet.core.geo;

/**
 * When the city is congested. Both travel speed and energy consumption depend on it, and both
 * change the moment a drive crosses into or out of the peak window, even halfway through.
 * Speeds are averages for the window, so stop-and-go is already priced in; surprises on the
 * night are handled by re-planning, not by a finer traffic model.
 */
public record TrafficProfile(int peakStartMinuteOfDay, int peakEndMinuteOfDay) {

    private static final int DAY = 24 * 60;

    public boolean isPeak(int minute) {
        int m = Math.floorMod(minute, DAY);
        return m >= peakStartMinuteOfDay && m < peakEndMinuteOfDay;
    }

    /** First minute after {@code minute} at which peak starts or ends; MAX_VALUE if there is no peak. */
    public int nextChange(int minute) {
        if (peakStartMinuteOfDay >= peakEndMinuteOfDay) return Integer.MAX_VALUE;
        int day = minute - Math.floorMod(minute, DAY);
        for (int candidate : new int[]{day + peakStartMinuteOfDay, day + peakEndMinuteOfDay, day + DAY + peakStartMinuteOfDay}) {
            if (candidate > minute) return candidate;
        }
        throw new IllegalStateException("unreachable");
    }
}
