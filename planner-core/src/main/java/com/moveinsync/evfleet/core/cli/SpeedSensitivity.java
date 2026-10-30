package com.moveinsync.evfleet.core.cli;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.geo.GeoModel;
import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Deadhead;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.planner.MultiStartPlanner;
import com.moveinsync.evfleet.core.validate.PlanValidator;

import java.util.Map;

/**
 * The traffic speeds are estimates, so how much do results depend on them? Two questions, for each
 * speed error: what if we had planned knowing the true speeds, and what happens to a plan made with
 * our speeds if the true ones differ and nobody re-plans? Prints Markdown tables for BENCHMARK.md.
 */
public final class SpeedSensitivity {

    private static final long[] SEEDS = {42, 1, 2, 3, 4};
    private static final double[] FACTORS = {0.8, 0.9, 1.0, 1.1, 1.2};

    private SpeedSensitivity() {
    }

    public static void main(String[] args) {
        System.out.println("| True speed vs assumed | Backup, planned knowing it | Cost incl. refill | All valid | Pickups late, no re-plan | Over 10 min late | Worst | Cabs below 15% floor |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        for (double factor : FACTORS) {
            double backup = 0;
            double cost = 0;
            boolean valid = true;
            Outcome total = new Outcome();
            for (long seed : SEEDS) {
                Scenario assumed = new ScenarioGenerator().generate(new ScenarioGenerator.Params(300, 5, 8, 1500, seed));
                Scenario truth = withSpeeds(assumed, factor);
                Plan informed = MultiStartPlanner.standard().planAndImprove(truth);
                backup += informed.unassigned().size();
                cost += MultiStartPlanner.netCostRupees(truth, informed);
                valid &= new PlanValidator().validate(truth, informed).valid();
                drive(truth, MultiStartPlanner.standard().planAndImprove(assumed), total);
            }
            System.out.printf("| %+.0f%% | %.1f | Rs %.0f | %s | %.1f | %.1f | %d min | %.1f |%n", (factor - 1) * 100,
                    backup / SEEDS.length, cost / SEEDS.length, valid ? "yes" : "NO", (double) total.late / SEEDS.length,
                    (double) total.veryLate / SEEDS.length, total.worst, (double) total.belowFloor / SEEDS.length);
        }
    }

    private static Scenario withSpeeds(Scenario s, double factor) {
        FleetPhysics p = s.physics();
        GeoModel geo = new GeoModel(p.geo().roadFactor(), p.geo().traffic().withSpeedsScaled(factor));
        FleetPhysics changed = new FleetPhysics(geo, p.consumption(), p.curve(), p.socFloorFraction());
        return new Scenario(s.name(), s.shiftStart(), s.shiftEnd(), s.vehicles(), s.hubs(), s.trips(), s.tariff(), changed);
    }

    private static final class Outcome {
        int late;
        int veryLate;
        int worst;
        int belowFloor;
    }

    /** Follows each cab's plan under the true speeds: it never leaves early, and delays carry over. */
    private static void drive(Scenario truth, Plan plan, Outcome out) {
        FleetPhysics physics = truth.physics();
        Map<String, Trip> trips = truth.tripsById();
        for (Vehicle v : truth.vehicles()) {
            int clock = v.availableFrom();
            double soc = v.initialSocKWh();
            boolean belowFloor = false;
            for (Activity a : plan.timeline(v.id())) {
                int start = Math.max(clock, a.start());
                switch (a) {
                    case Deadhead d -> {
                        clock = start + physics.geo().travelMinutes(d.km(), start);
                        soc -= physics.driveEnergyKWh(v, d.km(), start);
                    }
                    case TripLeg leg -> {
                        Trip t = trips.get(leg.tripId());
                        int lateBy = start - t.startTime();
                        if (lateBy > 0) out.late++;
                        if (lateBy > 10) out.veryLate++;
                        out.worst = Math.max(out.worst, lateBy);
                        clock = start + physics.geo().travelMinutes(t.distanceKm(), start);
                        soc -= physics.driveEnergyKWh(v, t.distanceKm(), start);
                    }
                    case ChargingSession c -> {
                        clock = start + (c.end() - c.start());
                        soc += c.socEndKWh() - c.socStartKWh();
                    }
                }
                belowFloor |= soc < physics.floorKWh(v);
            }
            if (belowFloor) out.belowFloor++;
        }
    }
}
