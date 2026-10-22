package com.kpmg.qtracker.service.userimport;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.security.LoginNameResolver;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Gives people their real e-mail before SSO (the user import gave them {@code <login>@<service domain>}): a CSV
 * with the columns User (the login, or the display name as in the Admin Panel) and Email (the corporate address
 * they sign in with at Microsoft). A dry run unless applied; all or nothing - any problem in the file refuses the
 * whole run (a person on two lines, an address on two lines or already someone else's, an address on the service
 * domain, an unknown or ambiguous person, a broken line). Running it again changes nothing (already that
 * address). Also for people who have signed in before; the stored Entra object id stays. Each change is the Admin
 * Panel's ({@link UserService#changeEmail}, 2026-10-22): the person's places in the controls - Facilitator, Control
 * Operator, SoQM Team / Delegate, Process Owner, Control Shared With - follow the new address in the same
 * transaction, and the report says how many controls moved. Each change is also an audit entry with the address
 * before and after.
 *
 * <p>The console report names line numbers and user ids only; the list with the addresses
 * ({@code update_emails_<date>.csv}) is written next to the file (migration\, ignored by git).
 */
@Service
public class UserEmailUpdate {

    static final String ACTOR_MAIL = "system";
    static final String AUDIT_ACTION = "USER_EMAIL_UPDATE";
    static final String USER = "User";
    static final String EMAIL = "Email";

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public enum Outcome {
        CHANGE("will change"),
        UNCHANGED("already this address"),
        ERROR("error");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One line of the file: the person found (null when not), the addresses, what happens or why not. */
    public record Line(int line, Outcome outcome, Long userId, String oldMail, String newMail, String reason,
                       int moved) {
    }

    public record Report(boolean applied, String refusal, List<Line> lines, Path listFile) {

        public boolean refused() {
            return refusal != null;
        }

        public long count(Outcome outcome) {
            return lines.stream().filter(line -> line.outcome() == outcome).count();
        }

        /** For the console: counts, then each line by number and user id - no address, no name. */
        public String render() {
            StringBuilder out = new StringBuilder("E-mail update: ");
            if (refused()) {
                out.append("REFUSED, nothing written: ").append(refusal).append('\n');
            } else {
                out.append(applied ? "APPLIED" : "DRY RUN (add --apply to write)").append('\n');
            }
            out.append("  change ").append(count(Outcome.CHANGE)).append(", already right ")
                    .append(count(Outcome.UNCHANGED)).append(", errors ").append(count(Outcome.ERROR)).append('\n');
            for (Line line : lines) {
                out.append("  line ").append(line.line()).append(": ")
                        .append(line.userId() == null ? "-" : "user " + line.userId()).append(", ")
                        .append(line.outcome() == Outcome.CHANGE && applied ? "changed" : line.outcome().label());
                if (line.outcome() == Outcome.CHANGE && applied) {
                    out.append(", assignments moved ").append(line.moved());
                }
                if (line.reason() != null) {
                    out.append(" (").append(line.reason()).append(')');
                }
                out.append('\n');
            }
            if (listFile != null) {
                out.append("  list with the addresses: ").append(listFile).append('\n');
            }
            return out.toString();
        }
    }

    private final UserRepository userRepository;
    private final UserService userService;
    private final AdminAuditService auditService;
    private final LoginNameResolver loginNames;

    public UserEmailUpdate(UserRepository userRepository, UserService userService, AdminAuditService auditService,
                           LoginNameResolver loginNames) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.auditService = auditService;
        this.loginNames = loginNames;
    }

