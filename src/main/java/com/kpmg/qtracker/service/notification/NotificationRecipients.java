package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who gets a notification, from the addresses of the control's fields (NOTIFICATIONS_v5, decisions 3 and 8):
 * <ul>
 * <li>one e-mail per address, compared without case; every user of that address gets the in-app notification;</li>
 * <li>nothing (neither e-mail nor in-app) for an inactive user, the role KDN ({@link AccessPolicy#hasNotifications})
 * and a Read Only user;</li>
 * <li>an address of no user, or a user without an address, is left out with a warning (control, role);</li>
 * <li>SoQM Admin Team: the people of the field SoQM Team / Delegate; when it names nobody who gets it, every active
 * SoQM Admin Team user.</li>
 * </ul>
 */
@Component
public class NotificationRecipients {

    public static final String FACILITATOR = "Facilitator";
    public static final String CONTROL_OPERATOR = "Control Operator";
    public static final String SOQM_ADMIN_TEAM = "SoQM Admin Team";
    public static final String PROCESS_OWNER = "Process Owner";

    private final UserRepository userRepository;

    public NotificationRecipients(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * One address (lower case, trimmed), the users of it who get the notification, and the roles of the control
     * through which the address came (in the order the roles were added).
     */
    public record Person(String email, List<User> users, List<String> roles) {

        /**
         * The name in "Dear {NAME},": the fields of a control hold e-mail addresses, and the control's page shows the
         * user's name for them (users.displayname), so the name is that of the user of the address - of the oldest
         * one when two users share it (they all get notifications, so all are active). No name: the person's role on
         * the control.
         */
        public String name() {
            return users.stream()
                    .min(Comparator.comparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(User::getDisplayName)
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim)
                    .orElse(roles.isEmpty() ? "" : roles.get(0));
        }

        /**
         * {ROLES} of T1 and T2: the person's roles among Facilitator, Control Operator and Process Owner, in that
         * order - "Facilitator", "Facilitator and Control Operator", "Facilitator, Control Operator and Process Owner".
         */
        public String rolesText() {
            List<String> named = List.of(FACILITATOR, CONTROL_OPERATOR, PROCESS_OWNER).stream()
                    .filter(roles::contains).toList();
            if (named.size() <= 1) {
                return named.isEmpty() ? String.join(" and ", roles) : named.get(0);
            }
            return String.join(", ", named.subList(0, named.size() - 1)) + " and " + named.get(named.size() - 1);
        }
    }

    /** Whether a user gets notifications at all: active, not KDN, not Read Only. */
    public static boolean receives(User user) {
        return user != null
                && Boolean.TRUE.equals(user.getEnabled())
                && AccessPolicy.hasNotifications(AccessPolicy.Subject.of(user))
                && user.getAccessLevel() != AccessLevel.READ_ONLY;
    }

    /** The people of a notification; add the fields of each role, then {@link #people()}. */
    public Builder forControl(String controlId) {
        return new Builder(controlId);
    }

    public final class Builder {

        private final String controlId;
        private final Map<String, List<User>> people = new LinkedHashMap<>();
        private final Map<String, Set<String>> roles = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        private Builder(String controlId) {
            this.controlId = controlId;
        }

        /** The people of a role's field. */
        public Builder add(String role, List<String> addresses) {
            addAddresses(role, addresses);
            return this;
        }

        /** SoQM Admin Team: the field SoQM Team / Delegate, or every active SoQM Admin Team user. */
        public Builder addSoqmTeam(List<String> addresses) {
            if (addAddresses(SOQM_ADMIN_TEAM, addresses) == 0) {
                for (User user : userRepository.findByAccessLevelAndEnabledTrueOrderByIdAsc(AccessLevel.SOQM)) {
                    addUser(SOQM_ADMIN_TEAM, user);
                }
            }
            return this;
        }

        public List<Person> people() {
            List<Person> out = new ArrayList<>();
            people.forEach((email, users) -> out.add(new Person(email, List.copyOf(users), List.copyOf(roles.get(email)))));
            return out;
        }

        /** What was left out and why (an address of nobody, a user without an address), for the log. */
        public List<String> warnings() {
            return List.copyOf(warnings);
        }

        /** How many addresses of the field reached someone who gets the notification. */
        private int addAddresses(String role, List<String> addresses) {
            int reached = 0;
            if (addresses == null) {
                return 0;
            }
            for (String address : addresses) {
                if (address == null || address.isBlank()) {
                    continue;
                }
                List<User> users = userRepository.findAllByMail(address.trim());
                if (users.isEmpty()) {
                    warnings.add("control " + controlId + ": no user for the " + role + " address " + address.trim());
                    continue;
                }
                for (User user : users) {
                    if (addUser(role, user)) {
                        reached++;
                    }
                }
            }
            return reached;
        }

        private boolean addUser(String role, User user) {
            if (user.getMail() == null || user.getMail().isBlank()) {
                warnings.add("control " + controlId + ": the " + role + " user " + user.getId() + " has no e-mail");
                return false;
            }
            if (!receives(user)) {
                return false;
            }
            String email = user.getMail().trim().toLowerCase(Locale.ROOT);
            List<User> users = people.computeIfAbsent(email, key -> new ArrayList<>());
            if (users.stream().noneMatch(known -> known.getId().equals(user.getId()))) {
                users.add(user);
            }
            roles.computeIfAbsent(email, key -> new LinkedHashSet<>()).add(role);
            return true;
        }
    }
}
