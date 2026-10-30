package com.moveinsync.evfleet.app.api;

import com.moveinsync.evfleet.app.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return new LoginResponse(auth.login(request.username(), request.password()));
    }
}
