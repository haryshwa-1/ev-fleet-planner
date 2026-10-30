package com.moveinsync.evfleet.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.evfleet.app.domain.PlanVersion;
import com.moveinsync.evfleet.app.domain.Shift;
import com.moveinsync.evfleet.app.error.ApiException;
import com.moveinsync.evfleet.app.repository.PlanVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads and publishes plan versions. Parsed states are cached by (shift, version); versions are
 * immutable, so a cached entry can never be stale. Writers fill the cache only after commit, so
 * a rolled-back version is never served.
 */
@Component
public class PlanStore {

    private record Key(long shiftId, int version) {
    }

    private final PlanVersionRepository versions;
    private final ObjectMapper json;
    private final Cache<Key, PlanState> cache = Caffeine.newBuilder().maximumSize(64).recordStats().build();

    public PlanStore(PlanVersionRepository versions, ObjectMapper json) {
        this.versions = versions;
        this.json = json;
    }

    /** The shift's live plan, or a 409 if it has never been planned. */
    public PlanState current(Shift shift) {
        if (shift.getCurrentVersion() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_PLAN", "Run the planner for this shift first");
        }
        return cache.get(new Key(shift.getId(), shift.getCurrentVersion()), k -> parse(row(shift).getState()));
    }

    /** The row of the live version, with its summary columns. */
    public PlanVersion row(Shift shift) {
        return versions.findByShiftIdAndVersion(shift.getId(), shift.getCurrentVersion())
                .orElseThrow(() -> new IllegalStateException("shift " + shift.getId() + " lost its current version"));
    }

    /** Publishes the next version. The caller holds the shift's row lock and the transaction. */
    public int publish(Shift shift, PlanState state, String cause, int lowerBound, long millis) {
        int next = shift.getCurrentVersion() + 1;
        versions.save(new PlanVersion(shift.getId(), next, cause, write(state), state.backupTrips(),
                state.chargingCost(), lowerBound, millis));
        shift.setCurrentVersion(next);
        Key key = new Key(shift.getId(), next);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.put(key, state);
            }
        });
        return next;
    }

    private PlanState parse(String body) {
        try {
            return json.readValue(body, PlanState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored plan is unreadable", e);
        }
    }

    private String write(PlanState state) {
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan cannot be stored", e);
        }
    }
}
