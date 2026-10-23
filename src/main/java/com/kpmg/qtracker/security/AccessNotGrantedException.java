package com.kpmg.qtracker.security;

import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;

/**
 * Microsoft signed the person in, but QTracker does not let them in (no account, disabled, ...). A SAML
 * exception so the SAML provider passes it on unchanged; the person sees only "Access is not granted".
 */
public class AccessNotGrantedException extends Saml2AuthenticationException {

    public static final String ERROR_CODE = "access_not_granted";

    public AccessNotGrantedException(String reason) {
        super(new Saml2Error(ERROR_CODE, reason));
    }
}
