package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Charger;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All charger schedules of a scenario, grouped by hub. */
public final class ChargerBook {

    private final Map<String, List<ChargerSchedule>> byHub = new LinkedHashMap<>();
    private final Map<String, ChargerSchedule> byCharger = new LinkedHashMap<>();

    public ChargerBook(Scenario scenario) {
        for (Hub hub : scenario.hubs()) {
            List<ChargerSchedule> list = new ArrayList<>();
            for (Charger c : hub.chargers()) {
                ChargerSchedule s = new ChargerSchedule(c);
                list.add(s);
                byCharger.put(c.id(), s);
            }
            byHub.put(hub.id(), list);
        }
    }

    /** A book pre-filled with every session of an existing plan. */
    public static ChargerBook of(Scenario scenario, Plan plan) {
        ChargerBook book = new ChargerBook(scenario);
        for (List<Activity> timeline : plan.timelines().values()) {
            for (Activity a : timeline) {
                if (a instanceof ChargingSession c) book.book(c);
            }
        }
        return book;
    }

    public List<ChargerSchedule> atHub(String hubId) {
        return byHub.getOrDefault(hubId, List.of());
    }

    public ChargerSchedule charger(String chargerId) {
        return byCharger.get(chargerId);
    }

    public void book(ChargingSession c) {
        byCharger.get(c.chargerId()).book(c.start(), c.end());
    }

    public void release(ChargingSession c) {
        byCharger.get(c.chargerId()).release(c.start(), c.end());
    }
}
