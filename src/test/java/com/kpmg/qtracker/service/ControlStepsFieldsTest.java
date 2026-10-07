package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControlStepsFieldsTest {

    @ParameterizedTest(name = "F=[{0}] CO=[{1}] -> one person: {2}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            // the same person
            "fac@x.kz            | fac@x.kz                 | true",
            // case and spaces do not matter
            "Fac@X.kz            | fac@x.KZ                 | true",
            "'  fac@x.kz  '      | fac@x.kz                 | true",
            "fac@x.kz            | ' FAC@x.kz '             | true",
            // several people: every Operator must be a Facilitator
            "a@x.kz;b@x.kz       | b@x.kz                   | true",
            "a@x.kz; b@x.kz      | 'B@x.kz ;a@x.kz'         | true",
            "a@x.kz,b@x.kz       | a@x.kz;b@x.kz            | true",
            "a@x.kz;b@x.kz       | b@x.kz;c@x.kz            | false",
            "a@x.kz              | a@x.kz;c@x.kz            | false",
            "a@x.kz              | a@x.kz;a@x.kz            | true",
            // different people
            "fac@x.kz            | op@x.kz                  | false",
            // a part of an address is not the address
            "fac@x.kz            | ac@x.kz                  | false",
            "fac@x.kz.org        | fac@x.kz                 | false",
            // separators only, blanks and empty slots
            "fac@x.kz;;          | ';fac@x.kz;'             | true",
            "fac@x.kz            | NULL                     | true",
            "fac@x.kz            | ''                       | true",
            "fac@x.kz            | ' ; , '                  | true",
            "NULL                | NULL                     | true",
            "NULL                | op@x.kz                  | false",
            "' ; '               | op@x.kz                  | false",
    })
    void onePerson_whenEveryOperatorIsAFacilitator(String facilitators, String operators, boolean expected) {
        assertThat(ControlStepsFields.onePerson(facilitators, operators)).isEqualTo(expected);
        assertThat(ControlStepsFields.split(facilitators, operators)).isEqualTo(!expected);
        // Control Operator's Program is required exactly when they are different people (as before)
        assertThat(ControlStepsFields.operatorProgramRequired(facilitators, operators)).isEqualTo(!expected);
    }

    @Test
    void operatorProgram_requiredOnlyForDifferentPeople_untilTheBusinessSaysAlways() {
        assertThat(ControlStepsFields.OPERATOR_PROGRAM_ALWAYS_REQUIRED).isFalse();
        assertThat(ControlStepsFields.operatorProgramRequired(true)).isTrue();
        assertThat(ControlStepsFields.operatorProgramRequired(false)).isFalse();
        assertThat(ControlStepsFields.operatorProgramRequired("fac@x.kz", "fac@x.kz")).isFalse();
        assertThat(ControlStepsFields.operatorProgramRequired("fac@x.kz", "op@x.kz")).isTrue();
    }

    @Test
    void lists_followTheSameRule_andAnElementMayHoldSeveralAddresses() {
        assertThat(ControlStepsFields.onePerson(List.of("a@x.kz", "B@x.kz"), List.of(" b@x.kz"))).isTrue();
        assertThat(ControlStepsFields.onePerson(List.of("a@x.kz;b@x.kz"), List.of("b@x.kz", "a@x.kz"))).isTrue();
        assertThat(ControlStepsFields.onePerson(List.of("a@x.kz"), List.of("a@x.kz", "c@x.kz"))).isFalse();
        assertThat(ControlStepsFields.split(List.of("a@x.kz"), List.of("c@x.kz"))).isTrue();
    }

    @Test
    void lists_emptyOrMissing() {
        assertThat(ControlStepsFields.onePerson(List.of("a@x.kz"), null)).isTrue();
        assertThat(ControlStepsFields.onePerson(List.of("a@x.kz"), List.of())).isTrue();
        assertThat(ControlStepsFields.onePerson(List.of("a@x.kz"), Arrays.asList(null, " "))).isTrue();
        assertThat(ControlStepsFields.onePerson(null, List.of("op@x.kz"))).isFalse();
        assertThat(ControlStepsFields.onePerson(Arrays.asList((String) null), List.of("op@x.kz"))).isFalse();
    }
}
