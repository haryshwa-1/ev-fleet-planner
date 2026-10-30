package com.moveinsync.evfleet.app.service;

import com.moveinsync.evfleet.app.domain.PlanVersion;
import com.moveinsync.evfleet.app.domain.Shift;
import com.moveinsync.evfleet.app.error.ApiException;
import com.moveinsync.evfleet.app.monitoring.FleetMetrics;
import com.moveinsync.evfleet.app.repository.PlanVersionRepository;
import com.moveinsync.evfleet.app.repository.ShiftRepository;
import com.moveinsync.evfleet.core.planner.OvernightCharging;
import com.moveinsync.evfleet.core.bound.LowerBound;
import com.moveinsync.evfleet.core.bound.LowerBoundCalculator;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import com.moveinsync.evfleet.core.planner.MultiStartPlanner;
import com.moveinsync.evfleet.core.util.Clock;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import com.moveinsync.evfleet.core.validate.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Creating shifts, running the full planner, and read-only views of the live plan. */
@Service
public class ShiftService {

    private static final Logger log = LoggerFactory.getLogger(ShiftService.class);
    private static final String INITIAL = "INITIAL_PLAN";

    /** The headline numbers of a live plan. {@code gap} is the distance from the proven best. */
    public record Summary(long shiftId, int version, String cause, String asOf, int totalTrips, long electricTrips,
                          int backupTrips, double chargingCostRupees, int lowerBound, int gap, long planningMillis) {
    }

    /** What an employee may see: their own trip and nothing about anyone else. */
    private static final long STARTUP_WAIT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** Share of the timeout for planning, leaving time to validate and publish before it runs out. */
    private static final double PLANNING_SHARE = 0.8;

    public record Overnight(double targetPercent, int cabsCharged, double energyKWh, double costRupees, String lastFinish,
                            List<String> notReady, boolean valid) {
    }

    public record MyTrip(String tripId, String pickupTime, String status, String cabId) {
    }

    private final ShiftRepository shifts;
    private final PlanVersionRepository versions;
    private final PlanStore store;
    private final FleetMetrics metrics;
    private final TransactionTemplate tx;
    private final ExecutorService plannerThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final long timeoutMillis;

