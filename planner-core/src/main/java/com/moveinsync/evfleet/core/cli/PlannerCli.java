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
import com.moveinsync.evfleet.core.planner.PlannerConfig;
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
        for (String key : List.of("lowSoc", "lowSocPenalty", "target", "hubs", "idle")) {
            if (opts.containsKey(key)) config = config.with(key, Double.parseDouble(opts.get(key)));
        }
        Plan plan = new GreedyPlanner(config).plan(scenario);
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
        System.out.printf("Lower bound on backup trips: %d (intrinsic %d, unreachable %d, concurrency %d [peak %d], energy %d, chains %d)"
                        + " -> gap <= %d trips%n", lb.unassignedLowerBound(), lb.intrinsicallyInfeasible(),
                lb.unreachable(), lb.concurrencyExcess(), lb.peakConcurrentTrips(), lb.energyExcess(), lb.chainExcess(),
                backup - lb.unassignedLowerBound());
        System.out.printf("Charging cost lower bound (if every coverable trip is served): Rs %.2f%n", lb.costLowerBoundRupees());
        Map<ReasonCode, Long> reasons = plan.unassigned().stream()
                .collect(Collectors.groupingBy(UnassignedTrip::reason, TreeMap::new, Collectors.counting()));
        System.out.println("Backup reasons: " + reasons);
        System.out.println("Validator: " + report.summary());
        report.violations().stream().limit(10).forEach(v -> System.out.println("  ! " + v));

        if (opts.containsKey("timeline")) printTimeline(plan, opts.get("timeline"));

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
        summary.put("lowerBound", lb);
        summary.put("valid", report.valid());
        json.writeValue(out.resolve("summary.json").toFile(), summary);
        System.out.println("Wrote " + out.toAbsolutePath());
        if (!report.valid()) System.exit(2);
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
