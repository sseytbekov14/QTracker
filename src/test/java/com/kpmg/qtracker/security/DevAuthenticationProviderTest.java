package com.kpmg.qtracker.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.kpmg.qtracker.repository.UserRepository;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAuthenticationProviderTest {

    @Mock
    private UserPrincipalService userPrincipalService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private DevAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        provider = new DevAuthenticationProvider(userPrincipalService, passwordEncoder, loginAttemptService, userRepository);
    }

    @Test
    void validBcryptPasswordAuthenticatesSuccessfully() {
        when(loginAttemptService.isLocked("soqm1@qtracker.local")).thenReturn(false);
        when(userPrincipalService.loadUserByEmail("soqm1@qtracker.local")).thenReturn(java.util.Optional.of(
                new UserPrincipalService.UserRecord(
                        7L,
                        "soqm1@qtracker.local",
                        passwordEncoder.encode("aaa"),
                        true,
                        Set.of("SOQM_TEAM")
                )));

        Authentication authentication = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("soqm1@qtracker.local", "aaa")
        );

        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(UserPrincipal.class);
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_SOQM_TEAM");
        verify(loginAttemptService).recordSuccess("soqm1@qtracker.local");
    }

    @Test
    void wrongPasswordFailsAuthentication() {
        when(loginAttemptService.isLocked("soqm1@qtracker.local")).thenReturn(false);
        when(userPrincipalService.loadUserByEmail("soqm1@qtracker.local")).thenReturn(java.util.Optional.of(
                new UserPrincipalService.UserRecord(
                        7L,
                        "soqm1@qtracker.local",
                        passwordEncoder.encode("aaa"),
                        true,
                        Set.of("SOQM_TEAM")
                )));

        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("soqm1@qtracker.local", "wrong")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        verify(loginAttemptService).recordFailure("soqm1@qtracker.local");
    }

    @Test
    void lockedUserCannotAuthenticate() {
        when(loginAttemptService.isLocked("soqm1@qtracker.local")).thenReturn(true);

        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("soqm1@qtracker.local", "aaa")))
                .isInstanceOf(LockedException.class)
                .hasMessage("Account is temporarily locked due to failed login attempts");

        verify(userPrincipalService, never()).loadUserByEmail("soqm1@qtracker.local");
    }

        @Test
        void disabledUserIsRejectedBeforePasswordCheck() {
                when(loginAttemptService.isLocked("soqm1@qtracker.local")).thenReturn(false);
                when(userPrincipalService.loadUserByEmail("soqm1@qtracker.local")).thenReturn(java.util.Optional.of(
                                new UserPrincipalService.UserRecord(
                                                7L,
                                                "soqm1@qtracker.local",
                                                passwordEncoder.encode("aaa"),
                                                false,
                                                Set.of("SOQM_TEAM")
                                )));

                assertThatThrownBy(() -> provider.authenticate(
                                UsernamePasswordAuthenticationToken.unauthenticated("soqm1@qtracker.local", "aaa")))
                                .isInstanceOf(DisabledException.class)
                                .hasMessage("Account is disabled");

                verify(loginAttemptService, never()).recordSuccess("soqm1@qtracker.local");
                verify(loginAttemptService, never()).recordFailure("soqm1@qtracker.local");
        }
}