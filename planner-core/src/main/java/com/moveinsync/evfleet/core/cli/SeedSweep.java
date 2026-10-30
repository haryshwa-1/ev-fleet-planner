package com.moveinsync.evfleet.core.cli;

import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.planner.MultiStartPlanner;
import com.moveinsync.evfleet.core.planner.Replanner;
import com.moveinsync.evfleet.core.validate.PlanValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Checks that results are not special to the demo's seed: plans the 300-cab shift on other seeds, and
 * fires random drift events at each plan. Prints a Markdown table for BENCHMARK.md.
 */
public final class SeedSweep {

    private static final int EVENTS_PER_SEED = 40;

    private SeedSweep() {
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        System.out.println("| Seed | Backup | Lower bound | Gap | Cost incl. refill | Plan time | Validator | Re-plans valid | Stranded | Slowest re-plan |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|");
        for (int seed = 1; seed <= seeds; seed++) {
            Scenario scenario = new ScenarioGenerator().generate(new ScenarioGenerator.Params(300, 5, 8, 1500, seed));
            long t0 = System.nanoTime();
            MultiStartPlanner.Run best = MultiStartPlanner.standard().plan(scenario).best();
            long planMillis = (System.nanoTime() - t0) / 1_000_000;
            boolean valid = new PlanValidator().validate(scenario, best.plan()).valid();
            int bound = new LowerBoundCalculator().compute(scenario).unassignedLowerBound();

            Random rnd = new Random(seed);
            List<String> cabs = new ArrayList<>(scenario.vehiclesById().keySet());
            Collections.sort(cabs);
            int ok = 0;
            int stranded = 0;
            long slowest = 0;
            for (int i = 0; i < EVENTS_PER_SEED; i++) {
                int now = 17 * 60 + 30 + rnd.nextInt(7 * 60);
                String cab = cabs.get(rnd.nextInt(cabs.size()));
                double soc = scenario.vehiclesById().get(cab).batteryKWh() * (0.05 + rnd.nextDouble() * 0.85);
                Replanner.Result r = new Replanner().onDrift(scenario, best.plan(), new Replanner.DriftEvent(cab, now, soc));
                if (r.report().valid()) ok++;
                if (r.strandedCab() != null) stranded++;
                slowest = Math.max(slowest, r.millis());
            }
            System.out.printf("| %d | %d | %d | %d | Rs %.0f | %.1f s | %s | %d/%d | %d | %d ms |%n", seed, best.backupTrips(),
                    bound, best.backupTrips() - bound, best.netCostRupees(), planMillis / 1000.0, valid ? "VALID" : "INVALID",
                    ok, EVENTS_PER_SEED, stranded, slowest);
        }
    }
}
