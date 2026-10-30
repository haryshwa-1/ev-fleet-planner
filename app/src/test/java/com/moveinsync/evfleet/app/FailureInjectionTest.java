package com.moveinsync.evfleet.app;

import com.moveinsync.evfleet.app.repository.ProcessedEventRepository;
import com.moveinsync.evfleet.app.service.EventService;
import com.moveinsync.evfleet.app.service.ShiftService;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import com.moveinsync.evfleet.core.planner.Replanner;
import com.moveinsync.evfleet.core.validate.ValidationReport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/** Breaks one step of an event on purpose, then checks nothing half-done was left behind. */
@SpringBootTest
@Import(TestDatabase.class)
class FailureInjectionTest {

    @Autowired
    EventService events;
    @Autowired
    ShiftService shifts;
    @Autowired
    ProcessedEventRepository processed;
    @Autowired
    MeterRegistry registry;

    long shift;

    @BeforeEach
    void plannedShift() {
        shift = shifts.create("acme", new ScenarioGenerator.Params(30, 2, 2, 100, 5L)).getId();
        shifts.plan("acme", shift);
    }

    @AfterEach
    void restore() {
        target("processed", processed);
        target("replanner", new Replanner());
    }

    private void target(String field, Object value) {
        Object service = AopTestUtils.getTargetObject(events);
        ReflectionTestUtils.setField(service, field, value);
    }

    private double applied() {
        return registry.counter("events", "type", "soc", "outcome", "applied").count();
    }

    private int version() {
        return shifts.summary("acme", shift).version();
    }

    @Test
    void aCrashBeforeCommitLeavesNothingAndTheSameKeyCanRetry() {
        double appliedBefore = applied();
        ProcessedEventRepository broken = Mockito.mock(ProcessedEventRepository.class);
        Mockito.when(broken.findByShiftIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        Mockito.when(broken.save(any())).thenThrow(new IllegalStateException("database lost mid-event"));
        target("processed", broken);

        assertThatThrownBy(() -> events.drift("acme", shift, "CAB-001", 40, "19:30", 1, "crash"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(version()).isEqualTo(1);
        assertThat(applied()).isEqualTo(appliedBefore);

        target("processed", processed);
        EventService.EventResult retry = events.drift("acme", shift, "CAB-001", 40, "19:30", 1, "crash");
        assertThat(retry.duplicate()).isFalse();
        assertThat(retry.planVersion()).isEqualTo(2);
    }

    @Test
    void aRepairTheValidatorRefusesKeepsThePreviousPlanLive() {
        Replanner real = new Replanner();
        Replanner bad = Mockito.mock(Replanner.class);
        Mockito.when(bad.onDrift(any(), any(), any())).thenAnswer(call -> {
            Replanner.Result r = real.onDrift(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            ValidationReport refused = new ValidationReport(false, 1, List.of(), Map.of(), 0, r.report().stats());
            return new Replanner.Result(r.snapshot(), r.before(), r.after(), r.diff(), refused, r.neighbourhood(),
                    r.millis(), r.strandedCab());
        });
        target("replanner", bad);

        EventService.EventResult result = events.drift("acme", shift, "CAB-001", 40, "19:30", 1, "refused");

        assertThat(result.outcome()).isEqualTo(EventService.Outcome.REJECTED);
        assertThat(version()).isEqualTo(1);
    }
}
