package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.security.SsoAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole sign-in with signed SAML answers of the test identity provider: who gets in, what the session holds
 * afterwards, and what a refused person sees, leaves in the log and in the audit trail.
 */
@ExtendWith(OutputCaptureExtension.class)
class SsoSignInIT extends SsoTestSupport {

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @Test
    void aKnownPerson_getsIn_andComesBackToThePageAskedFor() throws Exception {
        User user = person("anna", AccessLevel.PARTICIPANT, AccessScope.OWN);
        String objectId = newObjectId();

        SignIn signIn = signIn("/controls", SamlResponses.Person.of(user.getMail(), objectId));

        assertThat(signIn.redirect()).endsWith("/controls?continue");
        MockHttpSession session = signIn.session();
        assertThat(session.getId()).as("a new session id after the sign-in").isNotEqualTo(signIn.sessionIdBefore());
        assertThat(session.getAttribute("currentUser")).isInstanceOfSatisfying(User.class,
                current -> assertThat(current.getId()).isEqualTo(user.getId()));
        User stored = userRepository.findById(user.getId()).orElseThrow();
        assertThat(stored.getLastLoginAt()).isNotNull();
        assertThat(stored.getEntraOid()).isEqualTo(objectId);
        assertThat(auditLogRepository.findByActionTypeOrderByCreatedAtDesc(SsoAdmission.AUDIT_LINKED))
                .anySatisfy(log -> assertThat(log.getActionDescription()).endsWith("for " + user.getMail()));

        mockMvc.perform(get("/controls").session(session)).andExpect(status().isFound());
        mockMvc.perform(get("/component/ALL").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/").session(session)).andExpect(status().isOk());
    }

    @Test
    void theAddress_inAnotherCase_andWithSpaces_isTheSameAccount() throws Exception {
        User user = person("boris", AccessLevel.PARTICIPANT, AccessScope.OWN);
        String shouted = "  " + user.getMail().toUpperCase(Locale.ROOT) + " ";

        SignIn signIn = signIn("/", new SamlResponses.Person(shouted, shouted, null, newObjectId()));

        assertThat(signIn.session().getAttribute("currentUser")).isNotNull();
    }

    @Test
    void afterTheFirstSignIn_theObjectIdFindsThePerson_evenWithANewAddress() throws Exception {
        User user = person("clara", AccessLevel.PARTICIPANT, AccessScope.OWN);
        String objectId = newObjectId();
        signIn("/", SamlResponses.Person.of(user.getMail(), objectId));

        String renamed = "clara.new-" + user.getId() + "@corp.example.test";
        SignIn again = signIn("/", SamlResponses.Person.of(renamed, objectId));

        assertThat(again.session().getAttribute("currentUser")).isInstanceOfSatisfying(User.class,
                current -> assertThat(current.getId()).isEqualTo(user.getId()));
    }

    @Test
    void theUpn_findsThePerson_whenTheAddressDoesNot() throws Exception {
        User user = person("dana", AccessLevel.PARTICIPANT, AccessScope.OWN);
        String other = "dana.other-" + user.getId() + "@corp.example.test";

        SignIn signIn = signIn("/", new SamlResponses.Person(other, other, user.getMail(), newObjectId()));

        assertThat(signIn.session().getAttribute("currentUser")).isNotNull();
    }

    @Test
    void anUnknownPerson_isRefused_withoutAnAccountBeingCreated(CapturedOutput output) throws Exception {
        long before = userRepository.count();
        String stranger = "stranger-" + newObjectId().substring(0, 8) + "@corp.example.test";

        SignIn signIn = signIn("/controls", SamlResponses.Person.of(stranger, newObjectId()));

        assertRefused(signIn);
        assertThat(userRepository.count()).isEqualTo(before);
        assertThat(userRepository.findByMail(stranger)).isEmpty();
        assertThat(output.getOut()).contains("SSO sign-in refused: user=- reason=UNKNOWN").doesNotContain(stranger);
        assertThat(refusals()).anySatisfy(log -> assertThat(log.getActionDescription())
                .isEqualTo("SSO sign-in refused (no account) for " + stranger));
    }

    @Test
    void aDisabledPerson_isRefused(CapturedOutput output) throws Exception {
        User user = person("erik", AccessLevel.PARTICIPANT, AccessScope.OWN);
        user.setEnabled(false);
        userRepository.save(user);

        assertRefused(signIn("/", SamlResponses.Person.of(user.getMail(), newObjectId())));
        assertThat(output.getOut()).contains("SSO sign-in refused: user=" + user.getId() + " reason=DISABLED")
                .doesNotContain(user.getMail());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoginAt()).isNull();
    }

