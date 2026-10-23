package com.kpmg.qtracker.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ValueList: one way to read and write a field of several values ("SAP; KICS"). */
class ValueListTest {

    @Test
    void parse_splitsOnSemicolons_trims_dropsEmptyOnes() {
        assertThat(ValueList.parse(" SAP ;KICS;; ;Active Directory ")).containsExactly("SAP", "KICS", "Active Directory");
        assertThat(ValueList.parse(null)).isEmpty();
        assertThat(ValueList.parse("")).isEmpty();
        assertThat(ValueList.parse(" ; ;")).isEmpty();
    }

    @Test
    void parse_readsAnOlderSingleValueAsOne_commasDoNotSplit() {
        assertThat(ValueList.parse("Cloud Environment / Secure Server OS")).containsExactly("Cloud Environment / Secure Server OS");
        assertThat(ValueList.parse("HR-1, HR-2")).containsExactly("HR-1, HR-2");
    }

    @Test
    void parse_keepsTheOrderAndARepeat() {
        assertThat(ValueList.parse("b; a; b")).containsExactly("b", "a", "b");
    }

    @Test
    void join_writesTheValuesTheOneWay() {
        assertThat(ValueList.join(List.of("SAP", "KICS"))).isEqualTo("SAP; KICS");
        assertThat(ValueList.join(Arrays.asList(" SAP ", "", null, "KICS"))).isEqualTo("SAP; KICS");
        assertThat(ValueList.join(List.of())).isEmpty();
        assertThat(ValueList.join(null)).isEmpty();
        assertThat(ValueList.normalize("SAP ;KICS;")).isEqualTo("SAP; KICS");
    }

    @Test
    void joinAndParse_giveTheSameValuesBack() {
        List<String> values = List.of("HR-CTRL-MF-5/FY27/Central/OCT", "KDN 2", "a.b");
        assertThat(ValueList.parse(ValueList.join(values))).isEqualTo(values);
    }

    @Test
    void same_ignoresSpacesAndEmptyValues_notTheOrder() {
        assertThat(ValueList.same("SAP;KICS", " SAP ; KICS ;")).isTrue();
        assertThat(ValueList.same(null, "")).isTrue();
        assertThat(ValueList.same("SAP; KICS", "KICS; SAP")).isFalse();
    }

    @Test
    void key_ignoresCaseAndSpaces() {
        assertThat(ValueList.key(" kdn-1 ")).isEqualTo(ValueList.key("KDN-1"));
        assertThat(ValueList.key("KDN - 1")).isEqualTo(ValueList.key("kdn-1"));
        assertThat(ValueList.contains(List.of("HR-1", "KDN-1"), "kdn-1")).isTrue();
        assertThat(ValueList.contains(List.of("HR-1"), "HR-2")).isFalse();
        assertThat(ValueList.contains(null, "HR-2")).isFalse();
    }

    @Test
    void refusal_namesTheFieldAndTheProblem() {
        assertThat(ValueList.refusal("IT Applications", List.of("SAP", "KICS"))).isEmpty();
        assertThat(ValueList.refusal("IT Applications", List.of())).isEmpty();
        assertThat(ValueList.refusal("IT Applications", List.of("SAP", "sap ")))
                .contains("IT Applications: sap is given twice");
        assertThat(ValueList.refusal("Other Related Controls", List.of("A;B")))
                .contains("Other Related Controls: \"A;B\" holds \";\", which separates the values");
        assertThat(ValueList.refusal("Other Related Controls", List.of("x".repeat(256))))
                .contains("Other Related Controls: a value is longer than 255 characters");
        assertThat(ValueList.refusal("Other Related Controls", List.of("x".repeat(255)))).isEmpty();
        assertThat(ValueList.refusal("IT Applications", Arrays.asList("SAP", " "))).contains("IT Applications: an empty value");
    }
}
