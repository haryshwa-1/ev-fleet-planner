package com.moveinsync.evfleet.core.energy;

import com.moveinsync.evfleet.core.model.Vehicle;

/**
 * Energy used to drive: km x base kWh/km x AC factor, with the traffic factor applied only to
 * the km driven in peak traffic ({@code peakKm}, from {@code GeoModel.peakKm}).
 */
public record ConsumptionModel(double peakTrafficFactor, double acFactor) {

    public double energyKWh(Vehicle vehicle, double km, double peakKm) {
        double effectiveKm = (km - peakKm) + peakKm * peakTrafficFactor;
        return effectiveKm * vehicle.kWhPerKm() * (vehicle.acOn() ? acFactor : 1.0);
    }

    /** Highest energy the drive could take, whatever the departure time. */
    public double worstCaseEnergyKWh(Vehicle vehicle, double km) {
        return energyKWh(vehicle, km, peakTrafficFactor >= 1.0 ? km : 0);
    }
}
