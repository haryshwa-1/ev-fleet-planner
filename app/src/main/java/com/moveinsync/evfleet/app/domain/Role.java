package com.moveinsync.evfleet.app.domain;

public enum Role {
    /** Sees only their own trip. */
    EMPLOYEE,
    /** Sends live events and reads plans. */
    OPERATOR,
    /** Everything an operator can do, plus creating shifts and running the planner. */
    ADMIN
}
