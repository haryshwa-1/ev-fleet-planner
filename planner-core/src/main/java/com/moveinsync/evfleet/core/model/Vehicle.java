package com.moveinsync.evfleet.core.model;

/**
 * An electric cab at the start of the planning horizon.
 *
 * @param batteryKWh     usable battery capacity
 * @param initialSocKWh  state of charge when the cab becomes available
 * @param kWhPerKm       base consumption (no traffic, AC off)
 * @param maxChargeKW    the most power the cab's on-board charger accepts
 * @param acOn           AC raises consumption by {@code ConsumptionModel.acFactor}
 * @param availableFrom  minute (since shift-day midnight) the cab can start work
 */
public record Vehicle(
        String id,
        double batteryKWh,
        double initialSocKWh,
        double kWhPerKm,
        double maxChargeKW,
        boolean acOn,
        Location location,
        int availableFrom) {

    public Vehicle {
        if (batteryKWh <= 0) throw new IllegalArgumentException("batteryKWh must be positive");
        if (initialSocKWh < 0 || initialSocKWh > batteryKWh + 1e-9)
            throw new IllegalArgumentException("initialSocKWh must be within [0, batteryKWh]");
        if (kWhPerKm <= 0) throw new IllegalArgumentException("kWhPerKm must be positive");
    }

    public Vehicle withSoc(double socKWh) {
        return new Vehicle(id, batteryKWh, socKWh, kWhPerKm, maxChargeKW, acOn, location, availableFrom);
    }

    public Vehicle withState(double socKWh, Location at, int from) {
        return new Vehicle(id, batteryKWh, socKWh, kWhPerKm, maxChargeKW, acOn, at, from);
    }
}
