package com.moveinsync.evfleet.core.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.moveinsync.evfleet.core.bound.LowerBound;
import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Deadhead;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.ReasonCode;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import com.moveinsync.evfleet.core.planner.GreedyPlanner;
import com.moveinsync.evfleet.core.planner.MultiStartPlanner;
import com.moveinsync.evfleet.core.planner.PlannerConfig;
import com.moveinsync.evfleet.core.planner.LocalSearch;
import com.moveinsync.evfleet.core.planner.OvernightCharging;
import com.moveinsync.evfleet.core.planner.Replanner;
import com.moveinsync.evfleet.core.util.Clock;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import com.moveinsync.evfleet.core.validate.ValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Plans a generated scenario end to end and writes plan + validator report.
 * <pre>
 *   ./mvnw -q -pl planner-core exec:java -Dexec.args="--cabs 300 --hubs 5 --chargers-per-hub 8 --trips 1500 --seed 42"
 * </pre>
 */
public final class PlannerCli {

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = parse(args);
        var params = new ScenarioGenerator.Params(
                Integer.parseInt(opts.getOrDefault("cabs", "300")),
                Integer.parseInt(opts.getOrDefault("hubs", "5")),
                Integer.parseInt(opts.getOrDefault("chargers-per-hub", "8")),
                Integer.parseInt(opts.getOrDefault("trips", "1500")),
                Long.parseLong(opts.getOrDefault("seed", "42")));
        Path out = Path.of(opts.getOrDefault("out", "out"));

        Scenario scenario = new ScenarioGenerator().generate(params);
        System.out.println("Scenario: " + scenario.name());

        long t0 = System.nanoTime();
        PlannerConfig config = PlannerConfig.defaults();
        boolean tuned = false;
        for (String key : PlannerConfig.OPTIONS) {
            if (opts.containsKey(key)) {
                config = config.with(key, Double.parseDouble(opts.get(key)));
                tuned = true;
            }
        }
        // Explicit options mean "try this one configuration"; otherwise search over the standard set.
        Plan plan;
        if (tuned) {
            plan = new GreedyPlanner(config).plan(scenario);
        } else {
            MultiStartPlanner.Result result = MultiStartPlanner.standard().plan(scenario);
            LocalSearch.Result improved = new LocalSearch().improve(scenario, result.best().plan());
            plan = improved.plan();
            System.out.printf("Multi-start: %d configurations, best %s (backup trips ranged %d-%d)%n",
                    result.runs().size(), result.best().config(),
                    result.runs().stream().mapToInt(MultiStartPlanner.Run::backupTrips).min().orElse(0),
                    result.runs().stream().mapToInt(MultiStartPlanner.Run::backupTrips).max().orElse(0));
            System.out.printf("Local search: %d of %d rebuilds kept, backup trips %d -> %d%n", improved.kept(), improved.steps(),
                    result.best().backupTrips(), plan.unassigned().size());
        }
        long planMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        ValidationReport report = new PlanValidator().validate(scenario, plan);
        long validateMs = (System.nanoTime() - t1) / 1_000_000;
        LowerBound lb = new LowerBoundCalculator().compute(scenario);

        int total = scenario.trips().size();
        int backup = plan.unassigned().size();
        System.out.printf("Planned in %d ms (validated in %d ms)%n", planMs, validateMs);
        System.out.printf("Trips: %d electric / %d total, %d to backup cabs (%.1f%% coverage)%n",
                total - backup, total, backup, 100.0 * (total - backup) / total);
        System.out.printf("Charging: %d sessions, Rs %.2f; empty driving %.0f km%n",
                plan.chargingSessionCount(), plan.totalChargingCost(), plan.deadheadKm());
        double endKWh = MultiStartPlanner.endOfShiftEnergyKWh(scenario, plan);
        double netCost = MultiStartPlanner.netCostRupees(scenario, plan);
        System.out.printf("Fleet ends the shift with %.0f kWh; cost including overnight refill to the starting charge: Rs %.2f%n",
                endKWh, netCost);
        OvernightCharging.Result night = new OvernightCharging().schedule(scenario, plan);
        System.out.printf("Overnight: %d cabs back to %.0f%% by %s for Rs %.0f, %d not ready%n", night.cabsCharged(),
                OvernightCharging.DEFAULT_TARGET_FRACTION * 100, Clock.format(night.lastFinish()), night.costRupees(), night.notReady().size());
        System.out.printf("Lower bound on backup trips: %d (intrinsic %d, unreachable %d, concurrency %d [peak %d], energy %d, chains %d)"
                        + " -> gap <= %d trips%n", lb.unassignedLowerBound(), lb.intrinsicallyInfeasible(),
                lb.unreachable(), lb.concurrencyExcess(), lb.peakConcurrentTrips(), lb.energyExcess(), lb.chainExcess(),
                backup - lb.unassignedLowerBound());
        Map<ReasonCode, Long> reasons = plan.unassigned().stream()
                .collect(Collectors.groupingBy(UnassignedTrip::reason, TreeMap::new, Collectors.counting()));
        System.out.println("Backup reasons: " + reasons);
        System.out.println("Validator: " + report.summary());
        report.violations().stream().limit(10).forEach(v -> System.out.println("  ! " + v));

