package com.moveinsync.evfleet.core.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything the planner needs for one shift: fleet, hubs, trips, tariff and physics.
 */
public record Scenario(
        String name,
        int shiftStart,
        int shiftEnd,
        List<Vehicle> vehicles,
        List<Hub> hubs,
        List<Trip> trips,
        Tariff tariff,
        FleetPhysics physics) {

    public Scenario {
        vehicles = List.copyOf(vehicles);
        hubs = List.copyOf(hubs);
        trips = List.copyOf(trips);
    }

    public Map<String, Vehicle> vehiclesById() {
        return vehicles.stream().collect(Collectors.toMap(Vehicle::id, Function.identity()));
    }

    public Map<String, Trip> tripsById() {
        return trips.stream().collect(Collectors.toMap(Trip::id, Function.identity()));
    }

    public Map<String, Hub> hubsById() {
        return hubs.stream().collect(Collectors.toMap(Hub::id, Function.identity()));
    }

    public Map<String, Charger> chargersById() {
        return hubs.stream().flatMap(h -> h.chargers().stream())
                .collect(Collectors.toMap(Charger::id, Function.identity()));
    }

    public int chargerCount() {
        return hubs.stream().mapToInt(h -> h.chargers().size()).sum();
    }

    public Scenario withVehicles(List<Vehicle> newVehicles) {
        return new Scenario(name, shiftStart, shiftEnd, newVehicles, hubs, trips, tariff, physics);
    }

    public Scenario withHubs(List<Hub> newHubs) {
        return new Scenario(name, shiftStart, shiftEnd, vehicles, newHubs, trips, tariff, physics);
    }
}
