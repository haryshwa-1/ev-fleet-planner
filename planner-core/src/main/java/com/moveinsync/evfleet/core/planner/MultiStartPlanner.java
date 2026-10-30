package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.Plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /** A plan finished within a time budget, and how much of the search the time allowed. */
    public record TimedPlan(Plan plan, int runsFinished, int runsTotal, int searchSteps) {}

    private static final long NO_LIMIT_MILLIS = TimeUnit.DAYS.toMillis(365);
    /** Share of a budget for multi-start; local search gets what is left. */
    private static final double MULTI_START_SHARE = 0.8;

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
        return plan(scenario, NO_LIMIT_MILLIS);
    }

    /**
     * Stops waiting for more runs after {@code budgetMillis} and picks among those that finished, so a
     * slow machine gets a slightly weaker plan instead of none. It always waits for at least one run.
     * With time to spare, every run finishes and the result is the same as with no budget.
     */
    public Result plan(Scenario scenario, long budgetMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(Runtime.getRuntime().availableProcessors(), configs.size()));
        try {
            ExecutorCompletionService<Run> running = new ExecutorCompletionService<>(pool);
            List<Future<Run>> futures = new ArrayList<>();
            for (PlannerConfig config : configs) {
                futures.add(running.submit(() -> {
                    Plan plan = new GreedyPlanner(config).plan(scenario);
                    return new Run(config, plan, plan.unassigned().size(), netCostRupees(scenario, plan));
                }));
            }
            for (int finished = 0; finished < futures.size(); finished++) {
                long left = deadline - System.nanoTime();
                Future<Run> next = finished == 0 ? running.take() : running.poll(Math.max(left, 0), TimeUnit.NANOSECONDS);
                if (next == null) break;
                next.get();
            }
            // In configuration order, so which run wins does not depend on thread timing.
            List<Run> runs = new ArrayList<>();
            for (Future<Run> f : futures) if (f.isDone()) runs.add(f.get());
            return pickBest(runs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("planning was cancelled");
        } catch (ExecutionException e) {
            throw new IllegalStateException("a planner run failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Result pickBest(List<Run> runs) {
        Run best = runs.stream()
                .min(Comparator.comparingInt(Run::backupTrips).thenComparingDouble(Run::netCostRupees))
                .orElseThrow();
        return new Result(best, runs);
    }

    /** The full planner: the best of the multi-start runs, improved by local search. */
    public Plan planAndImprove(Scenario scenario) {
        return new LocalSearch().improve(scenario, plan(scenario).best().plan()).plan();
    }

    /** The full planner, finishing within {@code budgetMillis} once at least one run has finished. */
    public TimedPlan planWithin(Scenario scenario, long budgetMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        Result started = plan(scenario, (long) (budgetMillis * MULTI_START_SHARE));
        LocalSearch.Result improved = new LocalSearch().improve(scenario, started.best().plan(), deadline);
        return new TimedPlan(improved.plan(), started.runs().size(), configs.size(), improved.steps());
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
