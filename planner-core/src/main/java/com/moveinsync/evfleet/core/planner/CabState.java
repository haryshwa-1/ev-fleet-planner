package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Location;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;

import java.util.ArrayList;
import java.util.List;

/**
 * A cab while its plan is being built: the activities so far and where, when and how charged
 * it is at the end of them. Only the planner mutates it.
 */
public final class CabState {

    private final Vehicle vehicle;
    private final List<Activity> timeline = new ArrayList<>();
    private int freeAt;
    private Location location;
    private double socKWh;

    public CabState(Vehicle vehicle) {
        this.vehicle = vehicle;
        this.freeAt = vehicle.availableFrom();
        this.location = vehicle.location();
        this.socKWh = vehicle.initialSocKWh();
    }

    /** A cab that continues from a fixed prefix of activities (used by re-planning). */
    public CabState(Vehicle vehicle, List<Activity> frozenPrefix) {
        this(vehicle);
        for (Activity a : frozenPrefix) append(a);
    }

    public void append(Activity a) {
        timeline.add(a);
        freeAt = a.end();
        location = a.to();
        socKWh = a.socEndKWh();
    }

    public void appendAll(List<Activity> activities) {
        activities.forEach(this::append);
    }

    public Vehicle vehicle() {
        return vehicle;
    }

    public String id() {
        return vehicle.id();
    }

    public List<Activity> timeline() {
        return List.copyOf(timeline);
    }

    public int freeAt() {
        return freeAt;
    }

    public Location location() {
        return location;
    }

    public double socKWh() {
        return socKWh;
    }
}
