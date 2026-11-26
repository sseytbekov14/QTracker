package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Before the Admin Panel saves a person's role, access or status (2026-11-26): the controls where they perform a
 * step now and could not after the change, the current step apart from the ones still to come; nothing is saved by
 * the check, and only SoQM Admin Team may ask.
 */
class HeldStepsCheckIT extends ReleaseScenarioSupport {

    MockHttpServletRequestBuilder check(User person, String role, String visibility, String access, boolean enabled) {
        MockHttpServletRequestBuilder request = post("/api/users/{id}/access-check", person.getId()).param("role", role)
                .param("enabled", String.valueOf(enabled));
        if (visibility != null) {
            request.param("visibility", visibility).param("access", access);
        }
        return request;
    }

    static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.forEach(held -> ids.add(held.path("controlId").asText()));
        return ids;
    }

    @Test
    void readOnlyOrDeactivated_listsTheStepsTheyHold_andTheStepsToCome() throws Exception {
        User admin = soqm("hs-soqm");
        User sultan = myEdit("hs-sultan");
        MockHttpSession session = login(admin);
        Control onStep = control("HS1", "IN_PROGRESS", people().facilitator(sultan).operator(myEdit("hs-op1"))
                .soqm(admin).owner(myEdit("hs-po1")));
        Control toCome = control("HS2", "IN_PROGRESS", people().facilitator(myEdit("hs-fac2")).operator(sultan)
                .soqm(admin).owner(myEdit("hs-po2")));
        Control draft = control("HS3", "DRAFT", people().facilitator(myEdit("hs-fac3")).operator(myEdit("hs-op3"))
                .soqm(admin).owner(sultan));
        Control done = control("HS4", "COMPLETED", people().facilitator(sultan).operator(myEdit("hs-op4"))
                .soqm(admin).owner(myEdit("hs-po4")));
        Control poStep = control("HS5", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("hs-fac5"))
                .operator(myEdit("hs-op5")).soqm(admin).owner(sultan));

        for (MockHttpServletRequestBuilder change : List.of(check(sultan, "USER", "MY", "READ_ONLY", true),
                check(sultan, "USER", "MY", "EDIT", false), check(sultan, "KDN", null, null, true))) {
            JsonNode result = json(call(session, change));
            assertThat(result.path("onStepTotal").asInt()).isEqualTo(2);
            assertThat(ids(result.path("onStep"))).containsExactlyInAnyOrder(onStep.getControlId(), poStep.getControlId());
            assertThat(result.path("laterTotal").asInt()).isEqualTo(2);
            assertThat(ids(result.path("later"))).containsExactlyInAnyOrder(toCome.getControlId(), draft.getControlId())
                    .doesNotContain(done.getControlId());
            assertThat(result.path("onStep").get(0).path("places").toString()).containsAnyOf("Facilitator", "Process Owner");
        }
        // Nothing saved by the check
        assertThat(userRepository.findById(sultan.getId()).orElseThrow().getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(userRepository.findById(sultan.getId()).orElseThrow().getEnabled()).isTrue();
    }

    @Test
    void aChangeThatKeepsTheSteps_warnsOfNothing_andOnlySoqmMayAsk() throws Exception {
        User admin = soqm("hk-soqm");
        User aruzhan = myEdit("hk-aruzhan");
        MockHttpSession session = login(admin);
        control("HK1", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("hk-fac")).operator(myEdit("hk-op"))
                .soqm(admin).owner(aruzhan));

        for (MockHttpServletRequestBuilder change : List.of(check(aruzhan, "USER", "ALL", "EDIT", true),
                check(aruzhan, "SOQM_TEAM", null, null, true))) {
            JsonNode result = json(call(session, change));
            assertThat(result.path("onStepTotal").asInt()).isZero();
            assertThat(result.path("laterTotal").asInt()).isZero();
        }
        // Only in Shared With: no step to lose
        User reader = myEdit("hk-reader");
        control("HK2", "IN_PROGRESS", people().facilitator(myEdit("hk-fac2")).operator(myEdit("hk-op2"))
                .soqm(admin).owner(myEdit("hk-po2")).shared(reader));
        assertThat(json(call(session, check(reader, "USER", "MY", "READ_ONLY", true))).path("laterTotal").asInt()).isZero();

        assertThat(status(login(myEdit("hk-user")), check(aruzhan, "USER", "MY", "READ_ONLY", true))).isEqualTo(403);
    }
}
