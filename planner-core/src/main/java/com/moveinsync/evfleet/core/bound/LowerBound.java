package com.moveinsync.evfleet.core.bound;

/**
 * No plan, however clever, can send fewer than {@code unassignedLowerBound} trips to backup cabs.
 *
 * @param intrinsicallyInfeasible trips no EV can do at all (range or time window)
 * @param unreachable             trips no cab can reach in time even driving straight there at top speed
 * @param concurrencyExcess       peak simultaneous trips minus fleet size
 * @param energyExcess            trips that must be dropped so total demand fits total possible supply
 * @param chainExcess             trips no matching of cab starts and earlier trips can precede (unlimited battery)
 * @param costLowerBoundRupees    energy that must be charged if every other trip is served, at the cheapest rate
 */
public record LowerBound(
        int intrinsicallyInfeasible,
        int unreachable,
        int concurrencyExcess,
        int peakConcurrentTrips,
        int energyExcess,
        int chainExcess,
        int unassignedLowerBound,
        double costLowerBoundRupees) {
}
