package com.kpmg.qtracker.service.userimport;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AdminAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The last step of a clean slate: the made-up accounts the Flyway migration V2 adds to every database
 * (fac1@qtracker.local, ...) leave a freshly created local QTracker database, so that only the imported
 * people remain. Refused for any other database and as soon as the database holds a control. A dry run
 * by default; with apply the accounts are deleted, each with an entry in the audit trail.
 */
@Service
@RequiredArgsConstructor
public class SeedUsersRemoval {

    /** The accounts of V2__seed_users_bcrypt.sql. */
    public static final List<String> SEED_MAILS = List.of(
            "fac1@qtracker.local", "fac2@qtracker.local",
            "op1@qtracker.local", "op2@qtracker.local",
            "admin@qtracker.local",
            "soqm1@qtracker.local", "soqm2@qtracker.local",
            "po1@qtracker.local", "po2@qtracker.local");

    static final String ACTOR_MAIL = "system";

    private final UserRepository userRepository;
    private final ControlRepository controlRepository;
    private final AdminAuditService auditService;

    public record Report(boolean applied, String refusal, List<String> removed, long otherUsers) {

        public boolean refused() {
            return refusal != null;
        }

        public String render() {
            StringBuilder out = new StringBuilder();
            out.append("Seed users removal: ");
            if (refused()) {
                return out.append("REFUSED, nothing written: ").append(refusal).append(System.lineSeparator()).toString();
            }
            out.append(applied ? "APPLIED" : "DRY RUN, nothing written").append(System.lineSeparator());
            removed.forEach(mail -> out.append("  ").append(mail).append(System.lineSeparator()));
            out.append(applied ? "removed: " : "would remove: ").append(removed.size())
                    .append(", other users kept: ").append(otherUsers).append(System.lineSeparator());
            if (!applied && !removed.isEmpty()) {
                out.append("Run again with --apply to remove them.").append(System.lineSeparator());
            }
            return out.toString();
        }
    }

    /**
     * @param jdbcUrl the address of the database the application is connected to
     * @param actor   who runs it (the operating-system user), for the audit trail
     */
    @Transactional
    public Report run(boolean apply, String jdbcUrl, String actor) {
        String refusal = LocalTestDatabase.refusal(jdbcUrl).orElse(null);
        if (refusal == null) {
            long controls = controlRepository.count();
            if (controls > 0) {
                refusal = "the database holds " + controls + " control(s); seed users are removed only before any control";
            }
        }
        if (refusal != null) {
            return new Report(false, refusal, List.of(), 0);
        }

        List<User> seeds = new ArrayList<>();
        for (String mail : SEED_MAILS) {
            userRepository.findByMail(mail).ifPresent(seeds::add);
        }
        long others = userRepository.count() - seeds.size();
        List<String> mails = seeds.stream().map(User::getMail).toList();
        if (!apply) {
            return new Report(false, null, mails, others);
        }

        userRepository.deleteAll(seeds);
        userRepository.flush();
        String actorName = "Seed users removal" + (actor == null || actor.isBlank() ? "" : " (" + actor + ")");
        for (String mail : mails) {
            auditService.logActionWithChanges(ACTOR_MAIL, actorName, "USER_DELETE", null,
                    "Removed seed user for " + mail, "mail", "mail=" + mail, "-");
        }
        return new Report(true, null, mails, others);
    }
}
