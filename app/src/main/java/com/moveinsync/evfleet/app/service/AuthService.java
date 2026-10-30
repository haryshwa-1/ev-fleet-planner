package com.moveinsync.evfleet.app.service;

import com.moveinsync.evfleet.app.domain.AppUser;
import com.moveinsync.evfleet.app.error.ApiException;
import com.moveinsync.evfleet.app.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Duration ttl;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
                       @Value("${app.jwt.ttl-minutes}") long ttlMinutes) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String login(String username, String password) {
        // Same answer for an unknown user and a wrong password, so accounts cannot be discovered.
        AppUser user = users.findByUsername(username)
                .filter(u -> passwords.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                        "Wrong username or password"));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("tenant", user.getTenantId())
                .claim("role", user.getRole().name());
        if (user.getTripId() != null) claims.claim("trip", user.getTripId());
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
