package com.moveinsync.evfleet.core.model;

/**
 * A point on the city grid, in kilometres from the south-west corner.
 * A flat grid keeps distances deterministic and explainable; a real deployment
 * would swap {@link com.moveinsync.evfleet.core.geo.GeoModel} for a routing service.
 */
public record Location(double x, double y) {

    public double straightLineKm(Location other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    @Override
    public String toString() {
        return String.format("(%.2f,%.2f)", x, y);
    }
}
