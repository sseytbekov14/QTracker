package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AccessPolicy.SharedAccess;
import com.kpmg.qtracker.service.SharedWithPeople.Person;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SharedWithPeopleTest {

    private static final Map<String, User> USERS = Arrays.stream(new User[] {
            user("jane@x.kz", "Jane Doe", AccessLevel.PARTICIPANT, AccessScope.OWN, true),
            user("bob@x.kz", "Bob Smith", AccessLevel.READ_ONLY, AccessScope.OWN, true),
            user("gone@x.kz", "Gone Person", AccessLevel.PARTICIPANT, AccessScope.OWN, false),
            user("kdn@x.kz", "Kdn Viewer", AccessLevel.READ_ONLY, AccessScope.KDN, true),
            user("noname@x.kz", " ", AccessLevel.READ_ONLY, AccessScope.OWN, true),
    }).collect(Collectors.toMap(u -> u.getMail().toLowerCase(Locale.ROOT), u -> u));

    private static User user(String mail, String name, AccessLevel level, AccessScope scope, boolean enabled) {
        User user = new User();
        user.setMail(mail);
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setEnabled(enabled);
        return user;
    }

    private static final Function<String, Optional<User>> LOOKUP =
            mail -> Optional.ofNullable(USERS.get(mail.trim().toLowerCase(Locale.ROOT)));

    /** What the policy gives each one on a control in this status, not shared with them before. */
    private static Function<User, SharedAccess> on(String status, boolean kdnControl) {
        return user -> AccessPolicy.sharedAccess(AccessPolicy.Subject.of(user),
                new AccessPolicy.ControlFacts(status, kdnControl, false, false, false, false, false));
    }

    private static List<Person> describe(List<String> stored, String status, boolean kdnControl) {
        return SharedWithPeople.describe(stored, kdnControl, LOOKUP, on(status, kdnControl));
    }

    @Test
    void empty_orNothingStored_isNobody() {
        assertThat(describe(null, "IN_PROGRESS", false)).isEmpty();
        assertThat(describe(List.of(), "IN_PROGRESS", false)).isEmpty();
        assertThat(describe(Arrays.asList(" ", "", null), "IN_PROGRESS", false)).isEmpty();
    }

    @Test
    void repeatedAddress_ignoringCase_isOnePerson_inStoredOrder() {
        List<Person> people = describe(List.of("bob@x.kz", " JANE@X.KZ ", "Bob@X.kz", "jane@x.kz"), "IN_PROGRESS", false);

        assertThat(people).extracting(Person::mail).containsExactly("bob@x.kz", "jane@x.kz");
        assertThat(people).extracting(Person::name).containsExactly("Bob Smith", "Jane Doe");
        assertThat(people).extracting(Person::access).containsOnly(SharedAccess.VIEWS);
        assertThat(people).extracting(Person::note).containsOnlyNulls();
        assertThat(people).extracting(Person::saveRefusal).containsOnlyNulls();
        assertThat(people).allMatch(Person::named);
    }

    @Test
    void disabledUser_staysInTheList_markedDisabled_andSaves() {
        Person gone = describe(List.of("gone@x.kz"), "IN_PROGRESS", false).get(0);

        assertThat(gone.name()).isEqualTo("Gone Person");
        assertThat(gone.access()).isEqualTo(SharedAccess.DISABLED);
        assertThat(gone.note()).isEqualTo("Disabled");
        assertThat(gone.saveRefusal()).isNull();
    }

    @Test
    void addressWithoutUser_staysAsWritten_markedNotInTheSystem_andCannotBeSaved() {
        Person ghost = describe(List.of("Ghost@External.kz"), "IN_PROGRESS", false).get(0);

        assertThat(ghost.mail()).isEqualTo("Ghost@External.kz");
        assertThat(ghost.name()).isEqualTo("Ghost@External.kz");
        assertThat(ghost.named()).isFalse();
        assertThat(ghost.access()).isEqualTo(SharedAccess.NOT_A_USER);
        assertThat(ghost.note()).isEqualTo("Not in the system");
        assertThat(ghost.saveRefusal()).isEqualTo("is not a QTracker user");
    }

    @Test
    void kdnUser_onAControlThatIsNotKdn_willNotSeeIt_andCannotBeSaved() {
        Person kdn = describe(List.of("kdn@x.kz"), "REVIEW", false).get(0);

        assertThat(kdn.access()).isEqualTo(SharedAccess.NOT_SEEN);
        assertThat(kdn.note()).isEqualTo("Will not see this control");
        assertThat(kdn.saveRefusal()).isEqualTo("sees only KDN controls and cannot be added to this control");

        Person onKdnControl = describe(List.of("kdn@x.kz"), "REVIEW", true).get(0);
        assertThat(onKdnControl.access()).isEqualTo(SharedAccess.VIEWS);
        assertThat(onKdnControl.note()).isNull();
        assertThat(onKdnControl.saveRefusal()).isNull();
    }

    @Test
    void draft_myControlsUsersSeeItOnceInitiated() {
        List<Person> people = describe(List.of("bob@x.kz", "jane@x.kz"), "DRAFT", false);

        assertThat(people).extracting(Person::access).containsOnly(SharedAccess.AFTER_INITIATION);
        assertThat(people).extracting(Person::note).containsOnly("Will see it once the control is initiated");
        assertThat(people).extracting(Person::saveRefusal).containsOnlyNulls();
    }

    @Test
    void userWithoutName_isShownByAddress() {
        Person person = describe(List.of("noname@x.kz"), "IN_PROGRESS", false).get(0);

        assertThat(person.name()).isEqualTo("noname@x.kz");
        assertThat(person.named()).isFalse();
        assertThat(person.access()).isEqualTo(SharedAccess.VIEWS);
    }

    @Test
    void accessUnknown_hasNoMark() {
        List<Person> people = SharedWithPeople.describe(List.of("jane@x.kz"), false, LOOKUP, user -> null);

        assertThat(people.get(0).access()).isNull();
        assertThat(people.get(0).accessName()).isEmpty();
        assertThat(people.get(0).note()).isNull();
    }
}
