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
        provider = new DevAuthenticationProvider(userPrincipalService, passwordEncoder, loginAttemptService, userRepository,
                new LoginNameResolver(true, "qtracker.local"));
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
                        Set.of("SOQM")
                )));

        Authentication authentication = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("soqm1@qtracker.local", "aaa")
        );

        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(UserPrincipal.class);
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_SOQM");
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

    @Test
    void username_signsInAsItsAddress_lockoutCountedOnTheAddress() {
        when(loginAttemptService.isLocked("soqm1@qtracker.local")).thenReturn(false);
        when(userPrincipalService.loadUserByEmail("soqm1@qtracker.local")).thenReturn(java.util.Optional.of(
                new UserPrincipalService.UserRecord(7L, "soqm1@qtracker.local", passwordEncoder.encode("aaa"), true,
                        Set.of("SOQM"))));

        Authentication authentication = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(" SoQM1 ", "aaa"));

        assertThat(((UserPrincipal) authentication.getPrincipal()).getEmail()).isEqualTo("soqm1@qtracker.local");
        verify(loginAttemptService).recordSuccess("soqm1@qtracker.local");
    }

    @Test
    void username_withoutAnAccountOnTheDomain_isTheOnlyAccountWithThatNameOnAnotherDomain() {
        com.kpmg.qtracker.entity.User firm = new com.kpmg.qtracker.entity.User();
        firm.setMail("jdoe@firm.test");
        when(userRepository.existsByMail("jdoe@qtracker.local")).thenReturn(false);
        when(userRepository.findByMailLocalPart("jdoe")).thenReturn(java.util.List.of(firm));
        when(userPrincipalService.loadUserByEmail("jdoe@firm.test")).thenReturn(java.util.Optional.of(
                new UserPrincipalService.UserRecord(9L, "jdoe@firm.test", passwordEncoder.encode("aaa"), true,
                        Set.of("PARTICIPANT"))));

        Authentication authentication = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("JDoe", "aaa"));

        assertThat(((UserPrincipal) authentication.getPrincipal()).getEmail()).isEqualTo("jdoe@firm.test");
        verify(loginAttemptService).recordSuccess("jdoe@firm.test");
    }

    @Test
    void username_onTwoOtherDomains_isNotGuessed() {
        com.kpmg.qtracker.entity.User one = new com.kpmg.qtracker.entity.User();
        one.setMail("jdoe@firm.test");
        com.kpmg.qtracker.entity.User two = new com.kpmg.qtracker.entity.User();
        two.setMail("jdoe@other.test");
        when(userRepository.existsByMail("jdoe@qtracker.local")).thenReturn(false);
        when(userRepository.findByMailLocalPart("jdoe")).thenReturn(java.util.List.of(one, two));

        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("jdoe", "aaa")))
                .isInstanceOf(BadCredentialsException.class);
        verify(userPrincipalService).loadUserByEmail("jdoe@qtracker.local");
    }

    @Test
    void email_isNeverLookedUpByThePartBeforeTheAt() {
        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("jdoe@firm.test", "aaa")))
                .isInstanceOf(BadCredentialsException.class);

        verify(userRepository, never()).findByMailLocalPart(org.mockito.ArgumentMatchers.anyString());
        verify(userPrincipalService).loadUserByEmail("jdoe@firm.test");
    }
}
