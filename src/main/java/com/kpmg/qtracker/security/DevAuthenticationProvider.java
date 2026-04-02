package com.kpmg.qtracker.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class DevAuthenticationProvider implements AuthenticationProvider {

    private final UserPrincipalService userPrincipalService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken)) {
            return null;
        }

        String username = authentication.getName();
        String rawPassword = authentication.getCredentials() == null
                ? ""
                : authentication.getCredentials().toString();

        if (loginAttemptService.isLocked(username)) {
            log.warn("auth status=LOCKED username={}", username);
            throw new LockedException("Account is temporarily locked due to failed login attempts");
        }

        UserPrincipalService.UserRecord userRecord = userPrincipalService.loadUserByEmail(username)
                .orElse(null);
        if (userRecord != null) {
            if (!passwordEncoder.matches(rawPassword, userRecord.password())) {
                loginAttemptService.recordFailure(username);
                log.warn("auth status=BAD_CREDENTIALS username={}", username);
                throw new BadCredentialsException("Invalid credentials");
            }
            loginAttemptService.recordSuccess(username);
            UserPrincipal principal = new UserPrincipal(userRecord.id(), userRecord.email(), userRecord.roles());
            return new UsernamePasswordAuthenticationToken(principal, null, toAuthorities(userRecord.roles()));
        }

        loginAttemptService.recordFailure(username);
        log.warn("auth status=BAD_CREDENTIALS username={}", username);
        throw new BadCredentialsException("Invalid credentials");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private Set<GrantedAuthority> toAuthorities(Set<String> roles) {
        return roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}