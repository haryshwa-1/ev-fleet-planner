package com.moveinsync.evfleet.core.validate;

/** Every hard rule the validator enforces. */
public enum Rule {
    /** Each trip is assigned exactly once or listed as unassigned exactly once. */
    TRIP_ACCOUNTING,
    /** Unassigned trips must carry a reason. */
    MISSING_REASON,
    /** The plan refers to a cab, trip, hub or charger the scenario does not have. */
    UNKNOWN_REFERENCE,
    /** Activities of one cab overlap, run backwards, or start before the cab is available. */
    TIME_ORDER,
    /** An activity starts somewhere other than where the previous one ended. */
    LOCATION_CONTINUITY,
    /** A drive is shorter in time than the road distance allows, or its distance is wrong. */
    TRAVEL_TIME,
    /** A trip does not start exactly on time or ends after its latest finish. */
    TRIP_TIMING,
    /** Charge dropped below the 15% floor. */
    BATTERY_FLOOR,
    /** Charging claims more energy than the curve can deliver, or above capacity. */
    CHARGE_CURVE,
    /** The state of charge written in the plan differs from the recomputed one. */
    STATE_MISMATCH,
    /** A session's cost differs from the tariff applied to the energy actually added, minute by minute. */
    COST_MISMATCH,
    /** A session uses a charger after it failed or at the wrong hub. */
    CHARGER_UNAVAILABLE,
    /** Two sessions on the same charger overlap. */
    CHARGER_DOUBLE_BOOKED,
    /** More cabs charging at a hub than it has working chargers. */
    HUB_CAPACITY_EXCEEDED
}
