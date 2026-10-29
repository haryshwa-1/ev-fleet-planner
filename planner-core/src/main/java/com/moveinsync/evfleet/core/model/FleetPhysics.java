package com.moveinsync.evfleet.core.model;

import com.moveinsync.evfleet.core.energy.ChargingCurve;
import com.moveinsync.evfleet.core.energy.ConsumptionModel;
import com.moveinsync.evfleet.core.geo.GeoModel;

/**
 * The rules of the world every plan must obey. Shared by the planner and the validator:
 * the validator re-derives every number from these rules instead of trusting the plan.
 *
 * @param socFloorFraction battery may never drop below this share of capacity (0.15)
 */
public record FleetPhysics(GeoModel geo, ConsumptionModel consumption, ChargingCurve curve, double socFloorFraction) {

    public double floorKWh(Vehicle v) {
        return v.batteryKWh() * socFloorFraction;
    }

    /** Power actually delivered: the lower of what the charger offers and what the car accepts. */
    public double chargePowerKW(Vehicle v, Charger c) {
        return Math.min(v.maxChargeKW(), c.powerKW());
    }

    /** Energy for a drive of {@code km} leaving at {@code departMinute}; peak km cost more. */
    public double driveEnergyKWh(Vehicle v, double km, int departMinute) {
        return consumption.energyKWh(v, km, geo.peakKm(km, departMinute));
    }

    /** Minutes a trip takes when it starts on time. */
    public int tripMinutes(Trip t) {
        return geo.travelMinutes(t.distanceKm(), t.startTime());
    }

    public double tripEnergyKWh(Vehicle v, Trip t) {
        return driveEnergyKWh(v, t.distanceKm(), t.startTime());
    }
}
