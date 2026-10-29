package com.moveinsync.evfleet.core.bound;

/**
 * No plan, however clever, can send fewer than {@code unassignedLowerBound} trips to backup cabs.
 *
 * @param intrinsicallyInfeasible trips no EV can do at all (range or time window)
 * @param unreachable             trips no cab can reach in time even driving straight there from its start
 * @param concurrencyExcess       peak simultaneous trips minus fleet size
 * @param energyExcess            trips that must be dropped so total demand fits total possible supply
 * @param chainExcess             trips no matching of cab starts and earlier trips can precede (unlimited battery)
 * @param costLowerBoundRupees    cheapest possible charging bill for a plan that serves every trip not already
 *                                counted as impossible; a plan sending more trips to backup may charge less
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
