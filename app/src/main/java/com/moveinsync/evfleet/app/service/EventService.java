package com.moveinsync.evfleet.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.app.domain.CabEventSeq;
import com.moveinsync.evfleet.app.domain.ProcessedEvent;
import com.moveinsync.evfleet.app.domain.Shift;
import com.moveinsync.evfleet.app.error.ApiException;
import com.moveinsync.evfleet.app.monitoring.FleetMetrics;
import com.moveinsync.evfleet.app.repository.CabEventSeqRepository;
import com.moveinsync.evfleet.app.repository.ProcessedEventRepository;
import com.moveinsync.evfleet.app.repository.ShiftRepository;
import com.moveinsync.evfleet.core.model.Charger;
import com.moveinsync.evfleet.core.model.Scenario;
import com.moveinsync.evfleet.core.model.Vehicle;
import com.moveinsync.evfleet.core.planner.PlanDiff;
import com.moveinsync.evfleet.core.planner.Replanner;
import com.moveinsync.evfleet.core.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Applies live events. Each runs in one transaction holding the shift's row lock, so events on a
 * shift are strictly one at a time, and a crash leaves either the whole change or none of it:
 * the new version, the sequence number and the stored response commit together.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    public enum Outcome {
        APPLIED,
        /** The validator refused the repair, so the previous version stays live. */
        REJECTED
    }

    /**
     * @param duplicate true when this is the stored answer to an earlier event with the same key
     */
    public record EventResult(Outcome outcome, boolean duplicate, int planVersion, String effectiveAt, int cabsChanged,
                              long tripsReassigned, long tripsToBackup, long tripsRescued, int sessionsAdded,
                              int sessionsRemoved, List<PlanDiff.TripChange> tripChanges, long replanMillis,
                              String validator) {
        EventResult asDuplicate() {
            return new EventResult(outcome, true, planVersion, effectiveAt, cabsChanged, tripsReassigned, tripsToBackup,
                    tripsRescued, sessionsAdded, sessionsRemoved, tripChanges, replanMillis, validator);
        }
    }

    private final ShiftRepository shifts;
    private final ProcessedEventRepository processed;
    private final CabEventSeqRepository sequences;
    private final PlanStore store;
    private final FleetMetrics metrics;
    private final ObjectMapper json;
    private final Replanner replanner = new Replanner();

    public EventService(ShiftRepository shifts, ProcessedEventRepository processed, CabEventSeqRepository sequences,
                        PlanStore store, FleetMetrics metrics, ObjectMapper json) {
        this.shifts = shifts;
        this.processed = processed;
        this.sequences = sequences;
        this.store = store;
        this.metrics = metrics;
        this.json = json;
    }

    @Transactional
    public EventResult drift(String tenant, long shiftId, String cabId, double socPercent, String observedAt, long seq,
                             String idempotencyKey) {
        int observed = parseTime(observedAt);
        Shift shift = lock(tenant, shiftId);
        Optional<EventResult> seen = previous(shift, idempotencyKey, "soc");
        if (seen.isPresent()) return seen.get();

        PlanState state = store.current(shift);
        Vehicle cab = state.vehicles().stream().filter(v -> v.id().equals(cabId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Cab"));
        checkOrder(shift, cabId, seq);

        double socKWh = cab.batteryKWh() * socPercent / 100;
        return apply(shift, state, idempotencyKey, "soc", observed, String.format("DRIFT %s %.0f%% at %s", cabId, socPercent, observedAt),
                (scenario, minute) -> replanner.onDrift(scenario, state.plan(), new Replanner.DriftEvent(cabId, minute, socKWh)));
    }

    @Transactional
    public EventResult chargerFailure(String tenant, long shiftId, String chargerId, String failedAt,
                                      String idempotencyKey) {
        int observed = parseTime(failedAt);
        Shift shift = lock(tenant, shiftId);
        Optional<EventResult> seen = previous(shift, idempotencyKey, "charger_failure");
        if (seen.isPresent()) return seen.get();

        PlanState state = store.current(shift);
        Charger charger = state.scenario().chargersById().get(chargerId);
        if (charger == null) throw ApiException.notFound("Charger");
        if (charger.failedFrom() != Charger.WORKING) {
            metrics.event("charger_failure", "already_failed");
            throw new ApiException(HttpStatus.CONFLICT, "CHARGER_ALREADY_FAILED", "This charger is already out of service");
        }
        return apply(shift, state, idempotencyKey, "charger_failure", observed, "CHARGER_FAILURE " + chargerId + " at " + failedAt,
                (scenario, minute) -> replanner.onChargerFailure(scenario, state.plan(), new Replanner.ChargerFailure(chargerId, minute)));
    }

    /**
     * The shared path: re-plan from the plan's clock, validate, publish or reject, store the answer.
     * The plan's clock never goes backwards: a late event takes effect at the current clock, because
     * activities before it have already been committed to history.
     */
    private EventResult apply(Shift shift, PlanState state, String key, String type, int observed, String cause,
                              BiFunction<Scenario, Integer, Replanner.Result> replan) {
        int minute = Math.max(observed, state.asOfMinute());
        metrics.eventLag(minute - observed);
        Replanner.Result r = replan.apply(state.scenario(), minute);
        metrics.replanLatency().record(java.time.Duration.ofMillis(r.millis()));

        EventResult result;
        if (r.report().valid()) {
            PlanState next = state.after(r);
            int lowerBound = store.row(shift).getLowerBound();
            int version = store.publish(shift, next, cause, lowerBound, r.millis());
            metrics.event(type, "applied");
            metrics.unassigned(next.backupTrips());
            result = result(Outcome.APPLIED, version, minute, r);
            log.info("shift {} v{}: {} -> {} in {} ms", shift.getId(), version, cause, r.diff().summary(), r.millis());
        } else {
            metrics.event(type, "rejected");
            metrics.validatorFailure();
            result = result(Outcome.REJECTED, shift.getCurrentVersion(), minute, r);
            log.warn("shift {}: repair for {} rejected: {}", shift.getId(), cause, r.report().summary());
        }
        processed.save(new ProcessedEvent(shift.getId(), key, write(result)));
        return result;
    }

    private static EventResult result(Outcome outcome, int version, int minute, Replanner.Result r) {
        PlanDiff d = r.diff();
        return new EventResult(outcome, false, version, Clock.format(minute), d.cabsChanged().size(), d.reassigned(),
                d.movedToBackup(), d.rescuedFromBackup(), d.sessionsAdded().size(), d.sessionsRemoved().size(),
                d.tripChanges(), r.millis(), r.report().summary());
    }

    private Shift lock(String tenant, long shiftId) {
        return shifts.lockByIdAndTenantId(shiftId, tenant).orElseThrow(() -> ApiException.notFound("Shift"));
    }

    private Optional<EventResult> previous(Shift shift, String key, String type) {
        Optional<EventResult> seen = processed.findByShiftIdAndIdempotencyKey(shift.getId(), key)
                .map(p -> read(p.getResponse()).asDuplicate());
        seen.ifPresent(x -> metrics.event(type, "duplicate"));
        return seen;
    }

    /** Refuses anything not newer than the last event from this cab, then records it as seen. */
    private void checkOrder(Shift shift, String cabId, long seq) {
        CabEventSeq.Key id = new CabEventSeq.Key(shift.getId(), cabId);
        Optional<CabEventSeq> last = sequences.findById(id);
        if (last.isPresent() && seq <= last.get().getLastSeq()) {
            metrics.event("soc", "out_of_order");
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_OUT_OF_ORDER",
                    "A newer event from this cab has already been applied");
        }
        if (last.isPresent()) {
            last.get().setLastSeq(seq);
        } else {
            sequences.save(new CabEventSeq(shift.getId(), cabId, seq));
        }
    }

    private static int parseTime(String text) {
        try {
            return Clock.parse(text);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TIME", "Times look like 19:30, or 01:10+1 for the next day");
        }
    }

    private String write(EventResult r) {
        try {
            return json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event result cannot be stored", e);
        }
    }

    private EventResult read(String body) {
        try {
            return json.readValue(body, EventResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored event result is unreadable", e);
        }
    }
}
