package com.moveinsync.evfleet.core.geo;

import com.moveinsync.evfleet.core.model.Location;

/**
 * Road distance and travel time. Road distance = straight line x {@code roadFactor}.
 *
 * <p>Speed is time-dependent: a drive moves at the speed of whichever traffic window it is in at
 * each moment, so a drive that starts at 17:20 slows down at 17:30 and one that starts at 20:40
 * speeds up at 21:00. Travel time is rounded up to whole minutes so the plan is never optimistic.
 *
 * <p>This model has the FIFO property: leaving later never gets you there earlier. Hence waiting
 * before a drive, or splitting it into several drives, can never beat driving straight away,
 * which is what makes {@link #earliestArrival} simple and safe for lower bounds.
 */
public record GeoModel(double roadFactor, double peakSpeedKmph, double offPeakSpeedKmph, TrafficProfile traffic) {

    /** Outcome of one drive: exact (unrounded) minutes, and how many of its km were in peak traffic. */
    private record Drive(double minutes, double peakKm) {
    }

    public double roadKm(Location a, Location b) {
        return a.straightLineKm(b) * roadFactor;
    }

    public int travelMinutes(double km, int departMinute) {
        return (int) Math.ceil(drive(km, departMinute).minutes() - 1e-9);
    }

    /** Kilometres of this drive that fall inside the peak window; consumption is higher there. */
    public double peakKm(double km, int departMinute) {
        return drive(km, departMinute).peakKm();
    }

    /**
     * Earliest possible arrival when free to leave at {@code readyAt}. Because of the FIFO property
     * this is simply "leave now"; no plan can arrive earlier, so lower bounds may rely on it.
     */
    public int earliestArrival(double km, int readyAt) {
        return readyAt + travelMinutes(km, readyAt);
    }

    /** Travel time when the departure minute is not known yet: assume the slower speed throughout. */
    public int worstCaseTravelMinutes(double km) {
        return (int) Math.ceil(km / Math.min(peakSpeedKmph, offPeakSpeedKmph) * 60.0 - 1e-9);
    }

    /** Walks the drive window by window; at most three windows for any drive in a shift. */
    private Drive drive(double km, int depart) {
        double remaining = km;
        double peakKm = 0;
        int t = depart; // always a whole minute: the departure, then traffic boundaries
        while (true) {
            boolean peak = traffic.isPeak(t);
            double speed = peak ? peakSpeedKmph : offPeakSpeedKmph;
            int change = traffic.nextChange(t);
            double reachableKm = change == Integer.MAX_VALUE ? Double.MAX_VALUE : speed * (change - t) / 60.0;
            if (reachableKm >= remaining) {
                if (peak) peakKm += remaining;
                return new Drive(t - depart + remaining / speed * 60.0, peakKm);
            }
            if (peak) peakKm += reachableKm;
            remaining -= reachableKm;
            t = change;
        }
    }
}
