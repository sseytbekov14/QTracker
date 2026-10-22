package com.kpmg.qtracker.loadtest;

import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.ItApplications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fills the load test database with made-up data (java -jar qtracker-loadtest.jar --loadtest.generate.controls=5000):
 * 150 accounts (46 KDN, SoQM Admin Team, User Edit / Read Only, own and all), controls of every status and
 * frequency including drafts, their workflow steps, history, audit entries, notifications, Related Controls,
 * IT Applications and attachments (a few files of 3-5 MB). Everything already in the database is removed first.
 * The same number gives the same data (fixed seed). Writes target-loadtest/data/users.csv and controls.csv for
 * the load test client (made-up addresses only) and stops the application when done.
 */
@Component
@Profile("loadtest")
@ConditionalOnProperty(name = "loadtest.generate.controls")
public class LoadTestDataGenerator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestDataGenerator.class);

    static final String DOMAIN = "@loadtest.local";
    private static final String[] COMPONENTS = {"HR", "EP", "A&C", "RER", "INTR", "I&C", "GOV", "TECHR", "M&R", "RAP"};
    private static final String[] MONTHS = {"OCT", "NOV", "DEC", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP"};
    private static final String[] STATUSES = {"DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW",
            "COMPLETED"};
    private static final int[] STATUS_WEIGHTS = {10, 25, 15, 10, 10, 30};
    private static final String[] FREQUENCIES = {"Monthly", "Quarterly", "Annual", "Semi Annual", "Ad-hoc", "Recurring"};
    private static final int[] FREQUENCY_WEIGHTS = {35, 25, 15, 10, 8, 7};
    private static final String[] STEP_TYPES = {"FACILITATOR", "CONTROL_OPERATOR", "SOQM_TEAM", "PROCESS_OWNER"};
    private static final String CURRENT_YEAR = "1 OCT 2026 - 30 SEP 2027";
    private static final String PREVIOUS_YEAR = "1 OCT 2025 - 30 SEP 2026";
    private static final int BATCH = 2000;
    /** Controls whose fields hold many people: one move notifies dozens (the "many recipients" scenario). */
    static final int BROADCAST_CONTROLS = 5;
    /** Controls with two files of 3-5 MB each (the download and upload scenarios). */
    static final int BIG_FILE_CONTROLS = 10;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorage;
    private final ConfigurableApplicationContext context;
    private final Environment environment;

    public LoadTestDataGenerator(JdbcTemplate jdbc, DataSource dataSource, PasswordEncoder passwordEncoder,
                                 FileStorageService fileStorage, ConfigurableApplicationContext context,
                                 Environment environment) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
        this.fileStorage = fileStorage;
        this.context = context;
        this.environment = environment;
    }

    /** One made-up account. */
    record Person(long id, String mail, String name, String kind) {
    }

    /** One generated control, as the client needs it. */
    record Gen(long id, String controlId, String status, String frequency, boolean kdn, String component,
               List<Person> facilitators, List<Person> operators, List<Person> soqm, List<Person> owners,
               List<Person> sharedWith, LocalDate operationDate, List<String> files) {
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int controls = environment.getRequiredProperty("loadtest.generate.controls", Integer.class);
        checkDatabase();
        long started = System.currentTimeMillis();
        Random random = new Random(2026L + controls);
        log.info("Load test data: {} controls into {}", controls, jdbc.queryForObject("SELECT current_database()",
                String.class));

        clear();
        List<Person> people = users();
        List<String> refs = relatedRefs(random);
        List<Gen> generated = controls(controls, people, refs, random);
        workflow(generated, random);
        audit(generated, random);
        notifications(generated, people, random);
        relatedControls(generated, random);
        files(generated, random);
        for (String table : List.of("users", "controls", "workflow_steps", "workflow_history", "admin_audit_log",
                "notifications", "control_related_controls", "related_control_ref")) {
            jdbc.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), COALESCE((SELECT MAX(id) FROM "
                    + table + "), 1))");
        }
        jdbc.execute("ANALYZE");
        writeDataFiles(people, generated);
        log.info("Load test data done in {} s: {}", (System.currentTimeMillis() - started) / 1000, counts());

        if (environment.getProperty("loadtest.generate.exit", Boolean.class, true)) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    /** The guard checked the settings; here the connection itself, before anything is removed. */
    private void checkDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            LoadTestDatabase.refusal(url).ifPresent(reason -> {
                throw new IllegalStateException("The generator does not write here: " + reason);
            });
        }
        LoadTestDatabase.nameRefusal(jdbc.queryForObject("SELECT current_database()", String.class))
                .ifPresent(reason -> {
                    throw new IllegalStateException("The generator does not write here: " + reason);
                });
    }

    private void clear() throws IOException {
        jdbc.execute("TRUNCATE email_outbox, control_notification_log, notifications, admin_audit_log, workflow_history,"
                + " workflow_steps, control_attachments, control_related_controls, related_control_ref, deleted_users,"
                + " controls, users RESTART IDENTITY CASCADE");
        Path root = uploadRoot();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(root)).map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    private Path uploadRoot() {
        return Path.of(environment.getRequiredProperty("file.upload.dir")).toAbsolutePath().normalize();
    }

    // ---- users ----

    private List<Person> users() {
        String hash = passwordEncoder.encode(environment.getRequiredProperty("loadtest.password"));
        List<Person> people = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        // kind, access level, scope, how many: 150 in all
        Object[][] kinds = {
                {"soqm", "SOQM", "ALL", 8},
                {"kdn", "READ_ONLY", "KDN", 46},
                {"editall", "PARTICIPANT", "ALL", 4},
                {"edit", "PARTICIPANT", "OWN", 80},
                {"ro", "READ_ONLY", "OWN", 8},
                {"roall", "READ_ONLY", "ALL", 4}};
        long id = 1;
        for (Object[] kind : kinds) {
            for (int n = 1; n <= (int) kind[3]; n++, id++) {
                String mail = "lt." + kind[0] + "." + String.format("%03d", n) + DOMAIN;
                String name = "LT " + ((String) kind[0]).toUpperCase(Locale.ROOT) + " " + String.format("%03d", n);
                people.add(new Person(id, mail, name, (String) kind[0]));
                rows.add(new Object[]{id, mail, name, true, "SOQM".equals(kind[1]), hash, kind[1], kind[2],
                        Timestamp.valueOf(LocalDateTime.now().minusDays(200))});
            }
        }
        jdbc.batchUpdate("INSERT INTO users (id, mail, displayname, enabled, admin_access, password, access_level,"
                + " access_scope, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        return people;
    }

    private List<String> relatedRefs(Random random) {
        List<String> refs = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int n = 1; n <= 300; n++) {
            String ref = COMPONENTS[random.nextInt(COMPONENTS.length)].replace("&", "") + "-REF-" + String.format("%03d", n);
            refs.add(ref);
            rows.add(new Object[]{ref, ref.toLowerCase(Locale.ROOT), n % 20 != 0});
        }
        jdbc.batchUpdate("INSERT INTO related_control_ref (control_id, control_key, active) VALUES (?, ?, ?)", rows);
        return refs;
    }

    // ---- controls ----

    private List<Gen> controls(int count, List<Person> people, List<String> refs, Random random) {
        List<Person> soqm = of(people, "soqm");
        List<Person> participants = new ArrayList<>(of(people, "edit"));
        List<Person> owners = new ArrayList<>(participants);
        owners.addAll(of(people, "editall"));
        List<Person> readers = new ArrayList<>(of(people, "ro"));
        readers.addAll(of(people, "roall"));
        LocalDate today = LocalDate.now();

        List<Gen> generated = new ArrayList<>(count);
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (int i = 1; i <= count; i++) {
            boolean broadcast = i <= BROADCAST_CONTROLS;
            boolean kdn = !broadcast && random.nextInt(100) < 15;
            String component = COMPONENTS[random.nextInt(COMPONENTS.length)];
            String frequency = broadcast ? "Monthly" : pick(FREQUENCIES, FREQUENCY_WEIGHTS, random);
            String status = broadcast ? "IN_PROGRESS" : pick(STATUSES, STATUS_WEIGHTS, random);
            String controlId = (kdn ? "KDN-" + component.replace("&", "") : component.replace("&", ""))
                    + "-LT-" + String.format("%05d", i) + "/FY27/Central"
                    + switch (frequency) {
                        case "Monthly" -> "/" + MONTHS[random.nextInt(12)];
                        case "Quarterly" -> "/" + (1 + random.nextInt(4)) + "Q";
                        default -> "";
                    };
            List<Person> facilitators = some(participants, broadcast ? 15 : random.nextInt(10) == 0 ? 2 : 1, random);
            List<Person> operators = some(participants, broadcast ? 10 : random.nextInt(10) == 0 ? 2 : 1, random);
            List<Person> leads = some(soqm, broadcast ? 3 : 1, random);
            List<Person> processOwners = some(owners, broadcast ? 10 : 1, random);
            List<Person> shared = broadcast ? some(readers, 12, random)
                    : random.nextInt(10) < 4 ? some(random.nextBoolean() ? readers : participants, 1 + random.nextInt(3),
                    random) : List.of();
            Person creator = soqm.get(random.nextInt(soqm.size()));
            LocalDate operation = today.plusDays(random.nextInt(150) - 120);
            LocalDate deadline = operation.plusDays(14);
            LocalDate next = switch (frequency) {
                case "Monthly" -> operation.plusMonths(1);
                case "Quarterly" -> operation.plusMonths(3);
                case "Semi Annual" -> operation.plusMonths(6);
                case "Annual" -> operation.plusYears(1);
                default -> null;
            };
            boolean draft = "DRAFT".equals(status);
            LocalDateTime created = operation.minusDays(30).atTime(9, 0);
            int roll = random.nextInt(100);
            String controlStatus = draft && roll < 30 ? null : roll < 94 ? "ACTIVE" : roll < 97 ? "NOT_APPLICABLE"
                    : "SUPERSEDED";
            String it = String.join("; ", some(ItApplications.OPTIONS, 1 + random.nextInt(3), random));
            String related = random.nextInt(10) < 3 ? String.join("; ", some(refs, 1 + random.nextInt(3), random)) : null;

            rows.add(new Object[]{
                    (long) i, controlId, frequency, random.nextBoolean() ? "Preventive" : "Detective",
                    random.nextBoolean() ? "Manual" : "IT-dependent manual", component,
                    random.nextBoolean() ? "Central" : "Local", "Reference " + i,
                    new String[]{"High", "Medium", "Low"}[random.nextInt(3)], random.nextBoolean() ? "Yes" : "No",
                    random.nextBoolean() ? "Homogeneous" : "Non-homogeneous",
                    text("Control description", i, 260, random), text("PRP", i, 120, random),
                    draft ? null : text("SoQM Admin Team comments", i, 160, random),
                    draft ? null : text("Process Owner comments", i, 160, random),
                    creator.id(), creator.mail(), creator.name(), Timestamp.valueOf(created),
                    Timestamp.valueOf(created.plusDays(random.nextInt(25))), Date.valueOf(deadline),
                    "Process " + (i % 97), "Department " + (i % 23), text("Process activities", i, 200, random),
                    it, related, draft ? null : text("Steps performed", i, 220, random),
                    draft ? null : text("Operator review", i, 160, random),
                    mails(facilitators), mails(operators), mails(leads), mails(processOwners), mails(shared),
                    Date.valueOf(operation), Date.valueOf(deadline), next == null ? null : Date.valueOf(next),
                    status, controlStatus, random.nextInt(10) < 8 ? CURRENT_YEAR : PREVIOUS_YEAR,
                    !draft && random.nextInt(10) == 0 ? "Please add the evidence of step 3" : null});
            generated.add(new Gen(i, controlId, status, frequency, controlId.startsWith("KDN"), component, facilitators,
                    operators, leads, processOwners, shared, operation, new ArrayList<>()));
            if (rows.size() == BATCH) {
                insertControls(rows);
            }
        }
        insertControls(rows);
        return generated;
    }

    private void insertControls(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO controls (id, control_id, control_frequency, control_category, control_type,"
                + " component, operated_by, references_to_control, priority, non_audit_services_applicability, homogeneity,"
                + " control_description, prp, soqm_head_comments, process_owner_comments, created_by, created_by_email,"
                + " created_by_name, created_at, updated_at, deadline, process_name, department, process_activities,"
                + " it_applications, other_related_controls, control_steps_performed, control_operator_review,"
                + " facilitator, control_operator, soqm_team, process_owner, control_shared_with, control_operation_date,"
                + " control_operation_deadline, next_control_operation_date, performance_status, control_status,"
                + " soqm_year, return_to_facilitator_comment)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        rows.clear();
    }

    // ---- steps, history, audit ----

    private void workflow(List<Gen> generated, Random random) {
        List<Object[]> steps = new ArrayList<>(BATCH);
        List<Object[]> history = new ArrayList<>(BATCH);
        for (Gen control : generated) {
            int reached = indexOf(control.status());
            if (reached == 0) {
                continue;
            }
            LocalDateTime at = control.operationDate().atTime(10, 0);
            List<List<Person>> byStep = List.of(control.facilitators(), control.operators(), control.soqm(),
                    control.owners());
            for (int s = 0; s < 4; s++) {
                Person first = byStep.get(s).get(0);
                steps.add(new Object[]{control.id(), s + 1, Timestamp.valueOf(at), first.mail(), first.name(),
                        s == 0 ? "IN_PROGRESS" : "DRAFT", STEP_TYPES[s]});
            }
            Person soqm = control.soqm().get(0);
            history.add(historyRow(control, "INITIATE", null, "IN_PROGRESS", soqm, "SoQM Admin Team", at,
                    "Control initiated"));
            for (int to = 2; to <= reached; to++) {
                Person by = byStep.get(Math.min(to - 2, 3)).get(0);
                at = at.plusHours(4 + random.nextInt(40));
                if (to == 3 && random.nextInt(5) == 0) {
                    // A return and the second try
                    Person operator = control.operators().get(0);
                    history.add(historyRow(control, "RETURN_TO_FACILITATOR", "REVIEW", "IN_PROGRESS", operator,
                            "Control Operator", at, "Please add the evidence of step 3"));
                    at = at.plusHours(6);
                    history.add(historyRow(control, "SUBMIT_TO_OPERATOR", "IN_PROGRESS", "REVIEW",
                            control.facilitators().get(0), "Facilitator", at, "Submitted to Control Operator"));
                    at = at.plusHours(6);
                }
                history.add(historyRow(control, historyType(STATUSES[to]), STATUSES[to - 1], STATUSES[to], by,
                        new String[]{"Facilitator", "Control Operator", "SoQM Admin Team", "Process Owner"}[Math.min(to - 2, 3)],
                        at, "Submitted to " + STATUSES[to]));
            }
            if (steps.size() >= BATCH) {
                flushWorkflow(steps, history);
            }
        }
        flushWorkflow(steps, history);
    }

    private static String historyType(String to) {
        return switch (to) {
            case "REVIEW" -> "SUBMIT_TO_OPERATOR";
            case "SOQM_HEAD_REVIEW" -> "SUBMIT_TO_SOQM_TEAM";
            case "PROCESS_OWNER_REVIEW" -> "SUBMIT_TO_PROCESS_OWNER";
            default -> "APPROVE";
        };
    }

    private static Object[] historyRow(Gen control, String type, String from, String to, Person by, String actedAs,
                                       LocalDateTime at, String comment) {
        return new Object[]{control.id(), Timestamp.valueOf(at), comment, type, from, by.mail(), by.name(), to, actedAs,
                false, by.mail()};
    }

    private void flushWorkflow(List<Object[]> steps, List<Object[]> history) {
        if (!steps.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO workflow_steps (control_id, sequence_order, assigned_at, assigned_to_email,"
                    + " assigned_to_name, status, step_type) VALUES (?, ?, ?, ?, ?, ?, ?)", steps);
            steps.clear();
        }
        if (!history.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO workflow_history (control_id, created_at, comments, action_type, from_step,"
                    + " performed_by_email, performed_by_name, to_step, acted_as, on_behalf, assigned_performer)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", history);
            history.clear();
        }
    }

    private void audit(List<Gen> generated, Random random) {
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (Gen control : generated) {
            Person soqm = control.soqm().get(0);
            LocalDateTime at = control.operationDate().atTime(9, 0).minusDays(20);
            int edits = 1 + random.nextInt(4);
            for (int e = 0; e < edits; e++) {
                at = at.plusDays(1 + random.nextInt(3));
                rows.add(new Object[]{control.id(), control.controlId(), Timestamp.valueOf(at), "EDIT",
                        "Control fields updated", soqm.mail(), soqm.name(), "[\"Process Activities\"]",
                        "{\"Process Activities\":\"Version " + e + "\"}",
                        "{\"Process Activities\":\"Version " + (e + 1) + "\"}"});
            }
            for (int to = 2; to <= indexOf(control.status()); to++) {
                at = at.plusDays(1);
                rows.add(new Object[]{control.id(), control.controlId(), Timestamp.valueOf(at), "WORKFLOW_MOVE",
                        "Move: " + STATUSES[to - 1] + " -> " + STATUSES[to], soqm.mail(), soqm.name(),
                        "[\"Performance Status\"]", "{\"Performance Status\":\"" + STATUSES[to - 1] + "\"}",
                        "{\"Performance Status\":\"" + STATUSES[to] + "\",\"On behalf\":false}"});
            }
            if (rows.size() >= BATCH) {
                insertAudit(rows);
            }
        }
        insertAudit(rows);
    }

    private void insertAudit(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO admin_audit_log (control_id, control_control_id, created_at, action_type,"
                + " action_description, admin_email, admin_name, changed_fields, previous_values, new_values)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        rows.clear();
    }

    // ---- notifications, related controls, files ----

    private void notifications(List<Gen> generated, List<Person> people, Random random) {
        String[][] types = {{"WORKFLOW_STEP", "Control Operator Review"}, {"REMINDER", "Control Reminder"},
                {"RETURN_TO_FACILITATOR", "Control sent back to Facilitator"}, {"COMPLETED", "SoQM control completed"},
                {"INITIATE", "The creation of the SoQM control"}, {"OVERDUE", "Control Deadline Notification"}};
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (Person person : people) {
            if ("kdn".equals(person.kind())) {
                continue;
            }
            int count = "soqm".equals(person.kind()) ? 80 : 40;
            for (int n = 0; n < count; n++) {
                Gen control = generated.get(random.nextInt(generated.size()));
                String[] type = types[random.nextInt(types.length)];
                LocalDateTime at = LocalDateTime.now().minusHours(random.nextInt(24 * 90));
                boolean read = random.nextInt(10) >= 3;
                rows.add(new Object[]{control.id(), Timestamp.valueOf(at), person.id(), type[0], type[1],
                        "Dear " + person.name() + ", control " + control.controlId() + ": " + type[1] + ".",
                        "/view-control/" + control.id(), read, read ? Timestamp.valueOf(at.plusHours(2)) : null});
                if (rows.size() == BATCH) {
                    insertNotifications(rows);
                }
            }
        }
        insertNotifications(rows);
    }

    private void insertNotifications(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO notifications (control_id, created_at, user_id, type, title, message, link, is_read,"
                + " read_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        rows.clear();
    }

    private void relatedControls(List<Gen> generated, Random random) {
        List<Object[]> rows = new ArrayList<>(BATCH);
        for (Gen control : generated) {
            if (random.nextInt(10) != 0 || generated.size() < 3) {
                continue;
            }
            Set<Long> related = new LinkedHashSet<>();
            for (int n = 1 + random.nextInt(2); related.size() < n; ) {
                long other = generated.get(random.nextInt(generated.size())).id();
                if (other != control.id()) {
                    related.add(other);
                }
            }
            int order = 0;
            for (Long other : related) {
                rows.add(new Object[]{control.id(), other, order++});
            }
        }
        jdbc.batchUpdate("INSERT INTO control_related_controls (control_id, related_control_id, sort_order)"
                + " VALUES (?, ?, ?)", rows);
    }

    private void files(List<Gen> generated, Random random) throws IOException {
        List<Gen> withFiles = new ArrayList<>();
        int big = 0;
        for (Gen control : generated) {
            if ("DRAFT".equals(control.status())) {
                continue;
            }
            if (big < BIG_FILE_CONTROLS && !control.kdn()) {
                big++;
                for (int n = 1; n <= 2; n++) {
                    control.files().add(save(control, "Evidence " + n + ".pdf", (3 + random.nextInt(3)) * 1024 * 1024,
                            random));
                }
                withFiles.add(control);
            } else if (random.nextInt(100) < 8) {
                control.files().add(save(control, "Evidence.pdf", 20 * 1024, random));
                withFiles.add(control);
            }
        }
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> paths = new ArrayList<>();
        for (Gen control : withFiles) {
            for (String file : control.files()) {
                rows.add(new Object[]{control.id(), "DOCUMENTS", file, control.facilitators().get(0).mail(),
                        "IN_PROGRESS", Timestamp.valueOf(control.operationDate().atTime(11, 0))});
            }
            paths.add(new Object[]{String.join(";", control.files()), control.id()});
        }
        jdbc.batchUpdate("INSERT INTO control_attachments (control_id, tab, file_name, uploaded_by_email, uploaded_stage,"
                + " uploaded_at) VALUES (?, ?, ?, ?, ?, ?)", rows);
        jdbc.batchUpdate("UPDATE controls SET attachment_documents_path = ? WHERE id = ?", paths);
    }

    private String save(Gen control, String name, int size, Random random) throws IOException {
        byte[] content = new byte[size];
        random.nextBytes(content);
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, content, 0, header.length);
        return fileStorage.saveFile(new BytesFile(name, content),
                FileStorageService.controlFolder(control.controlId(), control.id()));
    }

    // ---- the client's data files ----

    private void writeDataFiles(List<Person> people, List<Gen> generated) throws IOException {
        Path folder = Path.of(environment.getProperty("loadtest.data-dir",
                System.getProperty("user.dir") + "/target-loadtest/data"));
        Files.createDirectories(folder);
        List<String> users = new ArrayList<>(List.of("mail,kind"));
        people.forEach(person -> users.add(person.mail() + "," + person.kind()));
        Files.write(folder.resolve("users.csv"), users, StandardCharsets.UTF_8);

        List<String> lines = new ArrayList<>(List.of(
                "id,controlId,status,frequency,kdn,component,facilitators,operators,soqm,owners,shared,files"));
        for (Gen control : generated) {
            lines.add(String.join(",", String.valueOf(control.id()), control.controlId(), control.status(),
                    control.frequency(), String.valueOf(control.kdn()), control.component(),
                    mailsOf(control.facilitators()), mailsOf(control.operators()), mailsOf(control.soqm()),
                    mailsOf(control.owners()), mailsOf(control.sharedWith()), String.join("|", control.files())));
        }
        Files.write(folder.resolve("controls.csv"), lines, StandardCharsets.UTF_8);
        log.info("Load test client data: {}", folder.toAbsolutePath());
    }

    private String counts() {
        StringBuilder out = new StringBuilder();
        for (String table : List.of("users", "controls", "workflow_steps", "workflow_history", "admin_audit_log",
                "notifications", "control_attachments", "control_related_controls", "related_control_ref")) {
            out.append(table).append('=').append(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
                    .append(' ');
        }
        return out.toString().trim();
    }

    // ---- helpers ----

    private static List<Person> of(List<Person> people, String kind) {
        return people.stream().filter(person -> person.kind().equals(kind)).toList();
    }

    private static <T> List<T> some(List<T> from, int count, Random random) {
        Set<T> chosen = new LinkedHashSet<>();
        int wanted = Math.min(count, from.size());
        while (chosen.size() < wanted) {
            chosen.add(from.get(random.nextInt(from.size())));
        }
        return new ArrayList<>(chosen);
    }

    private static String pick(String[] values, int[] weights, Random random) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int roll = random.nextInt(total);
        for (int n = 0; n < values.length; n++) {
            roll -= weights[n];
            if (roll < 0) {
                return values[n];
            }
        }
        return values[values.length - 1];
    }

    private static int indexOf(String status) {
        for (int n = 0; n < STATUSES.length; n++) {
            if (STATUSES[n].equals(status)) {
                return n;
            }
        }
        return 0;
    }

    private static String mails(List<Person> people) {
        return people.isEmpty() ? null : String.join(", ", people.stream().map(Person::mail).toList());
    }

    private static String mailsOf(List<Person> people) {
        return String.join("|", people.stream().map(Person::mail).toList());
    }

    private static String text(String label, int n, int length, Random random) {
        StringBuilder out = new StringBuilder(label).append(' ').append(n).append(':');
        String[] words = {"review", "evidence", "sample", "engagement", "partner", "quality", "monitoring", "check",
                "approval", "policy", "file", "report", "independence", "training", "client"};
        while (out.length() < length) {
            out.append(' ').append(words[random.nextInt(words.length)]);
        }
        return out.substring(0, length);
    }

    /** A file in memory, as an upload would bring it (FileStorageService.saveFile). */
    private record BytesFile(String name, byte[] content) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "application/pdf";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
