package com.moveinsync.evfleet.core.geo;

import com.moveinsync.evfleet.core.model.Location;

/**
 * Road distance and travel time. Road distance = straight line x {@code roadFactor}.
 *
 * <p>Speed is time-dependent: a drive moves at the speed of whichever traffic band it is in at
 * each moment, so a drive that starts at 17:20 slows down when the peak begins and one that
 * starts at 20:40 speeds up as traffic eases. Travel time is rounded up to whole minutes so the
 * plan is never optimistic.
 *
 * <p>This model has the FIFO property: leaving later never gets you there earlier, because a cab
 * that left earlier is always at least as far along the road. Hence waiting before a drive, or
 * splitting it into several drives, can never beat driving straight away, which is what makes
 * {@link #earliestArrival} simple and safe for lower bounds.
 */
public record GeoModel(double roadFactor, TrafficProfile traffic) {

    /** Outcome of one drive: exact (unrounded) minutes, and km weighted by each band's energy factor. */
    private record Drive(double minutes, double effectiveKm) {
    }

    public double roadKm(Location a, Location b) {
        return a.straightLineKm(b) * roadFactor;
    }

    public int travelMinutes(double km, int departMinute) {
        return (int) Math.ceil(drive(km, departMinute).minutes() - 1e-9);
    }

    /** Km of this drive weighted by the energy factor of the band each km was driven in. */
    public double effectiveKm(double km, int departMinute) {
        return drive(km, departMinute).effectiveKm();
    }

    /**
     * Earliest possible arrival when free to leave at {@code readyAt}. Because of the FIFO property
     * this is simply "leave now"; no plan can arrive earlier, so lower bounds may rely on it.
     */
    public int earliestArrival(double km, int readyAt) {
        return readyAt + travelMinutes(km, readyAt);
    }

    /** Travel time when the departure minute is not known yet: assume the slowest band throughout. */
    public int worstCaseTravelMinutes(double km) {
        return (int) Math.ceil(km / traffic.slowestSpeedKmph() * 60.0 - 1e-9);
    }

    /** Walks the drive band by band; a few bands at most for any drive in a shift. */
    private Drive drive(double km, int depart) {
        double remaining = km;
        double effectiveKm = 0;
        int t = depart; // always a whole minute: the departure, then band boundaries
        while (true) {
            double speed = traffic.speedAt(t);
            double factor = traffic.energyFactorAt(t);
            int change = traffic.nextChange(t);
            double reachableKm = change == Integer.MAX_VALUE ? Double.MAX_VALUE : speed * (change - t) / 60.0;
            if (reachableKm >= remaining) {
                return new Drive(t - depart + remaining / speed * 60.0, effectiveKm + remaining * factor);
            }
            effectiveKm += reachableKm * factor;
            remaining -= reachableKm;
            t = change;
        }
    }
}
