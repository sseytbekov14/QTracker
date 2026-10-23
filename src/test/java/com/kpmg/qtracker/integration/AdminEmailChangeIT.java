package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Admin Panel changes the e-mail of someone who has signed in (2026-10-21): the person's places in the controls
 * - the four people fields and Control Shared With - follow the new address, nothing else of the controls changes,
 * and the person signs in with the new address and sees them.
 */
class AdminEmailChangeIT extends ReleaseScenarioSupport {

    private MvcResult changeEmail(MockHttpSession admin, User target, String email) throws Exception {
        return mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(csrf().asHeader()).session(admin)
                .param("email", email)
                .param("role", "USER")
                .param("visibility", "MY")
                .param("access", "EDIT")
                .param("enabled", "true")).andReturn();
    }

    private ControlAssignment assignment(Control control) {
        return assignmentRepository.findByControlId(control.getId()).orElseThrow();
    }

    @Test
    void aChangedEmail_takesThePersonsPlacesInTheControls_andTheySignInWithIt() throws Exception {
        User admin = soqm("em-admin");
        User person = myEdit("em-person");
        User other = myEdit("em-other");
        Control asFacilitatorAndShared = control("HR-EM", "IN_PROGRESS", people().facilitator(person).operator(other)
                .soqm(admin).owner(other).shared(other, person));
        Control asOwner = control("HR-EM", "REVIEW", people().facilitator(other).operator(other).soqm(admin)
                .owner(person));
        Control notTheirs = control("HR-EM", "IN_PROGRESS", people().facilitator(other).operator(other).soqm(admin)
                .owner(other));
        // An address that only contains theirs is someone else's
        ControlAssignment lookalike = assignment(notTheirs);
        lookalike.setControlSharedWith("x" + person.getMail());
        assignmentRepository.save(lookalike);
        MockHttpSession oldSession = login(person);
        User signedIn = userRepository.findById(person.getId()).orElseThrow();
        assertThat(signedIn.getLastLoginAt()).isNotNull();
        String moved = "em-moved-" + next() + "-" + RUN + "@release.test";

        MvcResult result = changeEmail(login(admin), person, moved.toUpperCase(java.util.Locale.ROOT));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat(userRepository.findById(person.getId()).orElseThrow().getMail()).isEqualTo(moved);
        ControlAssignment first = assignment(asFacilitatorAndShared);
        assertThat(first.getFacilitator()).isEqualTo(moved);
        assertThat(first.getControlSharedWith()).isEqualTo(other.getMail() + "," + moved);
        assertThat(first.getControlOperator()).isEqualTo(other.getMail());
        assertThat(first.getSoqmLead()).isEqualTo(admin.getMail());
        assertThat(assignment(asOwner).getProcessOwner()).isEqualTo(moved);
        assertThat(assignment(notTheirs).getControlSharedWith()).isEqualTo("x" + person.getMail());
        assertThat(assignment(notTheirs).getProcessOwner()).isEqualTo(other.getMail());

        // The session of the old address ends; the new address signs in and the controls are theirs
        assertThat(mockMvc.perform(get("/view-control/{id}", asOwner.getId()).session(oldSession)).andReturn()
                .getResponse().getStatus()).isNotEqualTo(200);
        assertThat(signIn(person.getMail(), PASSWORD).getResponse().getRedirectedUrl()).isNotEqualTo("/");
        User now = userRepository.findById(person.getId()).orElseThrow();
        MockHttpSession newSession = login(now);
        assertThat(call(newSession, get("/view-control/{id}", asFacilitatorAndShared.getId())).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(call(newSession, get("/view-control/{id}", asOwner.getId())).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void anEmailThatIsSomeoneElses_isRefused_andNoControlChanges() throws Exception {
        User admin = soqm("em-admin2");
        User person = myEdit("em-person2");
        User other = myEdit("em-other2");
        person.setLastLoginAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        userRepository.save(person);
        Control control = control("HR-EM", "IN_PROGRESS", people().facilitator(person).operator(other).soqm(admin)
                .owner(other));

        MvcResult result = changeEmail(login(admin), person, other.getMail());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo("User with this email already exists");
        assertThat(userRepository.findById(person.getId()).orElseThrow().getMail()).isEqualTo(person.getMail());
        assertThat(assignment(control).getFacilitator()).isEqualTo(person.getMail());
    }
}
