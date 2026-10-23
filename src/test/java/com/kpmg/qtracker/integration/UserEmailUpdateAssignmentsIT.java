package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.userimport.UserEmailUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * --update-user-emails changes an address the way the Admin Panel does (D-1, 2026-10-22): the person's places in the
 * controls - the four people fields and Control Shared With - follow the new address, an address that only contains
 * theirs stays, the person signs in with the new address and sees their controls, and someone else's address is
 * refused with the reason.
 */
class UserEmailUpdateAssignmentsIT extends ReleaseScenarioSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @Autowired
    private UserEmailUpdate update;

    @TempDir
    private Path folder;

    private ControlAssignment assignment(Control control) {
        return assignmentRepository.findByControlId(control.getId()).orElseThrow();
    }

    @Test
    void theNewAddress_takesThePersonsPlacesInTheControls_andTheySeeThem() throws Exception {
        User admin = soqm("ue-admin");
        User person = myEdit("ue-person");
        User other = myEdit("ue-other");
        Control asFacilitatorAndShared = control("HR-UE", "IN_PROGRESS", people().facilitator(person).operator(other)
                .soqm(admin).owner(other).shared(other, person));
        Control asOperator = control("HR-UE", "IN_PROGRESS", people().facilitator(other).operator(person)
                .soqm(admin).owner(other));
        Control asOwner = control("HR-UE", "REVIEW", people().facilitator(other).operator(other).soqm(admin)
                .owner(person));
        Control notTheirs = control("HR-UE", "IN_PROGRESS", people().facilitator(other).operator(other).soqm(admin)
                .owner(other));
        // An address that only contains theirs is someone else's
        ControlAssignment lookalike = assignment(notTheirs);
        lookalike.setControlSharedWith("x" + person.getMail());
        assignmentRepository.save(lookalike);
        String moved = "ue-moved-" + next() + "-" + RUN + "@corp.release.test";

        UserEmailUpdate.Report report = update.run(file("User,Email\r\n"
                + person.getDisplayName() + "," + moved.toUpperCase(Locale.ROOT) + "\r\n"), true, "tester", TODAY);

        assertThat(report.applied()).as(report.render()).isTrue();
        assertThat(report.lines()).singleElement().satisfies(line -> assertThat(line.moved()).isEqualTo(3));
        assertThat(report.render()).contains("user " + person.getId() + ", changed, assignments moved 3")
                .doesNotContain(moved);
        assertThat(userRepository.findById(person.getId()).orElseThrow().getMail()).isEqualTo(moved);
        ControlAssignment first = assignment(asFacilitatorAndShared);
        assertThat(first.getFacilitator()).isEqualTo(moved);
        assertThat(first.getControlSharedWith()).isEqualTo(other.getMail() + "," + moved);
        assertThat(first.getControlOperator()).isEqualTo(other.getMail());
        assertThat(first.getSoqmLead()).isEqualTo(admin.getMail());
        assertThat(assignment(asOperator).getControlOperator()).isEqualTo(moved);
        assertThat(assignment(asOwner).getProcessOwner()).isEqualTo(moved);
        assertThat(assignment(notTheirs).getControlSharedWith()).isEqualTo("x" + person.getMail());

        MockHttpSession session = login(userRepository.findById(person.getId()).orElseThrow());
        for (Control theirs : new Control[] {asFacilitatorAndShared, asOperator, asOwner}) {
            assertThat(status(session, get("/view-control/{id}", theirs.getId()))).as(theirs.getControlId())
                    .isEqualTo(200);
        }
        assertThat(status(session, get("/view-control/{id}", notTheirs.getId()))).isNotEqualTo(200);
    }

    @Test
    void someoneElsesAddress_isRefusedWithTheReason_andNoControlChanges() throws Exception {
        User admin = soqm("ue-admin2");
        User person = myEdit("ue-person2");
        User other = myEdit("ue-other2");
        Control control = control("HR-UE", "IN_PROGRESS", people().facilitator(person).operator(other).soqm(admin)
                .owner(other));

        UserEmailUpdate.Report report = update.run(file("User,Email\r\n"
                + person.getDisplayName() + "," + other.getMail() + "\r\n"), true, "tester", TODAY);

        assertThat(report.refused()).isTrue();
        assertThat(report.lines()).singleElement().satisfies(line -> {
            assertThat(line.outcome()).isEqualTo(UserEmailUpdate.Outcome.ERROR);
            assertThat(line.reason()).isEqualTo("Email already belongs to user " + other.getId());
        });
        assertThat(report.render()).contains("REFUSED").contains("already belongs to user " + other.getId());
        assertThat(userRepository.findById(person.getId()).orElseThrow().getMail()).isEqualTo(person.getMail());
        assertThat(assignment(control).getFacilitator()).isEqualTo(person.getMail());
    }

    private Path file(String csv) throws Exception {
        Path file = folder.resolve("emails.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file;
    }
}
