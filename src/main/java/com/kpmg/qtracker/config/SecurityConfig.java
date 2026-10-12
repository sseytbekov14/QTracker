package com.kpmg.qtracker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.controller.AuthController;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.security.AuthMode;
import com.kpmg.qtracker.security.CsrfAccessDeniedHandler;
import com.kpmg.qtracker.security.DevAuthenticationProvider;
import com.kpmg.qtracker.security.LoginNameResolver;
import com.kpmg.qtracker.security.LoginAttemptService;
import com.kpmg.qtracker.security.RateLimitingFilter;
import com.kpmg.qtracker.security.SignInEntryPoint;
import com.kpmg.qtracker.security.SsoSignIn;
import com.kpmg.qtracker.security.UserPrincipal;
import com.kpmg.qtracker.security.UserPrincipalService;
import com.kpmg.qtracker.security.UserEnabledGuardFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The filter chains of the web application. Not created for a one-off command (QtrackerApplication.runCommand),
 * which runs without a web server and so without HttpSecurity, also in the dev profile.
 *
 * <p>dev and stage share a chain, prod has its own; both are built by {@link #chain} with the same protections
 * (CSRF, headers, the disabled-user guard, the rate limit; the Admin Panel and read-only checks live in the
 * controllers and ReadOnlyAccessInterceptor and apply in every profile). How people sign in is
 * {@link AuthMode}: the password form, or Microsoft Entra ID over SAML (SamlConfig, SsoSignIn) - never both.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final UserEnabledGuardFilter userEnabledGuardFilter;
    private final CsrfAccessDeniedHandler csrfAccessDeniedHandler;
    private final ObjectMapper objectMapper;

    public SecurityConfig(CorrelationIdFilter correlationIdFilter,
                          RateLimitingFilter rateLimitingFilter,
                          UserEnabledGuardFilter userEnabledGuardFilter,
                          CsrfAccessDeniedHandler csrfAccessDeniedHandler,
                          ObjectMapper objectMapper) {
        this.correlationIdFilter = correlationIdFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.userEnabledGuardFilter = userEnabledGuardFilter;
        this.csrfAccessDeniedHandler = csrfAccessDeniedHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * The same CSRF rules for every chain, with no exceptions: the token lives in a cookie (so it outlives the
     * session and an expired session still ends in the login redirect), pages send it as a header
     * (fragments/csrf.html + js/csrf.js) or as the hidden field Thymeleaf adds to th:action forms.
     */
    private void configureCsrf(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(csrfAccessDeniedHandler));
    }

    @Bean
    @Profile({"dev", "stage"})
    public SecurityFilterChain securityFilterChainDev(HttpSecurity http, AuthMode authMode,
                                                      ObjectProvider<AuthenticationProvider> passwordProvider,
                                                      ObjectProvider<SsoSignIn> ssoSignIn,
                                                      UserRepository userRepository) throws Exception {
        return chain(http, authMode, passwordProvider, ssoSignIn, userRepository);
    }

    /** prod: its own chain, the same protections; SSO unless AUTH_PASSWORD_ENABLED brings the form back. */
    @Bean
    @Profile("prod")
    public SecurityFilterChain securityFilterChainProd(HttpSecurity http, AuthMode authMode,
                                                       ObjectProvider<AuthenticationProvider> passwordProvider,
                                                       ObjectProvider<SsoSignIn> ssoSignIn,
                                                       UserRepository userRepository) throws Exception {
        return chain(http, authMode, passwordProvider, ssoSignIn, userRepository);
    }

    private SecurityFilterChain chain(HttpSecurity http, AuthMode authMode,
                                      ObjectProvider<AuthenticationProvider> passwordProvider,
                                      ObjectProvider<SsoSignIn> ssoSignIn,
                                      UserRepository userRepository) throws Exception {
        configureCsrf(http);
        RequestCache requestCache = requestCache();
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/login").permitAll()
                        .requestMatchers(SsoSignIn.ACCESS_NOT_GRANTED_PATH, SsoSignIn.SIGN_IN_FAILED_PATH,
                                AuthController.SIGNED_OUT_PATH).permitAll()
                        .requestMatchers("/images/**", "/css/**", "/js/**", "/webjars/**", "/favicon.ico", "/favicon.svg").permitAll()
                        .requestMatchers("/error").permitAll()
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
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'")))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .requestCache(cache -> cache.requestCache(requestCache))
                // Spring's POST /logout as GET /logout (AuthController): only with auth.logout.enabled
                .logout(logout -> {
                    if (authMode.logout()) {
                        logout.logoutSuccessUrl(authMode.sso() ? AuthController.SIGNED_OUT_PATH : "/login?logout");
                    } else {
                        logout.disable();
                    }
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new SignInEntryPoint(
                                authMode.sso() ? SamlConfig.SIGN_ON_PATH : "/login", objectMapper)));

        if (authMode.sso()) {
            SsoSignIn sso = ssoSignIn.getObject();
            http
                    .saml2Login(saml -> saml
                            // Our own page instead of Spring's list of identity providers
                            .loginPage(SamlConfig.SIGN_ON_PATH)
                            .authenticationManager(new ProviderManager(sso.provider()))
                            .successHandler(sso.successHandler(requestCache))
                            .failureHandler(sso.failureHandler()))
                    // The service provider metadata for the Entra application: /saml2/metadata/entra
                    .saml2Metadata(Customizer.withDefaults());
        } else {
            http
                    .authenticationProvider(passwordProvider.getObject())
                    .formLogin(form -> form
                            .loginPage("/login")
                            .loginProcessingUrl("/login")
                            .successHandler((request, response, authentication) -> {
                                if (authentication.getPrincipal() instanceof UserPrincipal principal) {
                                    userRepository.findById(principal.getId()).ifPresent(sessionUser -> {
                                        request.getSession(true).setAttribute("currentUser", sessionUser);
                                    });
                                }
                                response.sendRedirect("/");
                            })
                            .failureHandler((request, response, exception) -> {
                                request.getSession(true).setAttribute("SPRING_SECURITY_LAST_EXCEPTION", exception);
                                response.sendRedirect("/login?error");
                            }));
        }

        http
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(userEnabledGuardFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The page asked for without a sign-in, opened after it. Pages only: an /api/** call (a script's request)
     * and the sign-in pages themselves are never the place to come back to.
     */
    private static RequestCache requestCache() {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        RequestMatcher pages = new AndRequestMatcher(
                path.matcher(HttpMethod.GET, "/**"),
                new NegatedRequestMatcher(path.matcher("/api/**")),
                new NegatedRequestMatcher(path.matcher("/error")),
                new NegatedRequestMatcher(path.matcher("/favicon.*")),
                new NegatedRequestMatcher(path.matcher("/.well-known/**")));
        cache.setRequestMatcher(pages);
        return cache;
    }

    /** The password form's provider: only when the form is on (AUTH_PASSWORD_ENABLED). */
    @Bean
    @Profile({"dev", "stage", "prod"})
    @ConditionalOnProperty(name = "auth.password.enabled", havingValue = "true")
    public AuthenticationProvider devAuthenticationProvider(UserPrincipalService userPrincipalService,
                                                            PasswordEncoder passwordEncoder,
                                                            LoginAttemptService loginAttemptService,
                                                            UserRepository userRepository,
                                                            LoginNameResolver loginNameResolver) {
        return new DevAuthenticationProvider(userPrincipalService, passwordEncoder, loginAttemptService, userRepository,
                loginNameResolver);
    }
}
