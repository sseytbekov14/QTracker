package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A notification's text shown on its page: the link lines older texts held are left out, nothing else. */
class NotificationLinksTest {

    @Test
    void theActivationTextOfBefore_losesItsLinkLine_only() {
        String before = "Dear Aiseyeva, Nazira,\n\nThe scheduled control IC-CTRL-MF-1/FY27/Central/OCT / asd has been "
                + "activated today.\n\nPlease click the \"Go to Control\" button below to access the control.\n"
                + "http://localhost:8080/view-control/6\n\nThank you,\n\nKind regards,\nSoQM Team";

        assertThat(NotificationLinks.withoutLinkLines(before)).isEqualTo("Dear Aiseyeva, Nazira,\n\nThe scheduled control "
                + "IC-CTRL-MF-1/FY27/Central/OCT / asd has been activated today.\n\nPlease click the \"Go to Control\" "
                + "button below to access the control.\n\nThank you,\n\nKind regards,\nSoQM Team");
    }

    @Test
    void controlLinkLines_relativeOnes_andEmptyOnes_go_aBlankLineRunIsOneBlankLine() {
        String reminder = "Reminder Type: DAY 3\r\nControl ID: HR-1\r\nControl Link: https://qtracker.kz/view-control/7\r\n"
                + "\r\nKind regards,\r\nSoQM Team";
        assertThat(NotificationLinks.withoutLinkLines(reminder))
                .isEqualTo("Reminder Type: DAY 3\nControl ID: HR-1\n\nKind regards,\nSoQM Team");
        assertThat(NotificationLinks.withoutLinkLines("A\n\n/view-control/7\n\nB")).isEqualTo("A\n\nB");
        assertThat(NotificationLinks.withoutLinkLines("A\nControl Link: \nB")).isEqualTo("A\nB");
        assertThat(NotificationLinks.withoutLinkLines("A\n  /performance-cycle/9  \nB")).isEqualTo("A\nB");
    }

    @Test
    void anAddressInsideASentence_orTypedText_stays() {
        String comment = "Return Comment:\nSee https://sharepoint.test/x for the sample\nhttps is fine";
        assertThat(NotificationLinks.withoutLinkLines(comment)).isEqualTo(comment);
        assertThat(NotificationLinks.withoutLinkLines(null)).isNull();
        assertThat(NotificationLinks.withoutLinkLines("")).isEmpty();
    }

    @Test
    void anEmailCarriesOnlyAnAbsoluteLink() {
        assertThat(NotificationLinks.isAbsolute("https://qtracker.kz:8081/view-control/6")).isTrue();
        assertThat(NotificationLinks.isAbsolute("http://localhost:8080/view-control/6")).isTrue();
        assertThat(NotificationLinks.isAbsolute("/view-control/6")).isFalse();
        assertThat(NotificationLinks.isAbsolute("")).isFalse();
        assertThat(NotificationLinks.isAbsolute(null)).isFalse();
    }
}
