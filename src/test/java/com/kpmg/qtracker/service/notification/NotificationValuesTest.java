package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.service.WorkingDaysService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The values of a control's placeholders: the dates as "14 Nov 2026", in English whatever the server's language. */
class NotificationValuesTest {

    private final NotificationValues values = new NotificationValues("https://qtracker.example.test/");

    private static Control control() {
        Control control = new Control();
        control.setId(7L);
        control.setControlId(" HR-1/FY27 ");
        control.setControlDescription(" Payroll ");
        return control;
    }

    private static ControlAssignmentDTO assignment(LocalDate occurrence, LocalDate deadline) {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(occurrence);
        assignment.setControlOperationDeadline(deadline);
        return assignment;
    }

    @Test
    void theDates_dayShortMonthYear() {
        Map<String, String> of = values.of(control(), assignment(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 14)));

        assertThat(of.get(NotificationValues.OCCURRENCE_DATE)).isEqualTo("5 Nov 2026");
        assertThat(of.get(NotificationValues.DEADLINE)).isEqualTo("14 Nov 2026");
    }

    @Test
    void theDates_areInEnglish_onAServerInAnotherLanguage() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ru-RU"));
        try {
            assertThat(values.of(control(), assignment(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 5, 31)))
                    .get(NotificationValues.DEADLINE)).isEqualTo("31 May 2026");
            assertThat(values.of(control(), assignment(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)))
                    .get(NotificationValues.DEADLINE)).isEqualTo("1 Sep 2026");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void aDateTheControlDoesNotHave_isNull_soATextThatNeedsItIsNotMade() {
        Map<String, String> of = values.of(control(), assignment(null, null));

        assertThat(of.get(NotificationValues.OCCURRENCE_DATE)).isNull();
        assertThat(of.get(NotificationValues.DEADLINE)).isNull();
    }

    /** 2026-11-02 is a Monday; 14 Nov 2026 a Saturday; the working days are Monday to Friday, no holidays. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2026-11-16 | 2026-11-16 | 16 Nov 2026 (today)
            2026-11-16 | 2026-11-13 | 16 Nov 2026 (in 1 working day)
            2026-11-16 | 2026-11-12 | 16 Nov 2026 (in 2 working days)
            2026-11-20 | 2026-11-16 | 20 Nov 2026 (in 4 working days)
            2026-11-16 | 2026-11-17 | 16 Nov 2026 (overdue by 1 working day)
            2026-11-13 | 2026-11-16 | 13 Nov 2026 (overdue by 1 working day)
            2026-11-13 | 2026-11-17 | 13 Nov 2026 (overdue by 2 working days)
            2026-11-14 | 2026-11-16 | 14 Nov 2026 (overdue by 1 working day)
            2026-11-14 | 2026-11-13 | 14 Nov 2026
            """)
    void theDue_inWorkingDays(LocalDate deadline, LocalDate today, String due) {
        assertThat(NotificationValues.due(new WorkingDaysService(), deadline, today)).isEqualTo(due);
    }

    @Test
    void theIdAndTheDescription_trimmed_theLinkFromTheBaseUrl() {
        Map<String, String> of = values.of(control(), assignment(null, null));

        assertThat(of.get(NotificationValues.CONTROL_ID)).isEqualTo("HR-1/FY27");
        assertThat(of.get(NotificationValues.CONTROL_NAME)).isEqualTo("Payroll");
        assertThat(of.get(TemplateRenderer.LINK)).isEqualTo("https://qtracker.example.test/view-control/7");
    }
}
