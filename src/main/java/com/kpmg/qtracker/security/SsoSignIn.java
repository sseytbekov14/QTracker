package com.kpmg.qtracker.security;

import com.kpmg.qtracker.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The QTracker side of the SAML sign-in. Spring Security checks the answer of Microsoft (signature, audience,
 * time, the request it answers); then {@link #admit} decides whether the person gets in. In: the session user
 * as after the form, and the page that was asked for. Out: "Access is not granted" for a refusal, "Sign-in did
 * not complete" for an answer Spring refused, with the reason in the log (WARN, no addresses).
 */
@Component
@ConditionalOnProperty(name = "auth.sso.enabled", havingValue = "true")
public class SsoSignIn {

    public static final String ACCESS_NOT_GRANTED_PATH = "/access-not-granted";
    public static final String SIGN_IN_FAILED_PATH = "/sign-in-failed";

    private static final Logger logger = LoggerFactory.getLogger(SsoSignIn.class);
    private static final Pattern ADDRESS = Pattern.compile("[^\\s\\[\\]<>\"',;:]+@[^\\s\\[\\]<>\"',;:]+");

    private final UserRepository userRepository;
    private final SsoAdmission admission;
    private final UserPrincipalService principals;
    private final boolean keepClaims;
    private final Converter<OpenSaml4AuthenticationProvider.ResponseToken, Saml2Authentication> saml =
            OpenSaml4AuthenticationProvider.createDefaultResponseAuthenticationConverter();

    public SsoSignIn(UserRepository userRepository, SsoAdmission admission, UserPrincipalService principals,
                     @Value("${auth.sso.debug-endpoint:false}") boolean keepClaims) {
        this.userRepository = userRepository;
        this.admission = admission;
        this.principals = principals;
        this.keepClaims = keepClaims;
    }

    /** The SAML provider: Spring's checks of the answer, then QTracker's admission. */
    public OpenSaml4AuthenticationProvider provider() {
        OpenSaml4AuthenticationProvider provider = new OpenSaml4AuthenticationProvider();
        provider.setResponseAuthenticationConverter(this::admit);
        return provider;
    }

    /**
     * Whether the person Microsoft signed in gets into QTracker (SsoAdmission). In: the same principal and
     * authorities as after the password form, so every check downstream (the disabled-user guard, the Admin
     * Panel, read-only) works unchanged.
     */
    AbstractAuthenticationToken admit(OpenSaml4AuthenticationProvider.ResponseToken token) {
        Saml2Authentication answer = saml.convert(token);
        if (answer == null || !(answer.getPrincipal() instanceof Saml2AuthenticatedPrincipal person)) {
            throw new AccessNotGrantedException("no principal in the answer");
        }
        SsoAdmission.Decision decision = admission.admit(SsoIdentity.of(person));
        if (!decision.admitted()) {
            throw new AccessNotGrantedException(decision.refusal().name());
        }
        UserPrincipal principal = principals.principalOf(decision.user());
        List<GrantedAuthority> authorities = principal.getRoles().stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        // The claims only for the person's own GET /me (SsoDebugController), kept with their sign-in
        return new SsoAuthenticationToken(principal, authorities, keepClaims ? SsoClaims.of(person) : null);
    }

    /** The session user as after the form, then the page that was asked for (or the Dashboard). */
    public AuthenticationSuccessHandler successHandler(RequestCache requestCache) {
        SavedRequestAwareAuthenticationSuccessHandler toPage = new SavedRequestAwareAuthenticationSuccessHandler();
        toPage.setRequestCache(requestCache);
        return (request, response, authentication) -> {
            storeSessionUser(request, authentication);
            toPage.onAuthenticationSuccess(request, response, authentication);
        };
    }

    public AuthenticationFailureHandler failureHandler() {
        return this::failed;
    }

    private void storeSessionUser(HttpServletRequest request, Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            userRepository.findById(principal.getId())
                    .ifPresent(user -> request.getSession(true).setAttribute("currentUser", user));
        }
    }

    private void failed(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (exception instanceof AccessNotGrantedException) {
            // Logged where it was decided, with the user id and the reason
            response.sendRedirect(request.getContextPath() + ACCESS_NOT_GRANTED_PATH);
            return;
        }
        String code = exception instanceof Saml2AuthenticationException saml
                ? saml.getSaml2Error().getErrorCode() : exception.getClass().getSimpleName();
        logger.warn("SSO sign-in failed: {} {}", code, withoutAddresses(exception.getMessage()));
        response.sendRedirect(request.getContextPath() + SIGN_IN_FAILED_PATH);
    }

    /** The reason as Spring words it, with anything that looks like an e-mail address masked. */
    static String withoutAddresses(String text) {
        return text == null ? "" : ADDRESS.matcher(text).replaceAll("<address>");
    }
}
