package com.moveinsync.evfleet.app.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** The fleet's business metrics, exposed at /actuator/prometheus. */
@Component
public class FleetMetrics {

    private final MeterRegistry registry;
    private final Timer replanLatency;
    private final Timer planLatency;
    private final DistributionSummary eventLagMinutes;
    private final Counter validatorFailures;
    private final Counter plannerTimeouts;
    private final Counter eventRetries;
    private final AtomicInteger unassignedTrips = new AtomicInteger();
    private final AtomicInteger eventsInFlight = new AtomicInteger();

    public FleetMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.replanLatency = Timer.builder("replan.latency").description("Live re-plan time").register(registry);
        this.planLatency = Timer.builder("plan.latency").description("Full planning time").register(registry);
        this.eventLagMinutes = DistributionSummary.builder("event.lag.minutes")
                .description("How far an event's time was behind the plan's clock").register(registry);
        this.validatorFailures = registry.counter("validator.failures");
        this.plannerTimeouts = registry.counter("planner.timeouts");
        this.eventRetries = registry.counter("events.retries");
        Gauge.builder("events.in.flight", eventsInFlight, AtomicInteger::get)
                .description("Events being applied or queued behind a shift's lock").register(registry);
        Gauge.builder("unassigned.trips", unassignedTrips, AtomicInteger::get)
                .description("Backup trips in the plan most recently published").register(registry);
    }

    /** Counts an event by outcome: applied, duplicate, out_of_order or rejected. */
    public void event(String type, String outcome) {
        registry.counter("events", "type", type, "outcome", outcome).increment();
    }

    public Timer replanLatency() {
        return replanLatency;
    }

    public Timer planLatency() {
        return planLatency;
    }

    public void eventLag(int minutes) {
        eventLagMinutes.record(minutes);
    }

    public void validatorFailure() {
        validatorFailures.increment();
    }

    public void plannerTimeout() {
        plannerTimeouts.increment();
    }

    /** Runs an event while counting it in {@code events.in.flight}, the queue behind the shift locks. */
    public <T> T inFlight(Supplier<T> event) {
        eventsInFlight.incrementAndGet();
        try {
            return event.get();
        } finally {
            eventsInFlight.decrementAndGet();
        }
    }

    public void eventRetried() {
        eventRetries.increment();
    }

    public void cabStranded() {
        registry.counter("cabs.stranded").increment();
    }

    public void unassigned(int trips) {
        unassignedTrips.set(trips);
    }
}
