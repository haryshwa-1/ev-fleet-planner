package com.moveinsync.evfleet.core.energy;

import com.moveinsync.evfleet.core.geo.TrafficProfile;
import com.moveinsync.evfleet.core.model.Vehicle;

/**
 * Energy used to drive: km x base kWh/km x traffic factor x AC factor.
 */
public record ConsumptionModel(double peakTrafficFactor, double acFactor, TrafficProfile traffic) {

    public double energyKWh(Vehicle vehicle, double km, int departMinute) {
        double factor = traffic.isPeak(departMinute) ? peakTrafficFactor : 1.0;
        if (vehicle.acOn()) factor *= acFactor;
        return km * vehicle.kWhPerKm() * factor;
    }

    /** Highest energy the drive could take, whatever the departure time. */
    public double worstCaseEnergyKWh(Vehicle vehicle, double km) {
        double factor = Math.max(peakTrafficFactor, 1.0) * (vehicle.acOn() ? acFactor : 1.0);
        return km * vehicle.kWhPerKm() * factor;
    }
}
