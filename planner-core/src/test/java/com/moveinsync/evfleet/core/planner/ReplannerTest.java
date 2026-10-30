package com.moveinsync.evfleet.core.planner;

import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.ChargingSession;
import com.moveinsync.evfleet.core.plan.Plan;
import com.moveinsync.evfleet.core.plan.TripLeg;
import com.moveinsync.evfleet.core.validate.PlanValidator;
import com.moveinsync.evfleet.core.validate.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.moveinsync.evfleet.core.util.Clock.hm;
import static org.assertj.core.api.Assertions.assertThat;

/** Live re-planning on the full 300-cab shift: always valid, under 2 s, and changes little. */
class ReplannerTest {

    private static Scenario scenario;
    private static Plan plan;
    private final Replanner replanner = new Replanner();
    private final PlanValidator validator = new PlanValidator();

    @BeforeAll
    static void planTheShift() {
        scenario = new ScenarioGenerator().generate(ScenarioGenerator.Params.standard());
        plan = new GreedyPlanner().plan(scenario);
    }

    @ParameterizedTest
    @ValueSource(ints = {17 * 60 + 45, 19 * 60 + 30, 22 * 60 + 10, 24 * 60 + 40})
    void theRestOfAValidPlanIsValidAtAnyMoment(int minute) {
        Snapshot snap = Snapshot.of(scenario, plan, minute);
        assertThat(validator.validate(snap.scenario(), snap.plan()).violations()).isEmpty();
        int tripsDone = snap.history().values().stream().mapToInt(h -> (int) h.stream()
                .filter(a -> a instanceof TripLeg).count()).sum();
        assertThat(tripsDone + snap.plan().assignedTripCount()).isEqualTo(plan.assignedTripCount());
    }

    @Test
    void aCabFarBelowItsPlannedChargeIsRepairedQuickly() {
        int now = hm(19, 30);
        String cab = cabWithWorkAhead(now);
        Vehicle v = scenario.vehiclesById().get(cab);
        double reported = Math.max(safeReport(cab, now), v.batteryKWh() * 0.22);

        Replanner.Result r = replanner.onDrift(scenario, plan, new Replanner.DriftEvent(cab, now, reported));

        assertThat(validator.validate(r.snapshot().scenario(), r.before()).violationsByRule()).containsKey(Rule.STATE_MISMATCH);
        assertThat(r.report().violations()).isEmpty();
        assertThat(r.millis()).isLessThan(2_000);
        assertThat(r.diff().cabsChanged()).contains(cab).hasSizeLessThanOrEqualTo(r.neighbourhood() + 1);
        assertThat(r.after().unassigned().size()).isLessThanOrEqualTo(r.before().unassigned().size() + 2);
    }

    @Test
    void theDemoDriftChangesOnlyAHandfulOfCabs() {
        // CAB-007 expected 47% at 19:30 but reports 22%: it cannot do its 19:40 trip any more.
        Vehicle v = scenario.vehiclesById().get("CAB-007");
        Replanner.Result r = replanner.onDrift(scenario, plan,
                new Replanner.DriftEvent("CAB-007", hm(19, 30), v.batteryKWh() * 0.22));

        assertThat(r.report().violations()).isEmpty();
        assertThat(r.diff().movedToBackup()).isZero();
        assertThat(r.diff().cabsChanged()).hasSizeLessThanOrEqualTo(5);
        // cabs whose situation did not change keep their exact charging sessions
        assertThat(r.diff().sessionsAdded().size() + r.diff().sessionsRemoved().size()).isLessThanOrEqualTo(1);
    }

    @Test
    void aCabWithMoreChargeThanPlannedStaysValid() {
        int now = hm(20, 0);
        String cab = cabWithWorkAhead(now);
        Vehicle v = scenario.vehiclesById().get(cab);
        double planned = Snapshot.of(scenario, plan, now).scenario().vehiclesById().get(cab).initialSocKWh();
        double reported = Math.min(v.batteryKWh() * 0.95, planned + 5);

        Replanner.Result r = replanner.onDrift(scenario, plan, new Replanner.DriftEvent(cab, now, reported));
        assertThat(r.report().violations()).isEmpty();
        assertThat(r.millis()).isLessThan(2_000);
    }

    @Test
    void aFailedChargerIsNeverUsedAfterItFails() {
        int now = hm(19, 0);
        String charger = busiestChargerAfter(now);

        Replanner.Result r = replanner.onChargerFailure(scenario, plan, new Replanner.ChargerFailure(charger, now));

        assertThat(validator.validate(r.snapshot().scenario(), r.before()).violationsByRule())
                .containsKey(Rule.CHARGER_UNAVAILABLE);
        assertThat(r.report().violations()).isEmpty();
        assertThat(r.millis()).isLessThan(2_000);
        assertThat(r.after().allActivities().filter(a -> a instanceof ChargingSession c
                && c.chargerId().equals(charger) && c.end() > now)).isEmpty();
    }

