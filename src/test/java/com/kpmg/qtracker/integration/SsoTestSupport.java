package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The prod profile with SSO as it is deployed, against the made-up identity provider of
 * src/test/resources/saml (its metadata file stands for SAML_METADATA_URI). Every SSO test class extends this,
 * so they share one application context and one database. The site is https://qtracker.test (the canonical
 * host: MockMvc requests come as localhost, which is never redirected).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "file.upload.dir=target/it-uploads-sso",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "app.base-url=" + SsoTestSupport.SITE,
        "app.canonical-host=qtracker.test",
        "auth.sso.entity-id=" + SsoTestSupport.ENTITY_ID,
        "auth.sso.acs-url=" + SsoTestSupport.ACS_URL,
        "auth.sso.metadata-uri=classpath:saml/test-idp-metadata.xml"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
abstract class SsoTestSupport {

    static final String SITE = "https://qtracker.test";
    static final String ENTITY_ID = SITE + "/saml/qtracker";
    static final String ACS_PATH = "/login/saml2/sso/entra";
    static final String ACS_URL = SITE + ACS_PATH;
    static final String SIGN_ON_PATH = "/saml2/authenticate/entra";
    static final String IDP_ENTITY_ID = "https://idp.qtracker.test/tests-only";
    static final String IDP_SSO_URL = IDP_ENTITY_ID + "/saml2";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;

    /** The answer of the Assertion Consumer Service, and the session id the browser had before it. */
    record SignIn(MvcResult result, String sessionIdBefore) {

        MockHttpSession session() {
            return (MockHttpSession) result.getRequest().getSession(false);
        }

        String redirect() {
            return result.getResponse().getRedirectedUrl();
        }
    }

    /**
     * The browser's way in: open {@code page} without a session (QTracker remembers it and sends the browser to
     * the Sign on URL), the Sign on URL (an AuthnRequest, kept in the session), then the identity provider posts
     * its signed answer to the Assertion Consumer Service - without QTracker's CSRF token, as Microsoft does.
     */
    SignIn signIn(String page, SamlResponses.Person person) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(page).session(session));
        mockMvc.perform(get(SIGN_ON_PATH).session(session));
        String sessionIdBefore = session.getId();
        MvcResult result = mockMvc.perform(post(ACS_PATH).session(session)
                .param("SAMLResponse", SamlResponses.signed(person, authnRequestId(session)))).andReturn();
        return new SignIn(result, sessionIdBefore);
    }

    /** The id of the AuthnRequest the session waits an answer for. */
    static String authnRequestId(MockHttpSession session) {
        return Collections.list(session.getAttributeNames()).stream()
                .map(session::getAttribute)
                .filter(AbstractSaml2AuthenticationRequest.class::isInstance)
                .map(request -> ((AbstractSaml2AuthenticationRequest) request).getId())
                .findFirst().orElseThrow(() -> new IllegalStateException("no AuthnRequest in the session"));
    }

    /** A made-up person on the corporate test domain, with a unique address (the database is shared). */
    User person(String name, AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setMail(name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@corp.example.test");
        user.setDisplayName("Sso " + name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    static String newObjectId() {
        return UUID.randomUUID().toString();
    }
}
