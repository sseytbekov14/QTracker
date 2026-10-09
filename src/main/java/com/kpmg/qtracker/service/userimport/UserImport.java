package com.kpmg.qtracker.service.userimport;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.security.LoginNameResolver;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The people of QT_Users.csv as QTracker accounts in a test database: login from the column User (LoginNames),
 * address {@code <login>@auth.username-domain}, display name as in User, role from Role and Access, active
 * when Status is Active, the temporary password (dev and test profiles only). A dry run by default; with
 * apply the new accounts are created, each with an entry in the audit trail. Accounts that are already there
 * are never changed (the same file again changes nothing). The login of each person goes to
 * import_logins_<date>.csv next to the file; the report itself shows logins masked and no names.
 */
@Service
@RequiredArgsConstructor
public class UserImport {

    static final String ACTOR_MAIL = "system";
    static final int MIN_ACTIVE_SOQM_TEAM = 2;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService auditService;
    private final LoginNameResolver loginNames;
    private final Environment environment;

    public enum Outcome {
        CREATE("create"),
        UNCHANGED("already there, unchanged"),
        DIFFERS("already there with other access or status, not changed"),
        SERVICE_ACCOUNT("service account, not imported"),
        ERROR("not imported");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One line of the file and what the import does with it; login and mail only when there is one. */
    public record Line(int line, Outcome outcome, String displayName, String login, String mail,
                       AccessPolicy.Profile profile, boolean enabled, String reason) {
    }

    public record Report(boolean applied, String refusal, String encoding, String delimiter, List<Line> lines,
                         Path loginsFile) {

        public boolean refused() {
            return refusal != null;
        }

        public List<Line> lines(Outcome outcome) {
            return lines.stream().filter(line -> line.outcome() == outcome).toList();
        }

        public long count(Outcome outcome) {
            return lines(outcome).size();
        }

        /** The report for the console: counts, lines by number, logins masked, no names. */
        public String render() {
            String nl = System.lineSeparator();
            StringBuilder out = new StringBuilder("User import: ");
            if (refused()) {
                out.append("REFUSED, nothing written: ").append(refusal).append(nl);
                appendProblems(out, nl);
                return out.toString();
            }
            out.append(applied ? "APPLIED" : "DRY RUN, nothing written").append(nl);
            out.append("file: ").append(encoding).append(", ").append(delimiter).append(" separated, ")
                    .append(lines.size()).append(" line(s)").append(nl);
            List<Line> created = lines(Outcome.CREATE);
            if (!created.isEmpty()) {
                out.append(applied ? "Created:" : "Would create:").append(nl);
                created.forEach(line -> out.append(String.format("  line %3d  %-16s %s%s%n", line.line(),
                        mask(line.login()), plain(line.profile()),
                        line.enabled() ? "" : " (inactive)")));
            }
            for (Line line : lines(Outcome.DIFFERS)) {
                out.append(String.format("  line %3d  %-16s %s: %s%n", line.line(), mask(line.login()),
                        Outcome.DIFFERS.label(), line.reason()));
            }
            appendProblems(out, nl);

            Map<UserRole, Integer> byRole = new EnumMap<>(UserRole.class);
            created.forEach(line -> byRole.merge(line.profile().role(), 1, Integer::sum));
            out.append(applied ? "created: " : "would create: ").append(created.size());
            if (!created.isEmpty()) {
                out.append(" (");
                List<String> parts = new ArrayList<>();
                byRole.forEach((role, count) -> parts.add(role.getDisplayName() + " " + count));
                long inactive = created.stream().filter(line -> !line.enabled()).count();
                if (inactive > 0) {
                    parts.add("inactive " + inactive);
                }
                out.append(String.join(", ", parts)).append(")");
            }
            out.append(", unchanged: ").append(count(Outcome.UNCHANGED))
                    .append(", already there with other access: ").append(count(Outcome.DIFFERS))
                    .append(", service accounts: ").append(count(Outcome.SERVICE_ACCOUNT))
                    .append(", errors: ").append(count(Outcome.ERROR)).append(nl);
            if (loginsFile != null) {
                out.append("Names and logins: ").append(loginsFile).append(nl);
            }
            if (!applied && !created.isEmpty()) {
                out.append("Run again with --apply to create them.").append(nl);
            }
            return out.toString();
        }

        private void appendProblems(StringBuilder out, String nl) {
            for (Line line : lines) {
                if (line.outcome() == Outcome.SERVICE_ACCOUNT || line.outcome() == Outcome.ERROR) {
                    out.append(String.format("  line %3d  %s: %s%n", line.line(),
                            line.outcome() == Outcome.ERROR ? "error" : "skipped", line.reason()));
                }
            }
        }
    }