    @Test
    void anAccountOnTheServiceDomain_isRefused() throws Exception {
        User user = person("fiona", AccessLevel.PARTICIPANT, AccessScope.OWN);
        user.setMail("fiona" + user.getId() + "@qtracker.local");
        userRepository.save(user);

        assertRefused(signIn("/", SamlResponses.Person.of(user.getMail(), newObjectId())));
    }

    @Test
    void anObjectIdAndAnAddress_ofDifferentPeople_areRefused_andAudited() throws Exception {
        User linked = person("gleb", AccessLevel.PARTICIPANT, AccessScope.OWN);
        String objectId = newObjectId();
        signIn("/", SamlResponses.Person.of(linked.getMail(), objectId));
        User other = person("hana", AccessLevel.PARTICIPANT, AccessScope.OWN);

        assertRefused(signIn("/", SamlResponses.Person.of(other.getMail(), objectId)));
        assertThat(refusals()).anySatisfy(log -> assertThat(log.getNewValues())
                .startsWith("reason=MISMATCH, userId=" + linked.getId()));
    }

    @Test
    void anAnswerForAnotherApplication_isNotASignIn() throws Exception {
        User user = person("ivan", AccessLevel.SOQM, AccessScope.ALL);
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(SIGN_ON_PATH).session(session));
        String answer = SamlResponses.signed(SamlResponses.Person.of(user.getMail(), newObjectId()),
                authnRequestId(session), "https://another-application.example.test");

        String redirect = mockMvc.perform(post(ACS_PATH).session(session).param("SAMLResponse", answer))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).isEqualTo("/sign-in-failed");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoginAt()).isNull();
    }

    /** The checks of the Admin Panel and of read-only users work for a person who came in with SSO. */
    @Test
    void afterAnSsoSignIn_theAdminPanelAndReadOnlyChecks_apply() throws Exception {
        User soqm = person("soqm", AccessLevel.SOQM, AccessScope.ALL);
        MockHttpSession soqmSession = signIn("/admin/users", SamlResponses.Person.of(soqm.getMail(), newObjectId())).session();
        mockMvc.perform(get("/admin/users").session(soqmSession)).andExpect(status().isOk());

        User participant = person("plain", AccessLevel.PARTICIPANT, AccessScope.OWN);
        MockHttpSession participantSession = signIn("/", SamlResponses.Person.of(participant.getMail(), newObjectId())).session();
        String adminRedirect = mockMvc.perform(get("/admin/users").session(participantSession))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(adminRedirect).isEqualTo("/");
        mockMvc.perform(post("/api/users").session(participantSession).with(csrf().asHeader())
                .param("email", "new-" + newObjectId().substring(0, 6) + "@corp.example.test").param("role", "USER"))
                .andExpect(status().isForbidden());

        User readOnly = person("reader", AccessLevel.READ_ONLY, AccessScope.ALL);
        MockHttpSession readerSession = signIn("/", SamlResponses.Person.of(readOnly.getMail(), newObjectId())).session();
        mockMvc.perform(post("/api/controls/1/rename-id").session(readerSession).with(csrf().asHeader())
                        .param("newControlId", "X-1"))
                .andExpect(status().isForbidden());
    }

    /** Disabled later on: the next request ends the SSO session like the form's. */
    @Test
    void aPersonDisabledAfterTheSignIn_isOutAtTheNextRequest() throws Exception {
        User user = person("jana", AccessLevel.PARTICIPANT, AccessScope.OWN);
        MockHttpSession session = signIn("/", SamlResponses.Person.of(user.getMail(), newObjectId())).session();
        user = userRepository.findById(user.getId()).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        mockMvc.perform(get("/api/users").session(session)).andExpect(status().isForbidden());
    }

    private void assertRefused(SignIn signIn) throws Exception {
        assertThat(signIn.redirect()).isEqualTo("/access-not-granted");
        MockHttpSession session = signIn.session();
        if (session != null) {
            assertThat(session.getAttribute("currentUser")).isNull();
            mockMvc.perform(get("/api/users").session(session)).andExpect(status().isUnauthorized());
        }
    }

    private List<AdminAuditLog> refusals() {
        return auditLogRepository.findByActionTypeOrderByCreatedAtDesc(SsoAdmission.AUDIT_REFUSED);
    }
}
