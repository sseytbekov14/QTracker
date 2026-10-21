package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * View Control at SoQM's own step (SoQM Head Review, 2026-10-21): Send to Process Owner and one Return menu - to the
 * Control Operator and to the Facilitator (In Progress) - instead of Return to Operator next to Move / Return, whose
 * dialog offered the same moves again. Elsewhere SoQM keeps Move / Return.
 */
class SoqmReturnMenuIT extends ReleaseScenarioSupport {

    private User soqm;
    private User facilitator;
    private People people;

    @BeforeEach
    void setUp() {
        soqm = soqm("rm-soqm");
        facilitator = myEdit("rm-fac");
        people = people().soqm(soqm).facilitator(facilitator).operator(myEdit("rm-op")).owner(myEdit("rm-po"));
    }

    private String page(User user, Control control) throws Exception {
        return body(call(login(user), get("/view-control/{id}", control.getId())));
    }

    @Test
    void atSoqmHeadReview_oneReturnMenu_toTheOperatorAndToInProgress_andNoMoveButton() throws Exception {
        Control control = control("HR-RM", "SOQM_HEAD_REVIEW", people);

        String page = page(soqm, control);

        assertThat(page).contains("id=\"submitToProcessOwnerBtn\"", "id=\"soqmReturnGroup\"",
                "id=\"soqmReturnMenuBtn\"", "id=\"returnToOperatorBtn\"", "id=\"soqmReturnToInProgressBtn\"",
                "data-soqm-move-target=\"IN_PROGRESS\"", "data-bs-target=\"#soqmMoveModal\"",
                "Add SoQM Head/Team Comments first");
        assertThat(page).doesNotContain("id=\"soqmMoveBtn\"");
        // The dialog the In Progress item opens is still on the page, with that move among its options
        assertThat(page).contains("id=\"soqmMoveModal\"", "value=\"IN_PROGRESS\"");
        // To the Control Operator first, then to In Progress
        assertThat(page.indexOf("id=\"returnToOperatorBtn\"")).isLessThan(page.indexOf("id=\"soqmReturnToInProgressBtn\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED"})
    void atAnotherRolesStep_soqmKeepsMoveReturn_andNoInProgressItem(String status) throws Exception {
        Control control = control("HR-RM", status, people);

        String page = page(soqm, control);

        assertThat(page).contains("id=\"soqmMoveBtn\"", "id=\"soqmMoveModal\"");
        assertThat(page).doesNotContain("id=\"soqmReturnToInProgressBtn\"");
    }

    @Test
    void theFacilitator_hasNoReturnMenuItemToInProgress_norMoveButton() throws Exception {
        Control control = control("HR-RM", "SOQM_HEAD_REVIEW", people);

        String page = page(facilitator, control);

        assertThat(page).doesNotContain("id=\"soqmReturnToInProgressBtn\"", "id=\"soqmMoveBtn\"",
                "id=\"soqmMoveModal\"");
    }
}
