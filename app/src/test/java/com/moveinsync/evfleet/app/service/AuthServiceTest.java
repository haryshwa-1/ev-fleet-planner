package com.moveinsync.evfleet.app.service;

import com.moveinsync.evfleet.app.error.ApiException;
import com.moveinsync.evfleet.app.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void anUnknownUserStillCostsAPasswordCheck() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(users.findByUsername("nobody@acme")).thenReturn(Optional.empty());
        when(passwords.encode(anyString())).thenReturn("dummy-hash");

        AuthService auth = new AuthService(users, passwords, null, 60);

        assertThatThrownBy(() -> auth.login("nobody@acme", "guess")).isInstanceOf(ApiException.class);
        verify(passwords).matches("guess", "dummy-hash");
    }
}
