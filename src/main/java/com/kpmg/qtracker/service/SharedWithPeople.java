package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.util.RoleDisplayMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Control Shared With as View Control shows it: the stored addresses in stored order, a repeated one (ignoring
 * case) once, each with the user's name and what the place gives them ({@link AccessPolicy#sharedAccess}). An
 * address without a QTracker user stays in the list as written, so that nobody drops it from the control unseen.
 */
public final class SharedWithPeople {

    /**
     * One person of the list.
     *
     * @param mail        the user's address, or the stored one when there is no such user
     * @param name        the user's name, or the address when there is none
     * @param access      what the place gives them; null when it could not be told
     * @param note        the mark shown at the person ({@link RoleDisplayMapper#sharedNote}), or null
     * @param saveRefusal why the server would refuse to save them in the field ({@link AccessPolicy#assignmentRefusal},
     *                    "is not a QTracker user", ...), or null when it saves them
     */
    public record Person(String mail, String name, AccessPolicy.SharedAccess access, String note, String saveRefusal) {

        /** A name to show above the address (not just the address again). */
        public boolean named() {
            return name != null && !name.equalsIgnoreCase(mail);
        }

        /** The access as the page carries it ("VIEWS", "DISABLED", ...), or an empty string. */
        public String accessName() {
            return access == null ? "" : access.name();
        }
    }

    private SharedWithPeople() {
    }

    /**
     * @param stored     the addresses of the field, as stored
     * @param kdnControl whether the control is a KDN control (the save check)
     * @param users      the user with an address (ignoring case), if any
     * @param access     what a place in Shared With gives a user on this control
     */
    public static List<Person> describe(List<String> stored, boolean kdnControl,
                                        Function<String, Optional<User>> users,
                                        Function<User, AccessPolicy.SharedAccess> access) {
        if (stored == null || stored.isEmpty()) {
            return Collections.emptyList();
        }
        List<Person> people = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : stored) {
            String mail = raw == null ? "" : raw.trim();
            if (mail.isEmpty() || !seen.add(mail.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Optional<User> user = users.apply(mail);
            people.add(user.isPresent()
                    ? person(user.get(), access.apply(user.get()), kdnControl)
                    : unknown(mail));
        }
        return Collections.unmodifiableList(people);
    }

    /** A user as the field shows them, in the list or offered to it. */
    public static Person person(User user, AccessPolicy.SharedAccess access, boolean kdnControl) {
        String mail = user.getMail() == null ? "" : user.getMail().trim();
        String name = user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? mail : user.getDisplayName().trim();
        return new Person(mail, name, access, RoleDisplayMapper.sharedNote(access),
                AccessPolicy.assignmentRefusal(AccessPolicy.Subject.of(user), AccessPolicy.Slot.SHARED_WITH, kdnControl)
                        .orElse(null));
    }

    private static Person unknown(String mail) {
        AccessPolicy.SharedAccess access = AccessPolicy.SharedAccess.NOT_A_USER;
        return new Person(mail, mail, access, RoleDisplayMapper.sharedNote(access),
                AccessPolicy.assignmentRefusal(null, AccessPolicy.Slot.SHARED_WITH, false).orElse(null));
    }
}
