package com.moveinsync.evfleet.core.geo;

import com.moveinsync.evfleet.core.model.Location;

/**
 * Road distance and travel time. Road distance = straight line x {@code roadFactor}.
 * Travel time is rounded up to whole minutes so the plan is never optimistic.
 */
public record GeoModel(double roadFactor, double peakSpeedKmph, double offPeakSpeedKmph, TrafficProfile traffic) {

    public double roadKm(Location a, Location b) {
        return a.straightLineKm(b) * roadFactor;
    }

    public int travelMinutes(double km, int departMinute) {
        double speed = traffic.isPeak(departMinute) ? peakSpeedKmph : offPeakSpeedKmph;
        return (int) Math.ceil(km / speed * 60.0 - 1e-9);
    }

    /**
     * Earliest possible arrival when free to leave at {@code readyAt}: either go now, or wait for
     * traffic to clear and go then. Used for lower bounds, so it must never be later than any real plan.
     */
    public int earliestArrival(double km, int readyAt) {
        int now = readyAt + travelMinutes(km, readyAt);
        int later = traffic.nextOffPeak(readyAt);
        return Math.min(now, later + travelMinutes(km, later));
    }

    /** Travel time when the departure minute is not known yet: assume the slower speed. */
    public int worstCaseTravelMinutes(double km) {
        return (int) Math.ceil(km / Math.min(peakSpeedKmph, offPeakSpeedKmph) * 60.0 - 1e-9);
    }
}
