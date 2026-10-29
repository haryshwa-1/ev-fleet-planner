package com.moveinsync.evfleet.core.plan;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.moveinsync.evfleet.core.model.Location;

/**
 * One step in a cab's day. Gaps between consecutive activities are idle time at the
 * previous activity's end location, which costs no energy.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Deadhead.class, name = "DEADHEAD"),
        @JsonSubTypes.Type(value = TripLeg.class, name = "TRIP"),
        @JsonSubTypes.Type(value = ChargingSession.class, name = "CHARGE")
})
public sealed interface Activity permits Deadhead, TripLeg, ChargingSession {

    int start();

    int end();

    Location from();

    Location to();

    double socStartKWh();

    double socEndKWh();
}
