package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;


import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class DevAuthenticationProvider implements AuthenticationProvider {

    private final UserPrincipalService userPrincipalService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final UserRepository userRepository;
    private final LoginNameResolver loginNames;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof UsernamePasswordAuthenticationToken)) {
            return null;
        }

        // A username (when allowed) or an e-mail, in any case and with stray spaces: the address to look up
        String username = address(authentication.getName());
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
            if (!userRecord.enabled()) {
                log.warn("auth status=DISABLED username={}", username);
                throw new DisabledException("Account is disabled");
            }
            if (!passwordEncoder.matches(rawPassword, userRecord.password())) {
                loginAttemptService.recordFailure(username);
                log.warn("auth status=BAD_CREDENTIALS username={}", username);
                throw new BadCredentialsException("Invalid credentials");
            }
            loginAttemptService.recordSuccess(username);
            userRepository.findByMail(userRecord.email()).ifPresent(user -> {
                user.setLastLoginAt(LocalDateTime.now());
                userRepository.save(user);
            });
            UserPrincipal principal = new UserPrincipal(userRecord.id(), userRecord.email(), userRecord.roles());
            return new UsernamePasswordAuthenticationToken(principal, null, toAuthorities(userRecord.roles()));
        }

        loginAttemptService.recordFailure(username);
        log.warn("auth status=BAD_CREDENTIALS username={}", username);
        throw new BadCredentialsException("Invalid credentials");
    }

    /**
     * The address a login stands for: an e-mail as typed; a username is first <username>@auth.username-domain
     * (the imported people), else the only account whose address has this part before "@" on any domain.
     * Two or more such accounts: no guess, the domain address stays (and is refused).
     */
    private String address(String typed) {
        String mail = loginNames.toMail(typed);
        Optional<String> username = loginNames.username(typed);
        if (username.isEmpty() || userRepository.existsByMail(mail)) {
            return mail;
        }
        List<User> sameUsername = userRepository.findByMailLocalPart(username.get());
        return sameUsername.size() == 1 ? sameUsername.get(0).getMail() : mail;
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