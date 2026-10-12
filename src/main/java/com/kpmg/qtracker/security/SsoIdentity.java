package com.kpmg.qtracker.security;

import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

/**
 * Who Microsoft says signed in, from the SAML answer: the NameID, the claims for the e-mail address and the
 * user principal name (UPN) and the Entra object id (immutable). Values as sent, trimmed; null when absent.
 */
public record SsoIdentity(String nameId, String email, String upn, String objectId) {

    public static final String EMAIL_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";
    /** Entra's default claim "name" carries the UPN (user.userprincipalname). */
    public static final String NAME_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name";
    public static final String UPN_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn";
    public static final String OBJECT_ID_CLAIM = "http://schemas.microsoft.com/identity/claims/objectidentifier";

    public SsoIdentity {
        nameId = clean(nameId);
        email = clean(email);
        upn = clean(upn);
        objectId = clean(objectId);
    }

    public static SsoIdentity of(Saml2AuthenticatedPrincipal principal) {
        String upn = first(principal, UPN_CLAIM);
        return new SsoIdentity(principal.getName(), first(principal, EMAIL_CLAIM),
                upn != null ? upn : first(principal, NAME_CLAIM), first(principal, OBJECT_ID_CLAIM));
    }

    private static String first(Saml2AuthenticatedPrincipal principal, String claim) {
        Object value = principal.getFirstAttribute(claim);
        return value == null ? null : value.toString();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