    @Test
    void anEventThatAffectsNobodyChangesNothing() {
        // A charger with no bookings left fails: the cabs around it are re-planned but must come
        // back exactly as they were.
        int now = hm(19, 0);
        String idle = scenario.chargersById().keySet().stream().sorted()
                .filter(id -> plan.allActivities().noneMatch(a -> a instanceof ChargingSession c
                        && c.chargerId().equals(id) && c.end() > now))
                .findFirst().orElseThrow();

        Replanner.Result r = replanner.onChargerFailure(scenario, plan, new Replanner.ChargerFailure(idle, now));

        assertThat(r.report().violations()).isEmpty();
        assertThat(r.diff().cabsChanged()).isEmpty();
        assertThat(r.diff().tripChanges()).isEmpty();
    }

    @Test
    void aCabChargingWhenItsChargerFailsKeepsWhatItGained() {
        ChargingSession running = plan.allActivities().filter(a -> a instanceof ChargingSession c
                        && c.end() - c.start() >= 20 && c.start() >= hm(18, 0))
                .map(a -> (ChargingSession) a).findFirst().orElseThrow();
        int now = running.start() + 10;
        String cab = owner(running);

        Replanner.Result r = replanner.onChargerFailure(scenario, plan,
                new Replanner.ChargerFailure(running.chargerId(), now));

        assertThat(r.report().violations()).isEmpty();
        ChargingSession cut = (ChargingSession) r.after().timeline(cab).get(0);
        assertThat(cut.end()).isEqualTo(now);
        assertThat(cut.socEndKWh()).isGreaterThan(running.socStartKWh()).isLessThan(running.socEndKWh());
    }

    @Test
    void randomEventsAllGiveValidPlansWithinTwoSeconds() {
        Random rnd = new Random(11);
        List<String> cabs = new ArrayList<>(scenario.vehiclesById().keySet());
        List<String> chargers = new ArrayList<>(scenario.chargersById().keySet());
        cabs.sort(Comparator.naturalOrder());
        chargers.sort(Comparator.naturalOrder());
        long slowest = 0;
        for (int i = 0; i < 100; i++) {
            int now = hm(17, 30) + rnd.nextInt(7 * 60);
            Replanner.Result r;
            if (i % 2 == 0) {
                String cab = cabs.get(rnd.nextInt(cabs.size()));
                Vehicle v = scenario.vehiclesById().get(cab);
                double wanted = v.batteryKWh() * (0.15 + rnd.nextDouble() * 0.8);
                r = replanner.onDrift(scenario, plan,
                        new Replanner.DriftEvent(cab, now, Math.max(wanted, safeReport(cab, now))));
            } else {
                r = replanner.onChargerFailure(scenario, plan,
                        new Replanner.ChargerFailure(chargers.get(rnd.nextInt(chargers.size())), now));
            }
            assertThat(r.report().violations()).as("event %d at %d", i, now).isEmpty();
            slowest = Math.max(slowest, r.millis());
        }
        assertThat(slowest).isLessThan(2_000);
    }

    /** A cab with at least two trips still to start after {@code now}. */
    private static String cabWithWorkAhead(int now) {
        return plan.timelines().entrySet().stream()
                .filter(e -> e.getValue().stream().filter(a -> a instanceof TripLeg && a.start() >= now).count() >= 2)
                .map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
    }

    /**
     * Lowest charge that can be reported without the activity in progress itself dropping below the
     * floor; anything lower means the cab is already stranded, which no re-plan can undo.
     */
    private static double safeReport(String cab, int now) {
        Snapshot snap = Snapshot.of(scenario, plan, now);
        Vehicle v = snap.scenario().vehiclesById().get(cab);
        double floor = v.batteryKWh() * scenario.physics().socFloorFraction();
        Activity current = snap.inProgress(cab);
        if (current == null || current instanceof ChargingSession) return floor + 0.5;
        double f = (double) (now - current.start()) / Math.max(1, current.end() - current.start());
        double stillToUse = (1 - f) * (current.socStartKWh() - current.socEndKWh());
        return floor + stillToUse + 0.5;
    }

    private static String busiestChargerAfter(int now) {
        return plan.allActivities().filter(a -> a instanceof ChargingSession c && c.end() > now)
                .map(a -> ((ChargingSession) a).chargerId())
                .collect(java.util.stream.Collectors.groupingBy(id -> id, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
    }

    private static String owner(Activity a) {
        return plan.timelines().entrySet().stream().filter(e -> e.getValue().contains(a))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }
}
