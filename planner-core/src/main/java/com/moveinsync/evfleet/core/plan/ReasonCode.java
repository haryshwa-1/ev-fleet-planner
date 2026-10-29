package com.moveinsync.evfleet.core.plan;

/** Why a trip was handed to a backup (petrol/diesel) cab. */
public enum ReasonCode {
    /** Even a full battery cannot cover the trip plus the drive back to a charger. */
    TRIP_EXCEEDS_EV_RANGE,
    /** The trip itself takes longer than its latest-finish window allows. */
    LATEST_FINISH_INFEASIBLE,
    /** Every cab is busy or too far away to reach the pickup on time. */
    NO_CAB_AVAILABLE_IN_TIME,
    /** Cabs could reach the pickup but lack charge, and there is no time to charge first. */
    INSUFFICIENT_CHARGE,
    /** A cab could have charged in time, but every charger at the usable hubs was booked. */
    CHARGER_CONGESTION,
    /** Live re-planning ran out of its time budget before finding an electric cab. */
    REPLAN_BUDGET_EXHAUSTED
}
