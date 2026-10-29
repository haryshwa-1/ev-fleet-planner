package com.moveinsync.evfleet.core.model;

/**
 * One charging point at a hub.
 *
 * @param failedFrom minute from which the charger is out of service;
 *                   {@link #WORKING} if it never fails within the horizon
 */
public record Charger(String id, String hubId, double powerKW, int failedFrom) {

    public static final int WORKING = Integer.MAX_VALUE;

    public Charger(String id, String hubId, double powerKW) {
        this(id, hubId, powerKW, WORKING);
    }

    public boolean isWorkingThroughout(int start, int end) {
        return end <= failedFrom;
    }

    public Charger failedAt(int minute) {
        return new Charger(id, hubId, powerKW, minute);
    }
}
