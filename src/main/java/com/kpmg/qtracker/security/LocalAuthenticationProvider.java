package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class LocalAuthenticationProvider implements AuthenticationProvider {

    private final UserPrincipalService userPrincipalService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InMemoryUserDetailsManager inMemoryUserDetailsManager;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String rawPassword = authentication.getCredentials() == null
                ? ""
                : authentication.getCredentials().toString();

        Optional<UserPrincipal> principalOpt = userPrincipalService.loadByEmail(username);
        if (principalOpt.isPresent()) {
            UserPrincipal principal = principalOpt.get();
            String storedPassword = userRepository.findByMail(principal.getEmail())
                    .map(User::getPassword)
                    .orElse(null);
            if (storedPassword == null || !passwordEncoder.matches(rawPassword, storedPassword)) {
                log.debug("auth status=BAD_CREDENTIALS source=DB");
                throw new BadCredentialsException("Invalid credentials");
            }

            Set<GrantedAuthority> authorities = principal.getRoles().stream()
                    .map(this::toAuthority)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            log.debug("auth status=SUCCESS source=DB rolesCount={}", authorities.size());
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }

        UserDetails fallbackUser;
        try {
            fallbackUser = inMemoryUserDetailsManager.loadUserByUsername(username);
        } catch (UsernameNotFoundException ex) {
            log.debug("auth status=BAD_CREDENTIALS source=FALLBACK");
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(rawPassword, fallbackUser.getPassword())) {
            log.debug("auth status=BAD_CREDENTIALS source=FALLBACK");
            throw new BadCredentialsException("Invalid credentials");
        }

        Set<String> roles = fallbackUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(this::toRole)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        UserPrincipal fallbackPrincipal = new UserPrincipal(0L, fallbackUser.getUsername(), roles);
        log.debug("auth status=SUCCESS source=FALLBACK rolesCount={}", roles.size());
        return new UsernamePasswordAuthenticationToken(fallbackPrincipal, null, fallbackUser.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private SimpleGrantedAuthority toAuthority(String role) {
        String normalized = Objects.requireNonNullElse(role, "").trim();
        if (normalized.startsWith("ROLE_")) {
            return new SimpleGrantedAuthority(normalized);
        }
        return new SimpleGrantedAuthority("ROLE_" + normalized);
    }

    private String toRole(String authority) {
        String value = Objects.requireNonNullElse(authority, "").trim();
        return value.startsWith("ROLE_") ? value.substring("ROLE_".length()) : value;
    }
}
