package com.moveinsync.evfleet.app.api;

import com.moveinsync.evfleet.app.service.ShiftService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final ShiftService shifts;

    public MeController(ShiftService shifts) {
        this.shifts = shifts;
    }

    /** The trip comes from the token, so an employee cannot ask about anyone else's. */
    @GetMapping("/me/trip")
    ShiftService.MyTrip myTrip(@AuthenticationPrincipal Jwt jwt) {
        return shifts.myTrip(jwt.getClaimAsString("tenant"), jwt.getClaimAsString("trip"));
    }
}
