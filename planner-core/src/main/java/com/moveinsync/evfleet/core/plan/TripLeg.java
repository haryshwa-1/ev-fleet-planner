package com.moveinsync.evfleet.core.plan;

import com.moveinsync.evfleet.core.model.Location;

/** The cab carrying employees on {@code tripId}. */
public record TripLeg(
        String tripId,
        int start,
        int end,
        Location from,
        Location to,
        double km,
        double socStartKWh,
        double socEndKWh) implements Activity {
}
