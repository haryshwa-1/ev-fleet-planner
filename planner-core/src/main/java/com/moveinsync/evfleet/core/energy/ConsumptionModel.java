package com.moveinsync.evfleet.core.energy;

import com.moveinsync.evfleet.core.model.Vehicle;

/**
 * Energy used to drive: effective km x base kWh/km x AC factor. Effective km already include the
 * traffic band's energy factor ({@code GeoModel.effectiveKm}).
 */
public record ConsumptionModel(double acFactor) {

    public double energyKWh(Vehicle vehicle, double effectiveKm) {
        return effectiveKm * vehicle.kWhPerKm() * (vehicle.acOn() ? acFactor : 1.0);
    }
}
