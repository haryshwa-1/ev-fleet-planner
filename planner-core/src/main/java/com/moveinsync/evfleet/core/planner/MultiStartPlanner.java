package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.Plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Runs the greedy planner once per configuration in parallel and keeps the best plan: fewest
 * backup trips, then lowest net cost. Each run is independent and the winner is picked by a fixed
 * order, so the result does not depend on thread timing.
 *
 * <p>Cost: one greedy run per configuration, spread over the available cores.
 */
public final class MultiStartPlanner {

    /** One run's outcome, kept for reporting. */
    public record Run(PlannerConfig config, Plan plan, int backupTrips, double netCostRupees) {}

    /** The best plan and every run that competed for it. */
    public record Result(Run best, List<Run> runs) {}

    private final List<PlannerConfig> configs;

    public MultiStartPlanner(List<PlannerConfig> configs) {
        if (configs.isEmpty()) throw new IllegalArgumentException("need at least one configuration");
        this.configs = List.copyOf(configs);
    }

    /** The default config varied along the knobs that most change which cab gets which trip. */
    public static MultiStartPlanner standard() {
        List<PlannerConfig> configs = new ArrayList<>();
        PlannerConfig base = PlannerConfig.defaults();
        for (double idle : new double[] {1, 2, 4, 8}) {
            for (double lowSoc : new double[] {0.5, 0.6}) {
                for (double target : new double[] {0.8, 0.9}) {
                    configs.add(base.with("idle", idle).with("lowSoc", lowSoc).with("target", target));
                }
            }
        }
        return new MultiStartPlanner(configs);
    }

    public Result plan(Scenario scenario) {
        List<Run> runs = IntStream.range(0, configs.size()).parallel()
                .mapToObj(i -> {
                    Plan plan = new GreedyPlanner(configs.get(i)).plan(scenario);
                    return new Run(configs.get(i), plan, plan.unassigned().size(), netCostRupees(scenario, plan));
                })
                .toList();
        Run best = runs.stream()
                .min(Comparator.comparingInt(Run::backupTrips).thenComparingDouble(Run::netCostRupees))
                .orElseThrow();
        return new Result(best, runs);
    }

    /** The full planner: the best of the multi-start runs, improved by local search. */
    public Plan planAndImprove(Scenario scenario) {
        return new LocalSearch().improve(scenario, plan(scenario).best().plan()).plan();
    }

    /**
     * Charging bought during the shift plus refilling every cab overnight, at the cheapest tariff,
     * back to the charge it started with. Without the refill, a plan that leaves cabs empty would
     * look cheaper than one that leaves them ready for tomorrow.
     */
    public static double netCostRupees(Scenario scenario, Plan plan) {
        double startKWh = scenario.vehicles().stream().mapToDouble(Vehicle::initialSocKWh).sum();
        double refillKWh = startKWh - endOfShiftEnergyKWh(scenario, plan);
        return plan.totalChargingCost() + refillKWh * scenario.tariff().cheapestRate();
    }

    /** Sum of every cab's charge after its last activity (its starting charge if it did nothing). */
    public static double endOfShiftEnergyKWh(Scenario scenario, Plan plan) {
        double total = 0;
        for (Vehicle v : scenario.vehicles()) {
            List<Activity> timeline = plan.timeline(v.id());
            total += timeline.isEmpty() ? v.initialSocKWh() : timeline.get(timeline.size() - 1).socEndKWh();
        }
        return total;
    }
}
