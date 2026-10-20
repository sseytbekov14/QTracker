package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A completed control returned by SoQM Admin Team to an earlier status (decision 5): the holder of that status gets
 * the e-mail and the in-app notification of the return, every later move its own e-mail, the completion T12 to the
 * four roles again - and no planned e-mail (T3, T4, T5) until it is completed again, though it is past its deadline.
 */
class ReopenNotificationsIT extends NotificationScenarioSupport {

    record Cast(User fac, User op, User soqm, User po) {

        User of(String role) {
            return switch (role) {
                case "F" -> fac;
                case "CO" -> op;
                case "SOQM" -> soqm;
                default -> po;
            };
        }
    }

    void move(User actor, Control control, String target, LocalDate day, int hour) throws Exception {
        CLOCK.set(day.atTime(hour, 0));
        MockHttpSession session = login(actor);
        MvcResult result = call(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", target).param("comments", "Move to " + target));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    /** target, its template, who gets it; then the chain to Completed: the moves and their templates. */
    @ParameterizedTest(name = "Completed -> {0}")
    @CsvSource({
            "REVIEW,               T9,  CO,   SOQM_HEAD_REVIEW:CO:T8 PROCESS_OWNER_REVIEW:SOQM:T10 COMPLETED:PO:T12",
            "IN_PROGRESS,          T7,  F,    REVIEW:F:T6 SOQM_HEAD_REVIEW:CO:T8 PROCESS_OWNER_REVIEW:SOQM:T10 COMPLETED:PO:T12",
            "SOQM_HEAD_REVIEW,     T8,  SOQM, PROCESS_OWNER_REVIEW:SOQM:T10 COMPLETED:PO:T12",
            "PROCESS_OWNER_REVIEW, T10, PO,   COMPLETED:PO:T12",
    })
    void aReturnFromCompleted_notifiesEachMove_andNoPlannedEmailUntilCompletedAgain(String target, String template,
                                                                                    String holder, String chain)
            throws Exception {
        Cast cast = new Cast(myEdit("ro-fac"), myEdit("ro-op"), soqm("ro-soqm"), myEdit("ro-po"));
        Control control = control("RO", "COMPLETED", people().facilitator(cast.fac).operator(cast.op).soqm(cast.soqm)
                .owner(cast.po));
        dated(control, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19));

        // Returned on 23.11, past the deadline (19.11)
        LocalDate returned = LocalDate.of(2026, 11, 23);
        move(cast.soqm, control, target, returned, 11);
        assertThat(reload(control).getReopenedAt()).isNotNull();
        assertThat(sent(control)).containsExactly(line(template, cast.of(holder), returned));
        assertThat(inApp(control, cast.of(holder))).singleElement().satisfies(notice ->
                assertThat(notice.getMessage()).endsWith("Return Comment:\nMove to " + target));
        List<String> expected = new ArrayList<>(sent(control));

        // A week of planned runs: nothing (no T3, T4, T5 while returned)
        runEveryDay(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 30));
        assertThat(sent(control)).containsExactlyElementsOf(expected);

        // The chain back to Completed, one e-mail per move (T12 to the four roles)
        LocalDate day = LocalDate.of(2026, 12, 1);
        for (String step : chain.split(" ")) {
            String[] parts = step.split(":");
            move(cast.of(parts[1]), control, parts[0], day, 12);
            if (parts[2].equals("T12")) {
                for (User person : List.of(cast.fac, cast.op, cast.soqm, cast.po)) {
                    expected.add(line("T12", person, day));
                }
            } else {
                String to = switch (parts[0]) {
                    case "REVIEW" -> "CO";
                    case "SOQM_HEAD_REVIEW" -> "SOQM";
                    case "PROCESS_OWNER_REVIEW" -> "PO";
                    default -> "F";
                };
                expected.add(line(parts[2], cast.of(to), day));
            }
            runAt(day.plusDays(1));
            day = day.plusDays(1);
        }
        assertThat(sent(control)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(reload(control).getReopenedAt()).isNull();
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()))
                .noneMatch(notice -> "ON_BEHALF".equals(notice.getType()));
    }
}
