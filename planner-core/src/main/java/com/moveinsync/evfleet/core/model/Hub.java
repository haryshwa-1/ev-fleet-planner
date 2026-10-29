package com.moveinsync.evfleet.core.model;

import java.util.List;

public record Hub(String id, Location location, List<Charger> chargers) {

    public Hub {
        chargers = List.copyOf(chargers);
    }
}
