package com.moveinsync.evfleet.core.model;

/**
 * A fixed employee trip. It must start exactly at {@code startTime} and end by {@code latestFinish}.
 */
public record Trip(
        String id,
        int startTime,
        Location start,
        Location end,
        double distanceKm,
        int latestFinish) {

    public Trip {
        if (distanceKm <= 0) throw new IllegalArgumentException("distanceKm must be positive");
        if (latestFinish <= startTime) throw new IllegalArgumentException("latestFinish must be after startTime");
    }
}
