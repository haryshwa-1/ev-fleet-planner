package com.moveinsync.evfleet.core;

import com.moveinsync.evfleet.core.energy.ConsumptionModel;
import com.moveinsync.evfleet.core.energy.TwoSegmentChargingCurve;
import com.moveinsync.evfleet.core.geo.GeoModel;
import com.moveinsync.evfleet.core.geo.TrafficProfile;
import com.moveinsync.evfleet.core.model.Charger;
import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Tariff;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;

import java.util.List;

/**
 * Tiny worlds with round numbers so expected values can be worked out by hand:
 * no traffic, 30 km/h, road distance = straight line, 0.2 kWh/km, 20 kWh batteries (floor 3 kWh),
 * flat tariff Rs 10/kWh, 30 kW chargers (27.6 kWh/h effective below 80%).
 */
public final class Fixtures {

    public static final Location ORIGIN = new Location(0, 0);
    public static final FleetPhysics PHYSICS = new FleetPhysics(
            new GeoModel(1.0, 30, 30, new TrafficProfile(0, 0)),
            new ConsumptionModel(1.0, 1.0, new TrafficProfile(0, 0)),
            TwoSegmentChargingCurve.standard(),
            0.15);
    public static final Tariff FLAT = new Tariff(List.of(), 10.0);

    private Fixtures() {
    }

    public static Vehicle cab(String id, double socKWh, Location at) {
        return new Vehicle(id, 20.0, socKWh, 0.2, 30.0, false, at, 600);
    }

    public static Hub hub(String id, Location at, int chargers) {
        List<Charger> list = new java.util.ArrayList<>();
        for (int i = 1; i <= chargers; i++) list.add(new Charger(id + "-C" + i, id, 30.0));
        return new Hub(id, at, list);
    }

    public static Trip trip(String id, int start, Location from, Location to, int slackMinutes) {
        double km = from.straightLineKm(to);
        int duration = (int) Math.ceil(km / 30.0 * 60 - 1e-9);
        return new Trip(id, start, from, to, km, start + duration + slackMinutes);
    }

    public static Scenario scenario(List<Vehicle> cabs, List<Hub> hubs, List<Trip> trips) {
        return new Scenario("fixture", 600, 1500, cabs, hubs, trips, FLAT, PHYSICS);
    }
}
