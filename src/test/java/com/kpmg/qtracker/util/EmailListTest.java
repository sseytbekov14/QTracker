package com.kpmg.qtracker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailListTest {

    @Test
    void parse_acceptsCommasAndSemicolons_withOrWithoutSpaces() {
        assertThat(EmailList.parse("a@kpmg.kz,b@kpmg.kz")).containsExactly("a@kpmg.kz", "b@kpmg.kz");
        assertThat(EmailList.parse("a@kpmg.kz;b@kpmg.kz")).containsExactly("a@kpmg.kz", "b@kpmg.kz");
        assertThat(EmailList.parse(" a@kpmg.kz ; b@kpmg.kz , c@kpmg.kz ")).containsExactly("a@kpmg.kz", "b@kpmg.kz", "c@kpmg.kz");
    }

    @Test
    void parse_dropsBlanks_andKeepsARepeatedAddressOnceAsFirstWritten() {
        assertThat(EmailList.parse("a@kpmg.kz;;, ,A@KPMG.kz;b@kpmg.kz,a@kpmg.kz;"))
                .containsExactly("a@kpmg.kz", "b@kpmg.kz");
    }

    @Test
    void parse_emptyInput_givesEmptyList() {
        assertThat(EmailList.parse(null)).isEmpty();
        assertThat(EmailList.parse("")).isEmpty();
        assertThat(EmailList.parse(" ; , ")).isEmpty();
    }

    @Test
    void contains_matchesWholeAddressesIgnoringCase() {
        String stored = "Aigerim@KPMG.kz; ba@kpmg.kz";

        assertThat(EmailList.contains(stored, "aigerim@kpmg.kz")).isTrue();
        assertThat(EmailList.contains(stored, " BA@kpmg.kz ")).isTrue();
        // Part of another address is not a match
        assertThat(EmailList.contains(stored, "a@kpmg.kz")).isFalse();
        assertThat(EmailList.contains(stored, "kpmg.kz")).isFalse();
        assertThat(EmailList.contains(stored, null)).isFalse();
        assertThat(EmailList.contains(null, "a@kpmg.kz")).isFalse();
    }
}
