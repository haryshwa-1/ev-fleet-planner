package com.moveinsync.evfleet.core.data;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static com.moveinsync.evfleet.core.util.Clock.hm;

/**
 * Builds a deterministic evening-shift scenario: same parameters + seed = same scenario.
 *
 * <p>City: 40 x 40 km, three office parks, hubs spread around the city.
 * Shift 17:00 to 02:00. Most trips are office-to-home drops at logout batches; a minority
 * are home-to-office pickups for the night shift.
 */
public final class ScenarioGenerator {

    public static final double CITY_KM = 40.0;
    private static final Location[] OFFICES = {
            new Location(20, 20), new Location(28, 13), new Location(12, 27)};
    private static final Location[] HUB_SITES = {
            new Location(20, 17), new Location(30, 10), new Location(10, 30),
            new Location(8, 10), new Location(31, 30), new Location(20, 33),
            new Location(33, 20), new Location(6, 20)};
    /** Logout batches and the relative share of trips in each. */
    private static final int[][] BATCHES = {
            {hm(17, 30), 6}, {hm(18, 0), 12}, {hm(18, 30), 10}, {hm(19, 0), 12}, {hm(19, 30), 9},
            {hm(20, 0), 10}, {hm(20, 30), 7}, {hm(21, 0), 8}, {hm(22, 0), 9}, {hm(23, 0), 8},
            {hm(24, 0), 5}, {hm(24, 30), 4}};

    public record Params(int cabs, int hubs, int chargersPerHub, int trips, long seed) {
        public static Params standard() {
            return new Params(300, 5, 8, 1500, 42L);
        }
    }

    public static FleetPhysics standardPhysics() {
        TrafficProfile traffic = new TrafficProfile(hm(17, 30), hm(21, 0));
        return new FleetPhysics(
                new GeoModel(1.3, 20.0, 32.0, traffic),
                new ConsumptionModel(1.15, 1.10, traffic),
                TwoSegmentChargingCurve.standard(),
                0.15);
    }

    public static Tariff standardTariff() {
        return new Tariff(List.of(
                new Tariff.Window("PEAK", hm(18, 0), hm(22, 0), 12.0),
                new Tariff.Window("OFF_PEAK", hm(22, 0), hm(6, 0), 5.0)), 8.0);
    }

    public Scenario generate(Params p) {
        Random rnd = new Random(p.seed());
        FleetPhysics physics = standardPhysics();
        int shiftStart = hm(17, 0);
        int shiftEnd = hm(26, 0);

        List<Hub> hubs = new ArrayList<>();
        for (int h = 0; h < p.hubs(); h++) {
            Location site = h < HUB_SITES.length ? HUB_SITES[h] : randomPoint(rnd);
            String hubId = "H" + (h + 1);
            List<Charger> chargers = new ArrayList<>();
            for (int c = 0; c < p.chargersPerHub(); c++) {
                chargers.add(new Charger(hubId + "-C" + (c + 1), hubId, 30.0));
            }
            hubs.add(new Hub(hubId, site, chargers));
        }

        List<Vehicle> vehicles = new ArrayList<>();
        for (int v = 0; v < p.cabs(); v++) {
            double battery = pick(rnd, 26.0, 30.2, 40.5);
            double socPct = 0.35 + rnd.nextDouble() * 0.55;
            Location base = OFFICES[rnd.nextInt(OFFICES.length)];
            vehicles.add(new Vehicle(
                    String.format("CAB-%03d", v + 1),
                    battery,
                    round2(battery * socPct),
                    round3(0.13 + rnd.nextDouble() * 0.03),
                    battery > 35 ? 50.0 : 30.0,
                    rnd.nextDouble() < 0.8,
                    jitter(rnd, base, 3.0),
                    shiftStart));
        }

        int totalWeight = 0;
        for (int[] b : BATCHES) totalWeight += b[1];
        List<Trip> trips = new ArrayList<>();
        for (int t = 0; t < p.trips(); t++) {
            int start = pickBatch(rnd, totalWeight) + 5 * rnd.nextInt(4);
            Location office = OFFICES[rnd.nextInt(OFFICES.length)];
            Location home = homeNear(rnd, office);
            boolean drop = rnd.nextDouble() < 0.8;
            Location from = drop ? office : home;
            Location to = drop ? home : office;
            double km = round2(physics.geo().roadKm(from, to));
            int duration = physics.geo().travelMinutes(km, start);
            int latestFinish = start + duration + Math.max(15, (int) Math.ceil(duration * 0.3));
            trips.add(new Trip(null, start, from, to, km, latestFinish));
        }
        trips.sort(Comparator.comparingInt(Trip::startTime));
        List<Trip> numbered = new ArrayList<>(trips.size());
        for (int i = 0; i < trips.size(); i++) {
            Trip t = trips.get(i);
            numbered.add(new Trip(String.format("T-%04d", i + 1), t.startTime(), t.start(), t.end(),
                    t.distanceKm(), t.latestFinish()));
        }

        String name = String.format("evening-%dcabs-%dchargers-%dtrips-seed%d",
                p.cabs(), p.hubs() * p.chargersPerHub(), p.trips(), p.seed());
        return new Scenario(name, shiftStart, shiftEnd, vehicles, hubs, numbered, standardTariff(), physics);
    }

    private static int pickBatch(Random rnd, int totalWeight) {
        int r = rnd.nextInt(totalWeight);
        for (int[] b : BATCHES) {
            r -= b[1];
            if (r < 0) return b[0];
        }
        return BATCHES[BATCHES.length - 1][0];
    }

    /** Homes lie 3 to 14 km (straight line) from the office, clipped to the city. */
    private static Location homeNear(Random rnd, Location office) {
        double radius = 3 + rnd.nextDouble() * 11;
        double angle = rnd.nextDouble() * 2 * Math.PI;
        return clip(new Location(office.x() + radius * Math.cos(angle), office.y() + radius * Math.sin(angle)));
    }

    private static Location jitter(Random rnd, Location base, double km) {
        return clip(new Location(base.x() + (rnd.nextDouble() * 2 - 1) * km, base.y() + (rnd.nextDouble() * 2 - 1) * km));
    }

    private static Location randomPoint(Random rnd) {
        return new Location(rnd.nextDouble() * CITY_KM, rnd.nextDouble() * CITY_KM);
    }

    private static Location clip(Location l) {
        return new Location(round2(Math.max(0, Math.min(CITY_KM, l.x()))), round2(Math.max(0, Math.min(CITY_KM, l.y()))));
    }

    private static double pick(Random rnd, double... options) {
        return options[rnd.nextInt(options.length)];
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
