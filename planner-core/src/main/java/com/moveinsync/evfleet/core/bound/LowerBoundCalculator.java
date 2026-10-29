package com.moveinsync.evfleet.core.bound;

import com.moveinsync.evfleet.core.model.Charger;
import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Hub;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Simple, provable lower bounds on the number of backup trips. Each relaxes the problem
 * (ignores something real) so the true optimum can only be worse:
 * <ol>
 *   <li>Trips no EV can do at all are always backup.</li>
 *   <li>A trip whose pickup no cab can reach from its start position, even waiting for traffic to clear, is always backup.</li>
 *   <li>Among the rest, at any instant at most V trips can run, so peak overlap - V must be dropped.</li>
 *   <li>Total trip energy (ignoring empty km) cannot exceed initial usable energy + every charger
 *       running flat out for the whole shift; drop the largest trips until it fits.</li>
 *   <li>Chains: trips that cannot be served even with unlimited batteries, counting empty drives
 *       and each cab's start position ({@link ChainCoverBound}). This dominates (2) and (3).</li>
 * </ol>
 * Bound = (1) + (2) + max((3), (4), (5)), since (3)-(5) count from the same remaining set.
 * Runs in O(T^2 + E sqrt(T)) because of (5); about 100 ms for 1,500 trips.
 */
public final class LowerBoundCalculator {

    public LowerBound compute(Scenario scenario) {
        FleetPhysics physics = scenario.physics();
        List<Vehicle> vehicles = scenario.vehicles();

        int intrinsic = 0;
        int unreachable = 0;
        List<Trip> remaining = new ArrayList<>();
        for (Trip t : scenario.trips()) {
            boolean windowTooShort = t.startTime() + physics.tripMinutes(t) > t.latestFinish();
            // Only the true rule (floor) here, not the planner's own reserve policy, or the bound would be unsound.
            boolean noRange = vehicles.stream()
                    .allMatch(v -> physics.tripEnergyKWh(v, t) + physics.floorKWh(v) > v.batteryKWh());
            if (windowTooShort || noRange) {
                intrinsic++;
                continue;
            }
            boolean reachable = vehicles.stream().anyMatch(v -> physics.geo().earliestArrival(
                    physics.geo().roadKm(v.location(), t.start()), v.availableFrom()) <= t.startTime());
            if (!reachable) {
                unreachable++;
                continue;
            }
            remaining.add(t);
        }

        TreeMap<Integer, Integer> delta = new TreeMap<>();
        for (Trip t : remaining) {
            delta.merge(t.startTime(), 1, Integer::sum);
            delta.merge(t.startTime() + physics.tripMinutes(t), -1, Integer::sum);
        }
        int running = 0;
        int peak = 0;
        for (int d : delta.values()) {
            running += d;
            peak = Math.max(peak, running);
        }
        int concurrencyExcess = Math.max(0, peak - vehicles.size());

        double supply = 0;
        for (Vehicle v : vehicles) supply += Math.max(0, v.initialSocKWh() - physics.floorKWh(v));
        double shiftHours = (scenario.shiftEnd() - scenario.shiftStart()) / 60.0;
        double chargerEnergy = 0;
        for (Hub h : scenario.hubs()) {
            for (Charger c : h.chargers()) {
                double maxPower = vehicles.stream().mapToDouble(v -> Math.min(v.maxChargeKW(), c.powerKW())).max().orElse(0);
                double eff = physics.curve().socAfter(1e9, 0, maxPower, 60) / maxPower; // effective kWh per kW-hour
                chargerEnergy += maxPower * eff * shiftHours;
            }
        }
        List<Double> demands = new ArrayList<>();
        double demand = 0;
        for (Trip t : remaining) {
            double e = vehicles.stream().mapToDouble(v -> physics.tripEnergyKWh(v, t)).min().orElse(0);
            demands.add(e);
            demand += e;
        }
        demands.sort((a, b) -> Double.compare(b, a));
        int energyExcess = 0;
        double over = demand - (supply + chargerEnergy);
        for (int i = 0; over > 0 && i < demands.size(); i++) {
            over -= demands.get(i);
            energyExcess++;
        }

        int chainExcess = ChainCoverBound.unservable(remaining, vehicles, physics);

        double mustCharge = Math.max(0, demand - supply);
        double costLb = mustCharge * scenario.tariff().cheapestRate();
        int bound = intrinsic + unreachable + Math.max(chainExcess, Math.max(concurrencyExcess, energyExcess));
        return new LowerBound(intrinsic, unreachable, concurrencyExcess, peak, energyExcess, chainExcess, bound,
                Math.round(costLb * 100) / 100.0);
    }
}
