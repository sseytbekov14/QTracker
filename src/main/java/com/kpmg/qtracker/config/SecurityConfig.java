package com.kpmg.qtracker.config;

import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.security.DevAuthenticationProvider;
import com.kpmg.qtracker.security.LoginAttemptService;
import com.kpmg.qtracker.security.RateLimitingFilter;
import com.kpmg.qtracker.security.UserPrincipal;
import com.kpmg.qtracker.security.UserPrincipalService;
import com.kpmg.qtracker.security.UserEnabledGuardFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final UserEnabledGuardFilter userEnabledGuardFilter;

    public SecurityConfig(CorrelationIdFilter correlationIdFilter,
                          RateLimitingFilter rateLimitingFilter,
                          UserEnabledGuardFilter userEnabledGuardFilter) {
        this.correlationIdFilter = correlationIdFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.userEnabledGuardFilter = userEnabledGuardFilter;
    }

    @Bean
    @Profile("ssodev")
    public SecurityFilterChain securityFilterChainSso(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/api/**", "/notifications/mark-all-read")
            )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/images/**", "/css/**", "/js/**", "/webjars/**", "/favicon.ico", "/favicon.svg").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                    .anyRequest().authenticated()
                )
                .headers(headers -> headers
                    .contentTypeOptions(Customizer.withDefaults())
                    .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
                    .frameOptions(frame -> frame.sameOrigin())
                    .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                    .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .permissionsPolicy(permissions -> permissions
                        .policy("geolocation=(), microphone=(), camera=()"))
                )
                .headers(headers -> headers
                        .addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline' https:; style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; font-src 'self' data: https:; connect-src 'self' https: ws: wss:; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'")))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .oauth2Login(Customizer.withDefaults())
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(userEnabledGuardFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Profile({"dev", "stage"})
    public SecurityFilterChain securityFilterChainDev(HttpSecurity http,
                                                      AuthenticationProvider devAuthenticationProvider,
                                                      UserRepository userRepository) throws Exception {
        http
                .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringRequestMatchers("/api/**", "/notifications/mark-all-read")
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/images/**", "/css/**", "/js/**", "/webjars/**", "/favicon.ico", "/favicon.svg").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                    .anyRequest().authenticated()
                )
                .headers(headers -> headers
                    .contentTypeOptions(Customizer.withDefaults())
                    .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
                    .frameOptions(frame -> frame.sameOrigin())
                    .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                    .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .permissionsPolicy(permissions -> permissions
                        .policy("geolocation=(), microphone=(), camera=()"))
                )
                .headers(headers -> headers
                        .addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline' https:; style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; font-src 'self' data: https:; connect-src 'self' https: ws: wss:; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'")))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .authenticationProvider(devAuthenticationProvider)
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            if (authentication.getPrincipal() instanceof UserPrincipal principal) {
                                userRepository.findById(principal.getId()).ifPresent(sessionUser -> {
                                    request.getSession(true).setAttribute("currentUser", sessionUser);
                                    request.getSession().setAttribute("userRole", sessionUser.getRole());
                                });
                            }
                            response.sendRedirect("/");
                        })
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof DisabledException) {
                                request.getSession(true).setAttribute("SPRING_SECURITY_LAST_EXCEPTION", exception);
                                response.sendRedirect("/login?error");
                                return;
                            }
                            request.getSession(true).setAttribute("SPRING_SECURITY_LAST_EXCEPTION", exception);
                            response.sendRedirect("/login?error");
                        }))
                    .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(userEnabledGuardFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Profile({"dev", "stage"})
    public AuthenticationProvider devAuthenticationProvider(UserPrincipalService userPrincipalService,
                                                            PasswordEncoder passwordEncoder,
                                                            LoginAttemptService loginAttemptService,
                                                            UserRepository userRepository) {
        return new DevAuthenticationProvider(userPrincipalService, passwordEncoder, loginAttemptService, userRepository);
    }
}