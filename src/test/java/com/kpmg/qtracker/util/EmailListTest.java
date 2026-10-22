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

    /** The cut by hand gives what the regular expression gave, for any mix of separators, spaces and case. */
    @Test
    void parse_givesWhatSplittingByTheRegularExpressionGave() {
        java.util.Random random = new java.util.Random(11);
        char[] alphabet = {'a', 'A', 'b', 'B', '@', '.', ' ', ' ', ',', ';', '\t'};
        for (int n = 0; n < 20_000; n++) {
            StringBuilder raw = new StringBuilder();
            for (int length = random.nextInt(14); length > 0; length--) {
                raw.append(alphabet[random.nextInt(alphabet.length)]);
            }
            assertThat(EmailList.parse(raw.toString())).as("'%s'", raw).isEqualTo(byRegularExpression(raw.toString()));
        }
    }

    /** EmailList.parse as it was: split("[,;]"), trimmed, blanks dropped, the first of a repeated address kept. */
    private static java.util.List<String> byRegularExpression(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> emails = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String part : raw.split("[,;]")) {
            String email = part.trim();
            if (!email.isEmpty() && seen.add(email.toLowerCase(java.util.Locale.ROOT))) {
                emails.add(email);
            }
        }
        return emails;
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
