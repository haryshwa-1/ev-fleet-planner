package com.moveinsync.evfleet.core.energy;

/**
 * How fast a battery fills. Implementations must be monotone: more minutes never means less charge.
 */
public interface ChargingCurve {

    /** State of charge after charging {@code minutes} from {@code socKWh} at a point of {@code powerKW}. */
    double socAfter(double batteryKWh, double socKWh, double powerKW, double minutes);

    /** Minutes needed to go from {@code fromKWh} to {@code toKWh}; 0 if already there. */
    double minutesToReach(double batteryKWh, double fromKWh, double toKWh, double powerKW);
}
