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

    /** Minutes a trip takes, evaluated at its start time. */
    public int tripMinutes(Trip t) {
        return geo.travelMinutes(t.distanceKm(), t.startTime());
    }

    public double tripEnergyKWh(Vehicle v, Trip t) {
        return consumption.energyKWh(v, t.distanceKm(), t.startTime());
    }
}
