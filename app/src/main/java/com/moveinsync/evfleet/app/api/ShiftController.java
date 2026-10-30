package com.moveinsync.evfleet.app.api;

import com.moveinsync.evfleet.app.repository.PlanVersionRepository;
import com.moveinsync.evfleet.app.service.EventService;
import com.moveinsync.evfleet.app.service.ShiftService;
import com.moveinsync.evfleet.core.plan.Activity;
import com.moveinsync.evfleet.core.plan.UnassignedTrip;
import com.moveinsync.evfleet.core.validate.ValidationReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The tenant always comes from the signed token, never from anything the client sends. */
@RestController
public class ShiftController {

    public record SocEvent(@NotBlank String cabId, @DecimalMin("0") @DecimalMax("100") double socPercent,
                           @NotBlank String observedAt, @Min(0) long seq,
                           @NotBlank @Size(max = 100) String idempotencyKey) {
    }

    public record ChargerFailure(@NotBlank String failedAt, @NotBlank @Size(max = 100) String idempotencyKey) {
    }

    private final ShiftService shifts;
    private final EventService events;

    public ShiftController(ShiftService shifts, EventService events) {
        this.shifts = shifts;
        this.events = events;
    }

    @PostMapping("/shifts/{id}/plan")
    ShiftService.Summary plan(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return shifts.plan(tenant(jwt), id);
    }

    @GetMapping("/shifts/{id}/plan/summary")
    ShiftService.Summary summary(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return shifts.summary(tenant(jwt), id);
    }

    @GetMapping("/shifts/{id}/plan/unassigned")
    List<UnassignedTrip> unassigned(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return shifts.unassigned(tenant(jwt), id);
    }

    @GetMapping("/shifts/{id}/plan/validate")
    ValidationReport validate(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return shifts.validate(tenant(jwt), id);
    }

    @GetMapping("/shifts/{id}/plan/overnight")
    ShiftService.Overnight overnight(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return shifts.overnight(tenant(jwt), id);
    }

    @GetMapping("/shifts/{id}/plan/versions")
    List<PlanVersionRepository.Info> versions(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return shifts.versions(tenant(jwt), id);
    }

    @GetMapping("/shifts/{id}/cabs/{cabId}/timeline")
    List<Activity> timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable String cabId) {
        return shifts.timeline(tenant(jwt), id, cabId);
    }

    @PostMapping("/shifts/{id}/events/soc")
    ResponseEntity<EventService.EventResult> soc(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                 @Valid @RequestBody SocEvent e) {
        return respond(events.drift(tenant(jwt), id, e.cabId(), e.socPercent(), e.observedAt(), e.seq(), e.idempotencyKey()));
    }

    @PostMapping("/shifts/{id}/chargers/{chargerId}/failure")
    ResponseEntity<EventService.EventResult> chargerFailure(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                            @PathVariable String chargerId,
                                                            @Valid @RequestBody ChargerFailure e) {
        return respond(events.chargerFailure(tenant(jwt), id, chargerId, e.failedAt(), e.idempotencyKey()));
    }

    private static ResponseEntity<EventService.EventResult> respond(EventService.EventResult result) {
        HttpStatus status = result.outcome() == EventService.Outcome.REJECTED
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    private static String tenant(Jwt jwt) {
        return jwt.getClaimAsString("tenant");
    }
}