        if (opts.containsKey("timeline")) printTimeline(plan, opts.get("timeline"));
        if (opts.containsKey("drift")) {
            // CAB-007@19:30=22%
            String[] cabRest = opts.get("drift").split("@");
            String[] timeSoc = cabRest[1].split("=");
            double battery = scenario.vehiclesById().get(cabRest[0]).batteryKWh();
            double soc = battery * Double.parseDouble(timeSoc[1].replace("%", "")) / 100;
            Replanner.Result r = new Replanner().onDrift(scenario, plan,
                    new Replanner.DriftEvent(cabRest[0], Clock.parse(timeSoc[0]), soc));
            printReplan("Drift: " + cabRest[0] + " reports " + timeSoc[1] + " at " + timeSoc[0], r);
            writeReplan(out, r);
        }
        if (opts.containsKey("replan-bench")) replanBenchmark(scenario, plan, Integer.parseInt(opts.get("replan-bench")));
        if (opts.containsKey("fail-charger")) {
            // H1-C3@19:00
            String[] parts = opts.get("fail-charger").split("@");
            Replanner.Result r = new Replanner().onChargerFailure(scenario, plan,
                    new Replanner.ChargerFailure(parts[0], Clock.parse(parts[1])));
            printReplan("Charger failure: " + parts[0] + " out of service from " + parts[1], r);
            writeReplan(out, r);
        }

        Files.createDirectories(out);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        json.writeValue(out.resolve("plan.json").toFile(), plan);
        json.writeValue(out.resolve("validator-report.json").toFile(), report);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scenario", scenario.name());
        summary.put("planMillis", planMs);
        summary.put("tripsTotal", total);
        summary.put("tripsElectric", total - backup);
        summary.put("tripsBackup", backup);
        summary.put("backupReasons", reasons);
        summary.put("chargingCostRupees", Math.round(plan.totalChargingCost() * 100) / 100.0);
        summary.put("endOfShiftEnergyKWh", Math.round(endKWh * 10) / 10.0);
        summary.put("netCostRupees", Math.round(netCost * 100) / 100.0);
        summary.put("lowerBound", lb);
        summary.put("valid", report.valid());
        json.writeValue(out.resolve("summary.json").toFile(), summary);
        System.out.println("Wrote " + out.toAbsolutePath());
        if (!report.valid()) System.exit(2);
    }

    /** Random drift and charger-failure events (half each) against the same plan; prints latency percentiles. */
    private static void replanBenchmark(Scenario scenario, Plan plan, int events) {
        java.util.Random rnd = new java.util.Random(11);
        List<String> cabs = scenario.vehicles().stream().map(v -> v.id()).sorted().toList();
        List<String> chargers = scenario.chargersById().keySet().stream().sorted().toList();
        Replanner replanner = new Replanner();
        long[] millis = new long[events];
        int valid = 0;
        long moved = 0;
        for (int i = 0; i < events; i++) {
            int now = Clock.hm(17, 30) + rnd.nextInt(7 * 60);
            Replanner.Result r;
            if (i % 2 == 0) {
                var v = scenario.vehiclesById().get(cabs.get(rnd.nextInt(cabs.size())));
                r = replanner.onDrift(scenario, plan, new Replanner.DriftEvent(v.id(), now,
                        v.batteryKWh() * (0.3 + rnd.nextDouble() * 0.6)));
            } else {
                r = replanner.onChargerFailure(scenario, plan,
                        new Replanner.ChargerFailure(chargers.get(rnd.nextInt(chargers.size())), now));
            }
            millis[i] = r.millis();
            if (r.report().valid()) valid++;
            moved += r.diff().reassigned() + r.diff().movedToBackup();
        }
        java.util.Arrays.sort(millis);
        System.out.printf("%nRe-plan benchmark: %d events, %d valid; latency p50 %d ms, p95 %d ms, p99 %d ms, max %d ms;"
                        + " %.1f trips moved per event on average%n", events, valid, millis[events / 2],
                millis[(int) (events * 0.95)], millis[(int) (events * 0.99)], millis[events - 1], (double) moved / events);
    }

    private static void printReplan(String title, Replanner.Result r) {
        System.out.println();
        System.out.println(title);
        System.out.println("  Old plan in the new situation: "
                + new PlanValidator().validate(r.snapshot().scenario(), r.before()).summary());
        System.out.printf("  Re-planned in %d ms, %d cabs around the event considered%n", r.millis(), r.neighbourhood());
        System.out.println("  Changes: " + r.diff().summary());
        r.diff().tripChanges().forEach(c -> System.out.printf("    %s: %s -> %s%n", c.tripId(),
                c.fromCab() == null ? "backup" : c.fromCab(), c.toCab() == null ? "backup" : c.toCab()));
        System.out.println("  New plan: " + r.report().summary());
    }

    private static void writeReplan(Path out, Replanner.Result r) throws IOException {
        Files.createDirectories(out);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("millis", r.millis());
        json.put("diff", r.diff());
        json.put("validator", r.report());
        json.put("plan", r.after());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.resolve("replan.json").toFile(), json);
    }

    private static void printTimeline(Plan plan, String cabId) {
        System.out.println("Timeline for " + cabId + ":");
        for (Activity a : plan.timeline(cabId)) {
            String what = switch (a) {
                case Deadhead d -> String.format("empty drive %.1f km", d.km());
                case TripLeg t -> String.format("trip %s %.1f km", t.tripId(), t.km());
                case ChargingSession c -> String.format("charge at %s/%s Rs %.2f", c.hubId(), c.chargerId(), c.costRupees());
            };
            System.out.printf("  %s-%s  %-38s %5.1f kWh -> %5.1f kWh%n",
                    Clock.format(a.start()), Clock.format(a.end()), what, a.socStartKWh(), a.socEndKWh());
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) m.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        return m;
    }
}