    /** The role as the Admin Panel shows it, with "/" between the parts: a Windows console may not show "·". */
    static String plain(AccessPolicy.Profile profile) {
        return RoleDisplayMapper.summary(profile).replace(RoleDisplayMapper.SEPARATOR, " / ");
    }

    /** "jsmith" -> "j****h": the first and last letter only. */
    public static String mask(String login) {
        if (login == null || login.isEmpty()) {
            return "";
        }
        if (login.length() <= 2) {
            return login.charAt(0) + "*";
        }
        return login.charAt(0) + "*".repeat(login.length() - 2) + login.charAt(login.length() - 1);
    }

    /**
     * @param actor who runs it (the operating-system user), for the audit trail
     * @param today the date in the name of the logins file
     */
    @Transactional
    public Report run(Path file, boolean apply, String actor, LocalDate today) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return refused("the import gives everyone the temporary password, which is allowed only in the dev "
                    + "and test profiles (active: " + String.join(", ", environment.getActiveProfiles()) + ")");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            return refused("the file cannot be read: " + file);
        }
        UsersCsv.Table table = UsersCsv.read(bytes);
        if (!table.ok()) {
            return new Report(false, table.error(), table.encoding(), null, List.of(), null);
        }

        List<Line> lines = plan(table);
        long activeSoqm = lines.stream()
                .filter(line -> line.login() != null && line.enabled()
                        && line.profile().role() == UserRole.SOQM_TEAM)
                .count();
        if (activeSoqm < MIN_ACTIVE_SOQM_TEAM) {
            return new Report(false, "the file must give at least " + MIN_ACTIVE_SOQM_TEAM
                    + " active SoQM Team members (someone has to run the Admin Panel), found " + activeSoqm,
                    table.encoding(), table.delimiter(), lines, null);
        }

