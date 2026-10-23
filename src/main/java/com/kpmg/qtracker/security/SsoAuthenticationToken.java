package com.kpmg.qtracker.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * A sign-in with SSO: the same principal and authorities as the password form's, plus what Microsoft sent
 * (only while auth.sso.debug-endpoint is on, else null). Not in the details: the SAML provider replaces those
 * with the request's.
 */
public class SsoAuthenticationToken extends UsernamePasswordAuthenticationToken {

    private final SsoClaims claims;

    public SsoAuthenticationToken(UserPrincipal principal, Collection<? extends GrantedAuthority> authorities,
                                  SsoClaims claims) {
        super(principal, null, authorities);
        this.claims = claims;
    }

    public SsoClaims claims() {
        return claims;
    }
}