    /**
     * @param actor who runs it (the operating-system user), for the audit trail
     * @param today the date in the name of the list
     */
    @Transactional
    public Report run(Path file, boolean apply, String actor, LocalDate today) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            return new Report(false, "the file cannot be read: " + file, List.of(), null);
        }
        UsersCsv.Table table = UsersCsv.read(bytes, List.of(USER, EMAIL), List.of());
        if (!table.ok()) {
            return new Report(false, table.error(), List.of(), null);
        }

        List<Line> lines = plan(table.records());
        Path list = writeList(file, lines, false, today);
        String refusal = refusal(lines);
        if (refusal != null) {
            return new Report(false, refusal, lines, list);
        }
        if (!apply) {
            return new Report(false, null, lines, list);
        }

        String actorName = "E-mail update" + (actor == null || actor.isBlank() ? "" : " (" + actor + ")");
        List<Line> done = new ArrayList<>();
        for (Line line : lines) {
            if (line.outcome() != Outcome.CHANGE) {
                done.add(line);
                continue;
            }
            int moved = userService.changeEmail(line.userId(), line.newMail());
            auditService.logActionWithChanges(ACTOR_MAIL, actorName, AUDIT_ACTION, null,
                    "Changed email from " + line.oldMail() + " to " + line.newMail() + " for user id " + line.userId(),
                    "mail", "mail=" + line.oldMail(), "mail=" + line.newMail());
            done.add(new Line(line.line(), line.outcome(), line.userId(), line.oldMail(), line.newMail(), null, moved));
        }
        return new Report(true, null, done, writeList(file, done, true, today));
    }

    private List<Line> plan(List<UsersCsv.Record> records) {
        List<User> users = userRepository.findAll();
        List<Line> lines = new ArrayList<>();
        Map<Long, Integer> lineOfUser = new HashMap<>();
        Map<String, Integer> lineOfMail = new HashMap<>();
        for (UsersCsv.Record record : records) {
            String typedUser = record.get(USER);
            String newMail = record.get(EMAIL).toLowerCase(Locale.ROOT);
            if (record.error() != null) {
                lines.add(error(record.line(), null, null, newMail, record.error()));
                continue;
            }
            if (typedUser.isEmpty() || newMail.isEmpty()) {
                lines.add(error(record.line(), null, null, newMail, "User and Email are both needed"));
                continue;
            }
            if (!ADDRESS.matcher(newMail).matches()) {
                lines.add(error(record.line(), null, null, newMail, "Email is not an e-mail address"));
                continue;
            }
            List<User> found = find(users, typedUser);
            if (found.isEmpty()) {
                // A login whose imported address is gone because this line was applied before: the person who
                // has the address now
                found = users.stream()
                        .filter(user -> user.getMail() != null && user.getMail().trim().equalsIgnoreCase(newMail))
                        .toList();
            }
            if (found.size() != 1) {
                lines.add(error(record.line(), null, null, newMail, found.isEmpty()
                        ? "no user with this login or name" : found.size() + " users have this name"));
                continue;
            }
            User user = found.get(0);
            String oldMail = user.getMail();
            Integer earlierUser = lineOfUser.putIfAbsent(user.getId(), record.line());
            Integer earlierMail = lineOfMail.putIfAbsent(newMail, record.line());
            Optional<User> owner = users.stream()
                    .filter(other -> other.getMail() != null && other.getMail().trim().equalsIgnoreCase(newMail))
                    .findFirst();
            if (earlierUser != null) {
                lines.add(error(record.line(), user.getId(), oldMail, newMail, "the same user as line " + earlierUser));
            } else if (earlierMail != null) {
                lines.add(error(record.line(), user.getId(), oldMail, newMail, "the same Email as line " + earlierMail));
            } else if (loginNames.isServiceAddress(newMail)) {
                lines.add(error(record.line(), user.getId(), oldMail, newMail,
                        "Email is on the service domain " + loginNames.domain() + ", not a corporate address"));
            } else if (owner.isPresent() && !owner.get().getId().equals(user.getId())) {
                lines.add(error(record.line(), user.getId(), oldMail, newMail,
                        "Email already belongs to user " + owner.get().getId()));
            } else if (oldMail != null && oldMail.trim().equalsIgnoreCase(newMail)) {
                lines.add(new Line(record.line(), Outcome.UNCHANGED, user.getId(), oldMail, newMail, null, 0));
            } else {
                lines.add(new Line(record.line(), Outcome.CHANGE, user.getId(), oldMail, newMail, null, 0));
            }
        }
        return lines;
    }

    /**
     * The person a value of User stands for: a login (no spaces, no "@") is first the address the import gave,
     * then a display name; anything else is a display name (any case, spaces as typed collapsed).
     */
    private List<User> find(List<User> users, String typed) {
        String value = SPACES.matcher(typed.strip()).replaceAll(" ");
        if (!value.contains(" ") && !value.contains("@")) {
            String mail = loginNames.mailOf(value);
            List<User> byLogin = users.stream()
                    .filter(user -> user.getMail() != null && user.getMail().trim().equalsIgnoreCase(mail)).toList();
            if (!byLogin.isEmpty()) {
                return byLogin;
            }
        }
        return users.stream()
                .filter(user -> user.getDisplayName() != null
                        && SPACES.matcher(user.getDisplayName().strip()).replaceAll(" ").equalsIgnoreCase(value))
                .toList();
    }

    private static String refusal(List<Line> lines) {
        if (lines.isEmpty()) {
            return "the file has no lines";
        }
        long errors = lines.stream().filter(line -> line.outcome() == Outcome.ERROR).count();
        return errors == 0 ? null : errors + " line(s) with errors; fix the file, nothing is changed until all are right";
    }

    private static Line error(int line, Long userId, String oldMail, String newMail, String reason) {
        return new Line(line, Outcome.ERROR, userId, oldMail, newMail, reason, 0);
    }

    // update_emails_<date>.csv next to the file: line, user id, the addresses, the result
    private static Path writeList(Path file, List<Line> lines, boolean applied, LocalDate today) {
        Path target = file.toAbsolutePath().getParent().resolve("update_emails_" + today + ".csv");
        StringBuilder csv = new StringBuilder("﻿Line,User id,Old email,New email,Result\r\n");
        for (Line line : lines) {
            String result = line.outcome() == Outcome.CHANGE && applied ? "changed" : line.outcome().label()
                    + (line.reason() == null ? "" : ": " + line.reason());
            csv.append(line.line()).append(',').append(line.userId() == null ? "" : line.userId()).append(',')
                    .append(quoted(line.oldMail())).append(',').append(quoted(line.newMail())).append(',')
                    .append(quoted(result)).append("\r\n");
        }
        try {
            Files.writeString(target, csv, StandardCharsets.UTF_8);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("The list could not be written: " + target, ex);
        }
    }

    private static String quoted(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
