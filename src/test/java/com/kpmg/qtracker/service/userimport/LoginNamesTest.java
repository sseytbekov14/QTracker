package com.kpmg.qtracker.service.userimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Logins from "Last, First" display names (all names here are made up). */
class LoginNamesTest {

    static Stream<Arguments> logins() {
        return Stream.of(
                Arguments.of("Testov, Aidar", "atestov"),
                Arguments.of("Samplova, Irina", "isamplova"),
                Arguments.of("Smith, Mary Ann", "msmith"),
                Arguments.of("De La Cruz, Juan", "jdelacruz"),
                Arguments.of("O'Brien, Pat", "pobrien"),
                Arguments.of("Al-Sayed, Omar", "oalsayed"),
                Arguments.of("St. John, Eve", "estjohn"),
                Arguments.of("O’Neil, Kim", "koneil"),
                Arguments.of("  Spaced ,   Lena  ", "lspaced"),
                Arguments.of("UPPER, CASE", "cupper"),
                // Diacritics
                Arguments.of("Müller, Jürgen", "jmuller"),
                Arguments.of("Ñúñez, Élodie", "enunez"),
                Arguments.of("Kowalczyk-Łaska, Zoë", "zkowalczyklaska"),
                Arguments.of("Straße, Ågot", "astrasse"),
                // Cyrillic, also Kazakh letters
                Arguments.of("Иванов, Пётр", "pivanov"),
                Arguments.of("Щукина, Юлия", "yshchukina"),
                Arguments.of("Жұмабаев, Әлия", "azhumabaev"),
                Arguments.of("Қасымова, Іңкәр", "ikasymova"),
                Arguments.of("Лебедь, Ёжик", "elebed"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("logins")
    void loginOfADisplayName(String displayName, String login) {
        LoginNames.Base base = LoginNames.base(displayName);

        assertThat(base.error()).isNull();
        assertThat(base.login()).isEqualTo(login);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("Nocomma Person", "no comma"),
                Arguments.of("", "no comma"),
                Arguments.of("., Anna", "the surname is \".\""),
                Arguments.of(" . , Anna", "the surname is \".\""),
                Arguments.of(", Anna", "empty surname"),
                Arguments.of("- ' ., Anna", "empty surname"),
                Arguments.of("Person, ", "empty first name"),
                Arguments.of("Person, .", "empty first name"),
                Arguments.of("王, Wei", "no Latin spelling: U+738B"));
    }

    @ParameterizedTest(name = "[{0}] -> {1}")
    @MethodSource("refusals")
    void noLogin_withTheReason(String displayName, String reason) {
        LoginNames.Base base = LoginNames.base(displayName);

        assertThat(base.ok()).isFalse();
        assertThat(base.login()).isNull();
        assertThat(base.error()).contains(reason);
    }

    @Test
    void noName_isNoComma() {
        assertThat(LoginNames.base(null).error()).contains("no comma");
    }

    @Test
    void sameLogin_getsSuffixesByDisplayNameThenRow() {
        List<LoginNames.Candidate> candidates = List.of(
                new LoginNames.Candidate(7, "Smith, Maria", "msmith"),
                new LoginNames.Candidate(3, "Smith, Mark", "msmith"),
                new LoginNames.Candidate(5, "Smith, Mary Ann", "msmith"),
                new LoginNames.Candidate(4, "Testov, Aidar", "atestov"));

        assertThat(LoginNames.assign(candidates))
                .containsEntry(7, "msmith")
                .containsEntry(3, "msmith2")
                .containsEntry(5, "msmith3")
                .containsEntry(4, "atestov")
                .hasSize(4);
    }

    @Test
    void suffixes_doNotDependOnTheOrderOfTheFile() {
        List<LoginNames.Candidate> first = List.of(
                new LoginNames.Candidate(2, "Smith, Mark", "msmith"),
                new LoginNames.Candidate(3, "Smith, Maria", "msmith"));
        List<LoginNames.Candidate> reordered = List.of(
                new LoginNames.Candidate(2, "Smith, Maria", "msmith"),
                new LoginNames.Candidate(3, "Smith, Mark", "msmith"));

        assertThat(LoginNames.assign(first)).containsEntry(3, "msmith").containsEntry(2, "msmith2");
        assertThat(LoginNames.assign(reordered)).containsEntry(2, "msmith").containsEntry(3, "msmith2");
    }

    @Test
    void sameDisplayName_isOrderedByRow() {
        List<LoginNames.Candidate> candidates = List.of(
                new LoginNames.Candidate(9, "Smith, Mark", "msmith"),
                new LoginNames.Candidate(4, "smith,  mark", "msmith"));

        assertThat(LoginNames.assign(candidates)).containsEntry(4, "msmith").containsEntry(9, "msmith2");
    }
}