    public ShiftService(ShiftRepository shifts, PlanVersionRepository versions, PlanStore store, FleetMetrics metrics,
                        PlatformTransactionManager transactions,
                        @Value("${app.planner.timeout-millis}") long timeoutMillis) {
        this.shifts = shifts;
        this.versions = versions;
        this.store = store;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactions);
        this.timeoutMillis = timeoutMillis;
    }

    public boolean firstShiftIsPlanned(String tenant) {
        return shifts.findFirstByTenantIdOrderByIdAsc(tenant).filter(s -> s.getCurrentVersion() > 0).isPresent();
    }

    public Shift create(String tenant, ScenarioGenerator.Params p) {
        return shifts.save(new Shift(tenant, p.cabs(), p.hubs(), p.chargersPerHub(), p.trips(), p.seed()));
    }

    /**
     * Runs the full planner and publishes the result if the validator accepts it. Planning happens
     * outside any transaction, so it never holds a database connection or lock for seconds. Once live
     * events have been applied, a fresh plan would overwrite what really happened, so it is refused.
     */
    public Summary plan(String tenant, long shiftId) {
        return plan(tenant, shiftId, timeoutMillis);
    }

    /**
     * Plans with a long wait, for startup: on a slow machine the planner still stops improving at its
     * normal budget, but even a very slow first run must not stop the application from starting.
     */
    public Summary planAtStartup(String tenant, long shiftId) {
        return plan(tenant, shiftId, STARTUP_WAIT_MILLIS);
    }

    public Summary plan(String tenant, long shiftId, long waitMillis) {
        Shift shift = find(tenant, shiftId);
        if (shift.getCurrentVersion() > 0 && !INITIAL.equals(store.row(shift).getCause())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHIFT_IS_LIVE", "Live events were applied; the plan can only be repaired now");
        }
        int seenVersion = shift.getCurrentVersion();
        long t0 = System.nanoTime();
        Scenario scenario = new ScenarioGenerator().generate(params(shift));
        Plan plan = planWithinTimeout(shiftId, scenario, waitMillis);
        long millis = (System.nanoTime() - t0) / 1_000_000;
        metrics.planLatency().record(millis, TimeUnit.MILLISECONDS);

        ValidationReport report = new PlanValidator().validate(scenario, plan);
        if (!report.valid()) {
            metrics.validatorFailure();
            log.error("planner produced an invalid plan for shift {}: {}", shiftId, report.summary());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PLAN_REJECTED",
                    "The validator rejected the new plan; the previous plan stays live");
        }
        LowerBound bound = new LowerBoundCalculator().compute(scenario);
        PlanState state = PlanState.initial(scenario, plan);
        tx.executeWithoutResult(status -> {
            Shift locked = shifts.lockByIdAndTenantId(shiftId, tenant).orElseThrow(() -> ApiException.notFound("Shift"));
            if (locked.getCurrentVersion() != seenVersion) {
                throw new ApiException(HttpStatus.CONFLICT, "PLAN_CONFLICT", "The plan changed while this one was being computed");
            }
            store.publish(locked, state, INITIAL, bound.unassignedLowerBound(), millis);
        });
        metrics.unassigned(state.backupTrips());
        log.info("shift {} planned in {} ms, {} backup trips", shiftId, millis, state.backupTrips());
        return summary(tenant, shiftId);
    }

    private Plan planWithinTimeout(long shiftId, Scenario scenario, long waitMillis) {
        long budget = (long) (timeoutMillis * PLANNING_SHARE);
        Future<MultiStartPlanner.TimedPlan> run = plannerThreads.submit(() -> MultiStartPlanner.standard().planWithin(scenario, budget));
        try {
            MultiStartPlanner.TimedPlan timed = run.get(waitMillis, TimeUnit.MILLISECONDS);
            if (timed.runsFinished() < timed.runsTotal()) {
                log.warn("shift {}: slow machine, used {} of {} planner runs and {} search steps", shiftId,
                        timed.runsFinished(), timed.runsTotal(), timed.searchSteps());
            }
            return timed.plan();
        } catch (TimeoutException e) {
            run.cancel(true);
            metrics.plannerTimeout();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLANNER_TIMEOUT",
                    "Planning took too long; the previous plan stays live");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLANNER_INTERRUPTED", "Planning was interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException("planner failed", e.getCause());
        }
    }

    public Summary summary(String tenant, long shiftId) {
        Shift shift = find(tenant, shiftId);
        PlanState state = store.current(shift);
        PlanVersion row = store.row(shift);
        return new Summary(shiftId, row.getVersion(), row.getCause(), Clock.format(state.asOfMinute()), shift.getTrips(),
                state.electricTrips(), state.backupTrips(), Math.round(state.chargingCost() * 100) / 100.0,
                row.getLowerBound(), state.backupTrips() - row.getLowerBound(), row.getPlanningMillis());
    }

    public List<UnassignedTrip> unassigned(String tenant, long shiftId) {
        PlanState state = store.current(find(tenant, shiftId));
        return java.util.stream.Stream.concat(state.pastBackup().stream(), state.plan().unassigned().stream()).toList();
    }

    public List<Activity> timeline(String tenant, long shiftId, String cabId) {
        PlanState state = store.current(find(tenant, shiftId));
        if (state.vehicles().stream().noneMatch(v -> v.id().equals(cabId))) throw ApiException.notFound("Cab");
        return state.fullTimeline(cabId);
    }

    /** Runs the independent validator on the rest of the shift as it stands now. */
    public ValidationReport validate(String tenant, long shiftId) {
        PlanState state = store.current(find(tenant, shiftId));
        return new PlanValidator().validate(state.scenario(), state.plan());
    }

    /** Whether the fleet can be ready for tomorrow from this plan; computed on demand, never stored. */
    public Overnight overnight(String tenant, long shiftId) {
        PlanState state = store.current(find(tenant, shiftId));
        OvernightCharging.Result r = new OvernightCharging().schedule(state.scenario(), state.plan());
        ValidationReport report = new PlanValidator().validate(state.scenario(), r.plan());
        return new Overnight(OvernightCharging.DEFAULT_TARGET_FRACTION * 100, r.cabsCharged(), r.energyKWh(), r.costRupees(),
                r.lastFinish() == 0 ? null : Clock.format(r.lastFinish()), r.notReady(), report.valid());
    }

    public List<PlanVersionRepository.Info> versions(String tenant, long shiftId) {
        return versions.history(find(tenant, shiftId).getId());
    }

    /** The employee's trip in their tenant's latest planned shift. */
    public MyTrip myTrip(String tenant, String tripId) {
        if (tripId == null) throw ApiException.notFound("Trip");
        Shift shift = shifts.findFirstByTenantIdAndCurrentVersionGreaterThanOrderByIdDesc(tenant, 0)
                .orElseThrow(() -> ApiException.notFound("Trip"));
        PlanState state = store.current(shift);
        for (Map.Entry<String, List<Activity>> cab : state.plan().timelines().entrySet()) {
            for (Activity a : cab.getValue()) {
                if (a instanceof TripLeg t && t.tripId().equals(tripId)) {
                    return new MyTrip(tripId, Clock.format(t.start()), "ELECTRIC_CAB", cab.getKey());
                }
            }
        }
        for (Map.Entry<String, List<Activity>> cab : state.history().entrySet()) {
            for (Activity a : cab.getValue()) {
                if (a instanceof TripLeg t && t.tripId().equals(tripId)) {
                    return new MyTrip(tripId, Clock.format(t.start()), "COMPLETED", cab.getKey());
                }
            }
        }
        boolean backup = java.util.stream.Stream.concat(state.pastBackup().stream(), state.plan().unassigned().stream())
                .anyMatch(u -> u.tripId().equals(tripId));
        if (backup) return new MyTrip(tripId, null, "BACKUP_CAB", null);
        throw ApiException.notFound("Trip");
    }

    public Shift find(String tenant, long shiftId) {
        return shifts.findByIdAndTenantId(shiftId, tenant).orElseThrow(() -> ApiException.notFound("Shift"));
    }

    private static ScenarioGenerator.Params params(Shift s) {
        return new ScenarioGenerator.Params(s.getCabs(), s.getHubs(), s.getChargersPerHub(), s.getTrips(), s.getSeed());
    }
}
