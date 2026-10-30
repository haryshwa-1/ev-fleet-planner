package com.moveinsync.evfleet.core.cli;

import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.planner.GreedyPlanner;
import com.moveinsync.evfleet.core.planner.LocalSearch;
import com.moveinsync.evfleet.core.planner.MultiStartPlanner;
import com.moveinsync.evfleet.core.planner.Replanner;
import com.moveinsync.evfleet.core.util.Clock;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import com.moveinsync.evfleet.core.validate.ValidationReport;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Times the engine at growing fleet sizes and prints Markdown tables. One unmeasured run warms up
 * the JVM first. Trips scale at 5 per cab and chargers at about 2 per 15 cabs, as in the 300-cab case.
 * <pre>
 *   ./mvnw -q -pl planner-core exec:java -Dexec.mainClass=com.moveinsync.evfleet.core.cli.PlannerBenchmark
 * </pre>
 */
public final class PlannerBenchmark {

    private static final int[] FLEETS = {100, 300, 1000, 3000};
    /** Above this size the 16-run search is skipped: one greedy run already passes a minute. */
    private static final int MULTI_START_MAX_CABS = 1000;
    private static final int EVENTS = 200;

    public static void main(String[] args) {
        new GreedyPlanner().plan(new ScenarioGenerator().generate(params(300)));

        System.out.println("| Cabs | Trips | Chargers | Greedy s | Multi-start s | Local search s | Backup (greedy) | Backup (multi) | Backup (final) | Lower bound | Bound s | Validate ms | Valid |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        StringBuilder replans = new StringBuilder("""
                | Cabs | Events | Valid | p50 ms | p95 ms | p99 ms | Max ms | Events/s | Trips moved per event |
                |---|---|---|---|---|---|---|---|---|
                """);
        for (int cabs : FLEETS) {
            ScenarioGenerator.Params p = params(cabs);
            Scenario s = new ScenarioGenerator().generate(p);

            long t = System.nanoTime();
            Plan greedy = new GreedyPlanner().plan(s);
            double greedySeconds = seconds(t);

            String multiSeconds = "skipped";
            String multiBackup = "-";
            String searchSeconds = "skipped";
            String finalBackup = "-";
            Plan best = greedy;
            if (cabs <= MULTI_START_MAX_CABS) {
                t = System.nanoTime();
                best = MultiStartPlanner.standard().plan(s).best().plan();
                multiSeconds = String.format("%.1f", seconds(t));
                multiBackup = String.valueOf(best.unassigned().size());
                t = System.nanoTime();
                best = new LocalSearch().improve(s, best).plan();
                searchSeconds = String.format("%.1f", seconds(t));
                finalBackup = String.valueOf(best.unassigned().size());
            }

            t = System.nanoTime();
            int bound = new LowerBoundCalculator().compute(s).unassignedLowerBound();
            double boundSeconds = seconds(t);

            t = System.nanoTime();
            ValidationReport report = new PlanValidator().validate(s, best);
            long validateMillis = (System.nanoTime() - t) / 1_000_000;

            System.out.printf("| %d | %d | %d | %.1f | %s | %s | %d | %s | %s | %d | %.1f | %d | %s |%n", cabs, p.trips(),
                    p.hubs() * p.chargersPerHub(), greedySeconds, multiSeconds, searchSeconds, greedy.unassigned().size(),
                    multiBackup, finalBackup,
                    bound, boundSeconds, validateMillis, report.valid() ? "yes" : "NO");
            System.out.flush();
            replans.append(replanRow(s, best));
        }
        System.out.println();
        System.out.print(replans);
    }

    /** Half drift, half charger failures, each on the unchanged plan, at random evening times. */
    private static String replanRow(Scenario s, Plan plan) {
        Random rnd = new Random(11);
        List<Vehicle> cabs = s.vehicles();
        List<String> chargers = s.chargersById().keySet().stream().sorted().toList();
        Replanner replanner = new Replanner();
        long[] millis = new long[EVENTS];
        int valid = 0;
        long moved = 0;
        long start = System.nanoTime();
        for (int i = 0; i < EVENTS; i++) {
            int now = Clock.hm(17, 30) + rnd.nextInt(7 * 60);
            Replanner.Result r;
            if (i % 2 == 0) {
                Vehicle v = cabs.get(rnd.nextInt(cabs.size()));
                r = replanner.onDrift(s, plan, new Replanner.DriftEvent(v.id(), now,
                        v.batteryKWh() * (0.3 + rnd.nextDouble() * 0.6)));
            } else {
                r = replanner.onChargerFailure(s, plan,
                        new Replanner.ChargerFailure(chargers.get(rnd.nextInt(chargers.size())), now));
            }
            millis[i] = r.millis();
            if (r.report().valid()) valid++;
            moved += r.diff().reassigned() + r.diff().movedToBackup();
        }
        double total = seconds(start);
        Arrays.sort(millis);
        return String.format("| %d | %d | %d | %d | %d | %d | %d | %.0f | %.1f |%n", cabs.size(), EVENTS, valid,
                millis[EVENTS / 2], millis[(int) (EVENTS * 0.95)], millis[(int) (EVENTS * 0.99)], millis[EVENTS - 1],
                EVENTS / total, (double) moved / EVENTS);
    }

    private static ScenarioGenerator.Params params(int cabs) {
        int hubs = Math.max(2, cabs / 60);
        return new ScenarioGenerator.Params(cabs, hubs, 8, cabs * 5, 42L);
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }
}
