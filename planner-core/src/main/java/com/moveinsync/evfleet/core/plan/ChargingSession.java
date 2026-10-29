package com.moveinsync.evfleet.core.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moveinsync.evfleet.core.model.Location;

/** The cab occupying {@code chargerId} at {@code hubId} from start to end. */
public record ChargingSession(
        String hubId,
        String chargerId,
        int start,
        int end,
        Location location,
        double socStartKWh,
        double socEndKWh,
        double costRupees) implements Activity {

    @Override
    @JsonIgnore
    public Location from() {
        return location;
    }

    @Override
    @JsonIgnore
    public Location to() {
        return location;
    }

    public boolean overlaps(int otherStart, int otherEnd) {
        return start < otherEnd && otherStart < end;
    }
}
