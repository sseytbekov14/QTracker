package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The access model end to end: every kind of user x every control status x every operation, through the
 * real dev security chain (form login, session, CSRF, the read-only check, the controllers and the policy).
 * <p>
 * The expected outcome of each cell is written here from the access decisions, independently of
 * AccessPolicy, and compared with what the application answers. The whole matrix is written to
 * target/role-matrix/roles_matrix.md (the source of migration/analysis/4_roles_matrix.md); the test fails
 * listing every cell that differs.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:role-matrix-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads-matrix"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class RoleMatrixIT {

    private static final String PASSWORD = "Test#123";

    private static final List<String> STATUSES = List.of(
            "DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");

    /** Where a user stands on the controls of their row. */
    enum Place {
        /** Not on the control. */
        NONE,
        /** In the field whose step the status is (Facilitator, Control Operator or Process Owner). */
        STEP,
        /** Only in Shared With. */
        SHARED,
        /** In Facilitator at every status, next to a Control Operator who is someone else (two steps fields). */
        FACILITATOR,
        /** In Control Operator at every status, next to a Facilitator who is someone else (two steps fields). */
        OPERATOR,
        /** The only Facilitator and the only Control Operator: one person, one steps field. */
        BOTH
    }

    /** One row of the matrix: a kind of user. */
    record Who(String key, String label, AccessLevel level, AccessScope scope, boolean admin, Place place,
               boolean kdnControl, boolean disabled, boolean anonymous) {
    }

    private static final List<Who> PEOPLE = List.of(
            new Who("soqm", "SOQM", AccessLevel.SOQM, AccessScope.ALL, false, Place.NONE, false, false, false),
            new Who("part-step", "PARTICIPANT/OWN, assigned", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.STEP, false, false, false),
            new Who("part-none", "PARTICIPANT/OWN, not assigned", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.NONE, false, false, false),
            new Who("part-shared", "PARTICIPANT/OWN, shared only", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.SHARED, false, false, false),
            new Who("part-all", "PARTICIPANT/ALL (Master), not assigned", AccessLevel.PARTICIPANT, AccessScope.ALL, false, Place.NONE, false, false, false),
            new Who("kdn-step", "PARTICIPANT/KDN, assigned, KDN control", AccessLevel.PARTICIPANT, AccessScope.KDN, false, Place.STEP, true, false, false),
            new Who("kdn-none", "PARTICIPANT/KDN, not assigned, KDN control", AccessLevel.PARTICIPANT, AccessScope.KDN, false, Place.NONE, true, false, false),
            new Who("kdn-hr", "PARTICIPANT/KDN, assigned, non-KDN control", AccessLevel.PARTICIPANT, AccessScope.KDN, false, Place.STEP, false, false, false),
            new Who("ro-shared", "READ_ONLY/OWN, shared", AccessLevel.READ_ONLY, AccessScope.OWN, false, Place.SHARED, false, false, false),
            new Who("ro-all", "READ_ONLY/ALL", AccessLevel.READ_ONLY, AccessScope.ALL, false, Place.NONE, false, false, false),
            new Who("admin-soqm", "admin + SOQM", AccessLevel.SOQM, AccessScope.ALL, true, Place.NONE, false, false, false),
            new Who("admin-part", "admin + PARTICIPANT/OWN, not assigned", AccessLevel.PARTICIPANT, AccessScope.OWN, true, Place.NONE, false, false, false),
            new Who("admin-ro", "admin + READ_ONLY/OWN", AccessLevel.READ_ONLY, AccessScope.OWN, true, Place.NONE, false, false, false),
            new Who("disabled", "PARTICIPANT/OWN, assigned, disabled", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.STEP, false, true, false),
            new Who("part-fac", "PARTICIPANT/OWN, Facilitator (F and CO differ)", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.FACILITATOR, false, false, false),
            new Who("part-op", "PARTICIPANT/OWN, Control Operator (F and CO differ)", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.OPERATOR, false, false, false),
            new Who("part-both", "PARTICIPANT/OWN, Facilitator and Control Operator (one person)", AccessLevel.PARTICIPANT, AccessScope.OWN, false, Place.BOTH, false, false, false),
            new Who("ro-op", "READ_ONLY/OWN, Control Operator (old data)", AccessLevel.READ_ONLY, AccessScope.OWN, false, Place.OPERATOR, false, false, false),
            new Who("anonymous", "not signed in", null, null, false, Place.NONE, false, false, true));

    private static final List<String> CONTROL_OPS = List.of(
            "View page", "Read API", "History", "Save details", "Steps field", "Operator field", "Edit control",
            "Assign", "Upload", "Rename ID", "Step", "Return", "Excel (completed)");

    private static final List<String> USER_OPS = List.of(
            "Create control", "Export button", "Assignment picker", "Admin Panel", "Admin Panel change");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ControlRepository controlRepository;

    @Autowired
    private ControlAssignmentRepository assignmentRepository;

    @Autowired
    private ControlDetailsRepository detailsRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    private static int addressCount;
    private int controlCount;

    private User facilitator;
    private User operator;
    private User soqmLead;
    private User owner;
    private User adminTarget;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, MockHttpSession> sessions = new LinkedHashMap<>();

    @Test
    void everyKindOfUser_everyStatus_everyOperation() throws Exception {
        setUpPeople();

        List<String> mismatches = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("Generated by RoleMatrixIT on ").append(LocalDate.now()).append(".\n\n")
                .append("Cell: `ok` = done; otherwise what the application answered (`403`, `409`, `login` = sent ")
                .append("to the sign-in page, `not yet` = the \"Not available yet\" page, `n/a` = no such step). ")
                .append("‼ marks a cell that differs from the decided rule (see the legend).\n");

        for (String status : STATUSES) {
            report.append("\n### ").append(status).append("\n\n")
                    .append(header("User", CONTROL_OPS));
            for (Who who : PEOPLE) {
                Map<String, String> row = runControlOps(who, status);
                report.append("| ").append(who.label());
                for (String op : CONTROL_OPS) {
                    String actual = row.get(op);
                    String expected = expectedControlOp(who, status, op);
                    boolean matches = expected.equals("ok") == actual.equals("ok")
                            && (!expected.equals("not yet") || actual.equals("not yet"))
                            && !actual.startsWith("5") && !actual.equals("400");
                    if (!matches) {
                        mismatches.add(status + " | " + who.label() + " | " + op + ": expected " + expected + ", got " + actual);
                    }
                    report.append(" | ").append(matches ? "" : "‼ ").append(actual);
                }
                report.append(" |\n");
            }
        }

        report.append("\n### Without a control\n\n").append(header("User", USER_OPS));
        for (Who who : PEOPLE) {
            Map<String, String> row = runUserOps(who);
            report.append("| ").append(who.label());
            for (String op : USER_OPS) {
                String actual = row.get(op);
                boolean expected = expectedUserOp(who, op);
                boolean matches = expected == actual.equals("ok") && !actual.startsWith("5") && !actual.equals("400");
                if (!matches) {
                    mismatches.add("no control | " + who.label() + " | " + op + ": expected " + (expected ? "ok" : "refused")
                            + ", got " + actual);
                }
                report.append(" | ").append(matches ? "" : "‼ ").append(actual);
            }
            report.append(" |\n");
        }

        Path out = Path.of("target", "role-matrix", "roles_matrix.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);

        assertThat(mismatches).as("cells that differ from the decided rules (full matrix in %s)", out).isEmpty();
    }

    // ------------------------------------------------------------------ the decided rules

    /** "ok", "not yet" (opens the Not available yet page) or "refused". Written from the decisions, not from AccessPolicy. */
    private static String expectedControlOp(Who who, String status, String op) {
        boolean active = !who.anonymous() && !who.disabled();
        boolean soqm = active && who.level() == AccessLevel.SOQM;
        boolean seesAll = active && (soqm || who.admin() || who.scope() == AccessScope.ALL);
        boolean inF = inFacilitator(who, status);
        boolean inCO = inOperator(who, status);
        boolean inPO = inProcessOwner(who, status);
        boolean listed = inF || inCO || inPO;
        boolean shared = who.place() == Place.SHARED;
        boolean own = listed || shared;
        boolean inScope = who.scope() == AccessScope.KDN ? who.kdnControl() && own : who.scope() == AccessScope.ALL || own;
        boolean sees = active && (seesAll || inScope);
        boolean writer = active && who.level() != AccessLevel.READ_ONLY;
        // A participant acts in the field they are listed in, on a control within their scope
        boolean actsInStep = writer && who.level() == AccessLevel.PARTICIPANT && listed
                && (who.scope() != AccessScope.KDN || who.kdnControl());
        boolean participantStep = actsInStep && (("IN_PROGRESS".equals(status) && inF)
                || ("REVIEW".equals(status) && inCO) || ("PROCESS_OWNER_REVIEW".equals(status) && inPO));
        // Steps fields: one person -> one field, written by whoever's step it is; different people -> the
        // Facilitator's field in In Progress, the Control Operator's own field in Review; SoQM writes both
        boolean split = stepsSplit(who);
        boolean stepsField = soqm || (actsInStep && (("IN_PROGRESS".equals(status) && inF)
                || ("REVIEW".equals(status) && inCO && !split)));
        boolean operatorField = split && (soqm || (actsInStep && "REVIEW".equals(status) && inCO));

        return switch (op) {
            case "View page" -> !sees ? "refused"
                    : "DRAFT".equals(status) && shared && !seesAll ? "not yet" : "ok";
            case "Read API", "History" -> sees && !("DRAFT".equals(status) && shared && !seesAll) ? "ok" : "refused";
            case "Save details", "Upload" -> sees && writer && (soqm || participantStep) ? "ok" : "refused";
            case "Steps field" -> sees && writer && stepsField ? "ok" : "refused";
            case "Operator field" -> sees && writer && operatorField ? "ok" : "refused";
            case "Edit control", "Assign", "Rename ID" -> soqm ? "ok" : "refused";
            case "Step" -> switch (status) {
                case "DRAFT", "SOQM_HEAD_REVIEW" -> soqm ? "ok" : "refused";
                case "IN_PROGRESS", "REVIEW", "PROCESS_OWNER_REVIEW" -> participantStep ? "ok" : "refused";
                default -> "refused"; // a completed control: no step, also not for Shared With
            };
            case "Return" -> switch (status) {
                case "REVIEW", "PROCESS_OWNER_REVIEW" -> participantStep ? "ok" : "refused";
                case "SOQM_HEAD_REVIEW" -> soqm ? "ok" : "refused";
                default -> "refused";
            };
            case "Excel (completed)" -> "COMPLETED".equals(status) && sees && (soqm || shared) ? "ok" : "refused";
            default -> throw new IllegalArgumentException(op);
        };
    }

    private static boolean expectedUserOp(Who who, String op) {
        boolean active = !who.anonymous() && !who.disabled();
        boolean soqm = active && who.level() == AccessLevel.SOQM;
        return switch (op) {
            case "Create control", "Export button", "Assignment picker" -> soqm;
            case "Admin Panel" -> active && who.admin();
            case "Admin Panel change" -> active && who.admin() && who.level() != AccessLevel.READ_ONLY;
            default -> throw new IllegalArgumentException(op);
        };
    }

    // ------------------------------------------------------------------ running the operations

    private Map<String, String> runControlOps(Who who, String status) throws Exception {
        Map<String, String> row = new LinkedHashMap<>();
        MockHttpSession session = sessions.get(who.key());

        Control control = control(who, status);
        row.put("View page", page(get("/view-control/{id}", control.getId()), session));
        row.put("Read API", answer(get("/api/control-details").param("controlId", String.valueOf(control.getId())), session));
        row.put("History", answer(get("/api/controls/{id}/changelog", control.getId()), session));
        row.put("Excel (completed)", "COMPLETED".equals(status)
                ? answer(get("/api/controls/{id}/export/completed", control.getId()), session) : "n/a");
        row.put("Save details", answer(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"" + stepFieldOf(who, status) + "\":\"Saved by " + who.key() + "\"}"), session));
        row.put("Steps field", answer(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Steps by " + who.key() + "\"}"), session));
        row.put("Operator field", answer(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlOperatorReview\":\"Review by " + who.key() + "\"}"), session));
        row.put("Edit control", answer(put("/api/controls/{id}", control.getId()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlDescription\":\"Changed by " + who.key() + "\"}"), session));
        row.put("Assign", answer(post("/api/control-assignment").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + "}"), session));
        row.put("Upload", answer(multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", "matrix-" + who.key() + ".pdf", "application/pdf",
                        "%PDF-1.4 matrix".getBytes(StandardCharsets.UTF_8)))
                .with(csrf().asHeader()), session));
        row.put("Rename ID", answer(post("/api/controls/{id}/rename-id", control.getId()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newControlId\":\"" + control.getControlId() + "-R\"}"), session));

        row.put("Step", answer(step(status, control(who, status)), session));
        String returnUrl = switch (status) {
            case "REVIEW" -> "/api/workflow/return-to-facilitator";
            // Elsewhere there is no return: trying one must change nothing
            default -> "/api/workflow/return-to-operator";
        };
        row.put("Return", answer(post(returnUrl).with(csrf().asHeader())
                .param("controlId", String.valueOf(control(who, status).getId()))
                .param("comments", "Returned by " + who.key()), session));
        return reorder(row, CONTROL_OPS);
    }

    private MockHttpServletRequestBuilder step(String status, Control control) {
        String id = String.valueOf(control.getId());
        return switch (status) {
            case "DRAFT" -> post("/api/performance/initiate").with(csrf().asHeader())
                    .param("controlId", id)
                    .param("soqmYear", SoqmYear.current(today()));
            case "IN_PROGRESS" -> post("/api/workflow/submit-to-control-operator").with(csrf().asHeader()).param("controlId", id);
            case "REVIEW" -> post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader()).param("controlId", id);
            case "SOQM_HEAD_REVIEW" -> post("/api/workflow/submit-to-process-owner").with(csrf().asHeader()).param("controlId", id);
            case "PROCESS_OWNER_REVIEW" -> post("/api/workflow/complete-control").with(csrf().asHeader()).param("controlId", id);
            // The only step from Completed: Shared With sending it back to SoQM
            default -> post("/api/workflow/shared-submit-to-soqm-lead").with(csrf().asHeader()).param("controlId", id);
        };
    }

    private Map<String, String> runUserOps(Who who) throws Exception {
        MockHttpSession session = sessions.get(who.key());
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Create control", answer(post("/api/controls").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":\"HR-NEW-" + who.key() + "\",\"controlFrequency\":\"Monthly\","
                        + "\"controlType\":\"Preventive\",\"component\":\"HR\",\"operatedBy\":\"Finance\","
                        + "\"priority\":\"High\",\"nonAuditServicesApplicability\":\"No\"}"), session));
        MvcResult controls = perform(get("/controls"), session);
        ModelAndView mav = controls.getModelAndView();
        row.put("Export button", controls.getResponse().getStatus() == 200 && mav != null
                ? (Boolean.TRUE.equals(mav.getModel().get("canExportAll")) ? "ok" : "hidden")
                : outcome(controls));
        row.put("Assignment picker", answer(get("/api/users/role/FACILITATOR"), session));
        MvcResult adminPage = perform(get("/admin/users"), session);
        row.put("Admin Panel", adminPage.getResponse().getStatus() == 200 ? "ok" : outcome(adminPage));
        row.put("Admin Panel change", answer(post("/api/users/{id}/access", adminTarget.getId()).with(csrf().asHeader())
                .param("level", "PARTICIPANT")
                .param("scope", "OWN")
                .param("enabled", "true"), session));
        return row;
    }

    private String page(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult result = perform(request, session);
        ModelAndView mav = result.getModelAndView();
        if (result.getResponse().getStatus() == 200 && mav != null) {
            return switch (String.valueOf(mav.getViewName())) {
                case "view-control" -> "ok";
                case "control-not-available" -> "not yet";
                default -> mav.getViewName();
            };
        }
        return outcome(result);
    }

    private String answer(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult result = perform(request, session);
        int status = result.getResponse().getStatus();
        return status >= 200 && status < 300 ? "ok" : outcome(result);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        request.with(ownAddress());
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String outcome(MvcResult result) {
        int status = result.getResponse().getStatus();
        String location = result.getResponse().getRedirectedUrl();
        String forwarded = result.getResponse().getForwardedUrl();
        if ((status == 302 && location != null && location.contains("/login"))
                || (forwarded != null && forwarded.startsWith("/login"))) {
            return "login";
        }
        if (status == 302) {
            return "302 " + location;
        }
        return String.valueOf(status);
    }

    private static Map<String, String> reorder(Map<String, String> row, List<String> columns) {
        Map<String, String> ordered = new LinkedHashMap<>();
        columns.forEach(column -> ordered.put(column, row.get(column)));
        return ordered;
    }

    private static String header(String first, List<String> columns) {
        StringBuilder header = new StringBuilder("| ").append(first);
        columns.forEach(column -> header.append(" | ").append(column));
        header.append(" |\n|---");
        columns.forEach(column -> header.append("|---"));
        return header.append("|\n").toString();
    }

    // ------------------------------------------------------------------ data

    private void setUpPeople() throws Exception {
        facilitator = saveUser("rm-fac", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        operator = saveUser("rm-op", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        soqmLead = saveUser("rm-soqm-lead", AccessLevel.SOQM, AccessScope.ALL, false);
        owner = saveUser("rm-po", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        adminTarget = saveUser("rm-admin-target", AccessLevel.PARTICIPANT, AccessScope.OWN, false);

        for (Who who : PEOPLE) {
            if (who.anonymous()) {
                continue;
            }
            User user = saveUser("rm-" + who.key(), who.level(), who.scope(), who.admin());
            users.put(who.key(), user);
            sessions.put(who.key(), login(user.getMail()));
            if (who.disabled()) {
                // Signed in, then disabled by an administrator: every later request is refused
                user.setEnabled(false);
                userRepository.save(user);
            }
        }
    }

    /** A fresh control in the status, with the four standard people and the row's user in their place. */
    private Control control(Who who, String status) {
        Control control = new Control();
        control.setControlId((who.kdnControl() ? "KDN-RM-" : "HR-RM-") + (++controlCount));
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("HR");
        control.setOperatedBy("Finance");
        control.setPriority("High");
        control.setNonAuditServicesApplicability("No");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control.setSoqmYear(SoqmYear.current(today()));
        control.setCreatedBy(soqmLead);
        control.setCreatedAt(LocalDateTime.now());
        control = controlRepository.save(control);

        User user = users.get(who.key());
        String mail = user != null ? user.getMail() : null;
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        if (who.place() == Place.BOTH && mail != null) {
            assignment.setFacilitator(mail);
            assignment.setControlOperator(mail);
        } else {
            assignment.setFacilitator(facilitator.getMail() + (mail != null && inFacilitator(who, status) ? "," + mail : ""));
            assignment.setControlOperator(operator.getMail() + (mail != null && inOperator(who, status) ? "," + mail : ""));
        }
        assignment.setSoqmLead(soqmLead.getMail());
        assignment.setProcessOwner(owner.getMail() + (mail != null && inProcessOwner(who, status) ? "," + mail : ""));
        assignment.setControlSharedWith(who.place() == Place.SHARED && mail != null ? mail : null);
        assignment.setControlOperationDate(today().plusDays(10));
        assignment.setControlOperationDeadline(today().plusDays(17));
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setControlStepsPerformed("Steps performed");
        details.setControlOperatorReview("Operator review");
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Process Owner comments");
        detailsRepository.save(details);
        return control;
    }

    /** The field whose step the status is, for Place.STEP (Facilitator, Control Operator or Process Owner). */
    private static String stepSlot(String status) {
        return switch (status) {
            case "REVIEW" -> "CO";
            case "PROCESS_OWNER_REVIEW", "COMPLETED" -> "PO";
            default -> "F";
        };
    }

    private static boolean inFacilitator(Who who, String status) {
        return switch (who.place()) {
            case STEP -> "F".equals(stepSlot(status));
            case FACILITATOR, BOTH -> true;
            default -> false;
        };
    }

    private static boolean inOperator(Who who, String status) {
        return switch (who.place()) {
            case STEP -> "CO".equals(stepSlot(status));
            case OPERATOR, BOTH -> true;
            default -> false;
        };
    }

    private static boolean inProcessOwner(Who who, String status) {
        return who.place() == Place.STEP && "PO".equals(stepSlot(status));
    }

    /** Every control has a Facilitator and a Control Operator who differ, except the one-person row's. */
    private static boolean stepsSplit(Who who) {
        return who.place() != Place.BOTH;
    }

    /** The Details field the holder of the current step writes ("Save details"). */
    private static String stepFieldOf(Who who, String status) {
        return switch (status) {
            case "REVIEW" -> stepsSplit(who) ? "controlOperatorReview" : "controlStepsPerformed";
            case "PROCESS_OWNER_REVIEW" -> "processOwnerComments";
            default -> "controlStepsPerformed";
        };
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope, boolean admin) {
        User user = new User();
        user.setMail(name + "@matrix.test");
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(admin);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    private MockHttpSession login(String mail) throws Exception {
        MvcResult login = mockMvc.perform(post("/login").with(csrf()).with(ownAddress())
                        .param("username", mail)
                        .param("password", PASSWORD))
                .andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).as("login of %s", mail).isEqualTo("/");
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    /** Every request from its own address: RateLimitingFilter counts per address. */
    private static RequestPostProcessor ownAddress() {
        String address = "10.9." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static LocalDate today() {
        return DeadlineOverdue.today(Instant.now());
    }
}
