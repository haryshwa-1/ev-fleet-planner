package com.moveinsync.evfleet.app.api;

import com.moveinsync.evfleet.app.domain.Shift;
import com.moveinsync.evfleet.app.service.ShiftService;
import com.moveinsync.evfleet.core.data.ScenarioGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    public record NewShift(@Min(1) @Max(5000) int cabs, @Min(1) @Max(100) int hubs,
                           @Min(1) @Max(100) int chargersPerHub, @Min(1) @Max(50000) int trips, long seed) {
    }

    public record ShiftCreated(long shiftId) {
    }

    private final ShiftService shifts;

    public AdminController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping("/admin/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    ShiftCreated create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewShift r) {
        Shift shift = shifts.create(jwt.getClaimAsString("tenant"),
                new ScenarioGenerator.Params(r.cabs(), r.hubs(), r.chargersPerHub(), r.trips(), r.seed()));
        return new ShiftCreated(shift.getId());
    }
}
