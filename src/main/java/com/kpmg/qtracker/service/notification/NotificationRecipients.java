package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** One address (lower case, trimmed) and the users of it who get the notification. */
    public record Person(String email, List<User> users) {
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
            people.forEach((email, users) -> out.add(new Person(email, List.copyOf(users))));
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
            List<User> users = people.computeIfAbsent(user.getMail().trim().toLowerCase(Locale.ROOT), key -> new ArrayList<>());
            if (users.stream().noneMatch(known -> known.getId().equals(user.getId()))) {
                users.add(user);
            }
            return true;
        }
    }
}
