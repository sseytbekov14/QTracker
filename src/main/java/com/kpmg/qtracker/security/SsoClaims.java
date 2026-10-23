package com.kpmg.qtracker.security;

import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Microsoft sent about the person at their sign-in (NameID and claims as text), kept with their own sign-in
 * only while auth.sso.debug-endpoint is on, for GET /me.
 */
public record SsoClaims(String nameId, Map<String, List<String>> claims) implements Serializable {

    public static SsoClaims of(Saml2AuthenticatedPrincipal principal) {
        Map<String, List<String>> claims = new LinkedHashMap<>();
        principal.getAttributes().forEach((name, values) ->
                claims.put(name, values.stream().map(String::valueOf).toList()));
        return new SsoClaims(principal.getName(), claims);
    }
}
