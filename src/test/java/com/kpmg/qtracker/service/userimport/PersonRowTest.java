package com.kpmg.qtracker.service.userimport;

import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Role, Access and Status of a line in QTracker terms (made-up people only). */
class PersonRowTest {

    @ParameterizedTest(name = "{0} / {1} -> {2} {3}")
    @CsvSource(delimiter = '|', emptyValue = "", value = {
            "SoQM Team|SoQM Team|SOQM|ALL",
            "soqm  team||SOQM|ALL",
            "KDN|All KDN Controls, Read only|READ_ONLY|KDN",
            "kdn||READ_ONLY|KDN",
            "User|My Controls, Edit|PARTICIPANT|OWN",
            "User|My Controls, Read Only|READ_ONLY|OWN",
            "User|All Controls, Edit|PARTICIPANT|ALL",
            "user|  all controls ,  read   only |READ_ONLY|ALL"
    })
    void roleAndAccess_giveTheLevelAndScope(String role, String access, AccessLevel level, AccessScope scope) {
        PersonRow.Profile profile = PersonRow.profile(role, access);

        assertThat(profile.error()).isNull();
        assertThat(profile.profile().level()).isEqualTo(level);
        assertThat(profile.profile().scope()).isEqualTo(scope);
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(delimiter = '|', emptyValue = "", value = {
            "Master|My Controls, Edit|unknown Role \"Master\"",
            "Read Only||unknown Role \"Read Only\"",
            "||empty Role",
            "SoQM Team|My Controls, Edit|does not fit the role SoQM Team",
            "KDN|My Controls, Edit|does not fit the role KDN",
            "User||for the role User is not",
            "User|Edit|for the role User is not",
            "User|My Controls, Edit, Extra|for the role User is not",
            "User|Some Controls, Edit|unknown visibility \"Some Controls\"",
            "User|My Controls, Write|unknown access \"Write\"",
            "User|All KDN Controls, Read only|unknown visibility \"All KDN Controls\""
    })
    void unknownValues_areErrors_withoutGuessing(String role, String access, String reason) {
        PersonRow.Profile profile = PersonRow.profile(role, access);

        assertThat(profile.profile()).isNull();
        assertThat(profile.error()).contains(reason);
    }

    @Test
    void aPerson_activeOrNot() {
        PersonRow active = PersonRow.of(record(2, "Aidar", "Testov, Aidar", "User", "My Controls, Edit", "Active"));
        PersonRow inactive = PersonRow.of(record(3, "Irina", "Samplova, Irina", "KDN", "", "Inactive"));
        PersonRow blank = PersonRow.of(record(4, "Pat", "O'Brien, Pat", "SoQM Team", "", ""));

        assertThat(active.kind()).isEqualTo(PersonRow.Kind.PERSON);
        assertThat(active.line()).isEqualTo(2);
        assertThat(active.displayName()).isEqualTo("Testov, Aidar");
        assertThat(active.profile().role()).isEqualTo(UserRole.USER);
        assertThat(active.enabled()).isTrue();
        assertThat(inactive.enabled()).isFalse();
        assertThat(inactive.profile().role()).isEqualTo(UserRole.KDN);
        assertThat(blank.kind()).isEqualTo(PersonRow.Kind.PERSON);
        assertThat(blank.enabled()).isFalse();
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(delimiter = '|', emptyValue = "", value = {
            "svc-qt-test|svc-qt-test",
            "Robot|qt-svc-sync",
            "QT SVC|Account, Service",
            "Aidar|IT Tester 2",
            "it-tester|Testov, Aidar"
    })
    void serviceAccounts_areKeptApart(String name, String user) {
        PersonRow row = PersonRow.of(record(7, name, user, "User", "My Controls, Edit", "Active"));

        assertThat(row.kind()).isEqualTo(PersonRow.Kind.SERVICE_ACCOUNT);
        assertThat(row.error()).contains("service account");
    }

    @Test
    void namesThatOnlyLookAlike_areNotServiceAccounts() {
        assertThat(PersonRow.of(record(2, "Svcetlana", "Kitester, Svcetlana", "KDN", "", "Active")).kind())
                .isEqualTo(PersonRow.Kind.PERSON);
    }

    @Test
    void errors_keepTheLineAndTheReason() {
        PersonRow unknownRole = PersonRow.of(record(5, "Aidar", "Testov, Aidar", "Master", "", "Active"));
        PersonRow noUser = PersonRow.of(record(6, "Aidar", "", "User", "My Controls, Edit", "Active"));
        PersonRow badRecord = PersonRow.of(new UsersCsv.Record(8, Map.of(UsersCsv.USER, "Testov, Aidar"), 2,
                "2 fields where the header has 5"));

        assertThat(unknownRole.kind()).isEqualTo(PersonRow.Kind.ERROR);
        assertThat(unknownRole.line()).isEqualTo(5);
        assertThat(unknownRole.error()).contains("unknown Role \"Master\"");
        assertThat(noUser.error()).isEqualTo("empty User");
        assertThat(badRecord.error()).isEqualTo("2 fields where the header has 5");
    }

    @Test
    void anOverlongValue_isShortenedInTheReason() {
        PersonRow.Profile profile = PersonRow.profile("x".repeat(100), "");

        assertThat(profile.error()).contains("x".repeat(40) + "…\"").doesNotContain("x".repeat(41));
    }

    private static UsersCsv.Record record(int line, String name, String user, String role, String access, String status) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(UsersCsv.NAME, name);
        values.put(UsersCsv.USER, user);
        values.put(UsersCsv.ROLE, role);
        values.put(UsersCsv.ACCESS, access);
        values.put(UsersCsv.STATUS, status);
        return new UsersCsv.Record(line, values, 5, null);
    }
}
