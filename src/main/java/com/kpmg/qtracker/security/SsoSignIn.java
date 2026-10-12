package com.kpmg.qtracker.security;

import com.kpmg.qtracker.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    public SsoSignIn(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The SAML provider: Spring's checks of the answer, then QTracker's admission. */
    public OpenSaml4AuthenticationProvider provider() {
        OpenSaml4AuthenticationProvider provider = new OpenSaml4AuthenticationProvider();
        provider.setResponseAuthenticationConverter(this::admit);
        return provider;
    }

    /** Whether the person Microsoft signed in gets into QTracker. */
    AbstractAuthenticationToken admit(OpenSaml4AuthenticationProvider.ResponseToken token) {
        logger.warn("SSO sign-in refused: user=- reason=ADMISSION_NOT_CONFIGURED");
        throw new AccessNotGrantedException("admission is not configured");
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