        if (apply) {
            String actorName = "User import" + (actor == null || actor.isBlank() ? "" : " (" + actor + ")");
            for (Line line : lines) {
                if (line.outcome() == Outcome.CREATE) {
                    create(line, actorName);
                }
            }
        }
        Path loginsFile = writeLogins(file, lines, apply, today);
        return new Report(apply, null, table.encoding(), table.delimiter(), lines, loginsFile);
    }

    // What each line of the file gives, in the order of the file
    private List<Line> plan(UsersCsv.Table table) {
        List<PersonRow> rows = table.records().stream().map(PersonRow::of).toList();
        Map<Integer, Line> problems = new HashMap<>();
        Map<Integer, PersonRow> people = new HashMap<>();
        List<LoginNames.Candidate> candidates = new ArrayList<>();
        Map<String, Integer> firstLineOfName = new HashMap<>();
        for (PersonRow row : rows) {
            if (row.kind() == PersonRow.Kind.SERVICE_ACCOUNT) {
                problems.put(row.line(), problem(row, Outcome.SERVICE_ACCOUNT, row.error()));
                continue;
            }
            if (row.kind() == PersonRow.Kind.ERROR) {
                problems.put(row.line(), problem(row, Outcome.ERROR, row.error()));
                continue;
            }
            LoginNames.Base base = LoginNames.base(row.displayName());
            if (!base.ok()) {
                problems.put(row.line(), problem(row, Outcome.ERROR, "no login: " + base.error()));
                continue;
            }
            if (row.displayName().length() > 255) {
                problems.put(row.line(), problem(row, Outcome.ERROR, "the name is longer than 255 characters"));
                continue;
            }
            Integer sameName = firstLineOfName.putIfAbsent(LoginNames.sortKey(row.displayName()), row.line());
            if (sameName != null) {
                problems.put(row.line(), problem(row, Outcome.ERROR, "the same User as line " + sameName));
                continue;
            }
            people.put(row.line(), row);
            candidates.add(new LoginNames.Candidate(row.line(), row.displayName(), base.login()));
        }
        Map<Integer, String> logins = LoginNames.assign(candidates);

        List<Line> lines = new ArrayList<>();
        for (PersonRow row : rows) {
            Line problem = problems.get(row.line());
            if (problem != null) {
                lines.add(problem);
                continue;
            }
            String login = logins.get(row.line());
            lines.add(withAccount(row, login, loginNames.mailOf(login)));
        }
        return lines;
    }

    // Compared with the account the address already has, if any
    private Line withAccount(PersonRow row, String login, String mail) {
        Optional<User> existing = userRepository.findByMail(mail);
        if (existing.isEmpty()) {
            return line(row, Outcome.CREATE, login, mail, null);
        }
        User user = existing.get();
        if (!LoginNames.sortKey(user.getDisplayName()).equals(LoginNames.sortKey(row.displayName()))) {
            return line(row, Outcome.ERROR, login, mail, "the login " + mask(login) + " belongs to another account");
        }
        List<String> differences = new ArrayList<>();
        AccessPolicy.Profile current = AccessPolicy.Profile.of(user);
        if (!current.equals(row.profile())) {
            differences.add("has " + plain(current) + ", the file says " + plain(row.profile()));
        }
        boolean enabled = Boolean.TRUE.equals(user.getEnabled());
        if (enabled != row.enabled()) {
            differences.add(enabled ? "active, the file says inactive" : "inactive, the file says active");
        }
        return differences.isEmpty() ? line(row, Outcome.UNCHANGED, login, mail, null)
                : line(row, Outcome.DIFFERS, login, mail, String.join("; ", differences));
    }

    private void create(Line line, String actorName) {
        AccessPolicy.Profile profile = line.profile();
        AccessPolicy.levelScopeRefusal(profile.level(), profile.scope()).ifPresent(refusal -> {
            throw new IllegalStateException(refusal);
        });
        User user = new User();
        user.setMail(line.mail());
        user.setDisplayName(line.displayName());
        user.setAccessLevel(profile.level());
        user.setAccessScope(profile.scope());
        user.setAdminAccess(profile.adminAccess());
        user.setEnabled(line.enabled());
        user.setPassword(passwordEncoder.encode(UserService.DEFAULT_NEW_USER_PASSWORD));
        User saved = userRepository.save(user);

        auditService.logActionWithChanges(ACTOR_MAIL, actorName, "USER_CREATE", null,
                "Created user " + saved.getMail(),
                "mail,displayName,role,enabled",
                "-",
                "mail=" + saved.getMail()
                        + ", displayName=" + saved.getDisplayName()
                        + ", role=" + RoleDisplayMapper.access(saved)
                        + ", enabled=" + Boolean.TRUE.equals(saved.getEnabled()));
    }

    // import_logins_<date>.csv next to the file: line, name, login, what happened; people with a login only
    private Path writeLogins(Path file, List<Line> lines, boolean applied, LocalDate today) {
        Path target = file.toAbsolutePath().getParent().resolve("import_logins_" + today + ".csv");
        StringBuilder csv = new StringBuilder("﻿Line,Name,Login,Result\r\n");
        for (Line line : lines) {
            if (line.login() == null) {
                continue;
            }
            String result = line.outcome() == Outcome.CREATE ? (applied ? "created" : "would create")
                    : line.outcome().label();
            csv.append(line.line()).append(',').append(quoted(line.displayName())).append(',')
                    .append(line.login()).append(',').append(quoted(result)).append("\r\n");
        }
        try {
            Files.writeString(target, csv, StandardCharsets.UTF_8);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("The logins file could not be written: " + target, ex);
        }
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static Line problem(PersonRow row, Outcome outcome, String reason) {
        return new Line(row.line(), outcome, row.displayName(), null, null, row.profile(), row.enabled(), reason);
    }

    private static Line line(PersonRow row, Outcome outcome, String login, String mail, String reason) {
        return new Line(row.line(), outcome, row.displayName(), login, mail, row.profile(), row.enabled(), reason);
    }

    private static Report refused(String refusal) {
        return new Report(false, refusal, null, null, List.of(), null);
    }
}
