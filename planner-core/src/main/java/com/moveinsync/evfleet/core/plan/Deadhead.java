package com.moveinsync.evfleet.core.plan;

import com.moveinsync.evfleet.core.model.Location;

/** An empty drive: to a trip's pickup point or to a charging hub. */
public record Deadhead(
        int start,
        int end,
        Location from,
        Location to,
        double km,
        double socStartKWh,
        double socEndKWh) implements Activity {
}
