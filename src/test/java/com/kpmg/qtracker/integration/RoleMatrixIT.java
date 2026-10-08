package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.dto.ControlResponseDTO;
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
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.ComponentControlsList;
import com.kpmg.qtracker.service.ControlStepsFields;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.FileStorageService;
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
import org.springframework.web.util.HtmlUtils;

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
        BOTH,
        /** Not on the control, but its creator (My controls see it, decision of 2026-10-07). */
        CREATOR
    }

    /** One row of the matrix: a kind of user, by role (SoQM Team, User with Visibility and Access, KDN). */
    record Who(String key, String label, AccessLevel level, AccessScope scope, Place place,
               boolean kdnControl, boolean disabled, boolean anonymous) {
    }

    private static final AccessLevel EDIT = AccessLevel.PARTICIPANT;
    private static final AccessLevel READ = AccessLevel.READ_ONLY;
    private static final AccessScope MY = AccessScope.OWN;
    private static final AccessScope ALL = AccessScope.ALL;

    private static final List<Who> PEOPLE = List.of(
            new Who("soqm", "SoQM Team", AccessLevel.SOQM, ALL, Place.NONE, false, false, false),
            new Who("soqm-kdn", "SoQM Team, KDN control", AccessLevel.SOQM, ALL, Place.NONE, true, false, false),
            new Who("part-step", "User · My controls · Edit, assigned", EDIT, MY, Place.STEP, false, false, false),
            new Who("part-none", "User · My controls · Edit, not on it", EDIT, MY, Place.NONE, false, false, false),
            new Who("part-shared", "User · My controls · Edit, shared only", EDIT, MY, Place.SHARED, false, false, false),
            new Who("part-creator", "User · My controls · Edit, creator only", EDIT, MY, Place.CREATOR, false, false, false),
            new Who("all-step", "User · All controls · Edit, assigned", EDIT, ALL, Place.STEP, false, false, false),
            new Who("all-none", "User · All controls · Edit, not on it", EDIT, ALL, Place.NONE, false, false, false),
            new Who("all-shared", "User · All controls · Edit, shared only", EDIT, ALL, Place.SHARED, false, false, false),
            new Who("part-none-kdn", "User · My controls · Edit, not on it, KDN control", EDIT, MY, Place.NONE, true, false, false),
            new Who("kdn-step", "KDN, in the step field, KDN control", READ, AccessScope.KDN, Place.STEP, true, false, false),
            new Who("kdn-shared", "KDN, shared only, KDN control", READ, AccessScope.KDN, Place.SHARED, true, false, false),
            new Who("kdn-creator", "KDN, creator only, KDN control", READ, AccessScope.KDN, Place.CREATOR, true, false, false),
            new Who("kdn-none", "KDN, not on it, KDN control", READ, AccessScope.KDN, Place.NONE, true, false, false),
            new Who("kdn-hr", "KDN, in the step field, non-KDN control (old data)", READ, AccessScope.KDN, Place.STEP, false, false, false),
            new Who("kdn-hr-shared", "KDN, shared only, non-KDN control", READ, AccessScope.KDN, Place.SHARED, false, false, false),
            new Who("kdn-inside", "KDN, in the step field, KDN further on in the ID (X-KDN-…)", READ, AccessScope.KDN, Place.STEP, false, false, false),
            new Who("ro-shared", "User · My controls · Read Only, shared", READ, MY, Place.SHARED, false, false, false),
            new Who("ro-creator", "User · My controls · Read Only, creator only", READ, MY, Place.CREATOR, false, false, false),
            new Who("ro-none", "User · My controls · Read Only, not on it", READ, MY, Place.NONE, false, false, false),
            new Who("ro-all", "User · All controls · Read Only, not on it", READ, ALL, Place.NONE, false, false, false),
            new Who("ro-all-step", "User · All controls · Read Only, in the step field (old data)", READ, ALL, Place.STEP, false, false, false),
            new Who("ro-all-shared", "User · All controls · Read Only, shared", READ, ALL, Place.SHARED, false, false, false),
            new Who("disabled", "User · My controls · Edit, assigned, disabled", EDIT, MY, Place.STEP, false, true, false),
            new Who("part-fac", "User · My controls · Edit, Facilitator (F and CO differ)", EDIT, MY, Place.FACILITATOR, false, false, false),
            new Who("part-op", "User · My controls · Edit, Control Operator (F and CO differ)", EDIT, MY, Place.OPERATOR, false, false, false),
            new Who("part-both", "User · My controls · Edit, Facilitator and Control Operator (one person)", EDIT, MY, Place.BOTH, false, false, false),
            new Who("ro-op", "User · My controls · Read Only, Control Operator (old data)", READ, MY, Place.OPERATOR, false, false, false),
            new Who("ro-both", "User · My controls · Read Only, Facilitator and Control Operator (one person, old data)", READ, MY, Place.BOTH, false, false, false),
            new Who("all-both", "User · All controls · Edit, Facilitator and Control Operator (one person)", EDIT, ALL, Place.BOTH, false, false, false),
            new Who("kdn-op", "KDN, Control Operator (F and CO differ), KDN control", READ, AccessScope.KDN, Place.OPERATOR, true, false, false),
            new Who("anonymous", "not signed in", null, null, Place.NONE, false, false, true));

    private static final List<String> CONTROL_OPS = List.of(
            "In Controls list", "In component list", "View page", "Program on page", "Notice", "KDN mark", "Read API", "History", "Download", "Save details", "Steps field", "Operator's Program", "Edit control",
            "Assign", "Upload", "Rename ID", "Rename ±KDN, no comment", "Rename ±KDN", "Step", "Return",
            "Move to In Progress", "Move to Review", "Move to SoQM review", "Move to PO review", "Excel (completed)");

    /** The operations that change a control: none may pass where the page shows a notice. */
    private static final List<String> WRITES = List.of("Save details", "Steps field", "Operator's Program", "Edit control",
            "Assign", "Upload", "Rename ID", "Rename ±KDN", "Step", "Return",
            "Move to In Progress", "Move to Review", "Move to SoQM review", "Move to PO review");

    /** The working statuses in order; "Move to X" is POST /api/workflow/move, a return when X is earlier. */
    private static final List<String> WORKING = List.of(
            "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");

    private static final Map<String, String> BACK_TO = Map.of(
            "Move to In Progress", "IN_PROGRESS",
            "Move to Review", "REVIEW",
            "Move to SoQM review", "SOQM_HEAD_REVIEW",
            "Move to PO review", "PROCESS_OWNER_REVIEW");

    /**
     * A KDN control is one whose Control ID starts with "KDN", in any case: the KDN rows' controls take these
     * forms in turn; the other controls are "HR-RM-n", the kdn-inside row's "X-KDN-RM-n" (not a KDN control).
     */
    private static final List<String> KDN_ID_FORMS = List.of("KDN-RM-%d", "KDNRM%d", "kdn-rm-%d", "Kdn_RM_%d");

    private static final List<String> USER_OPS = List.of(
            "Create control", "Export button", "Assignment picker", "Admin Panel", "Admin Panel change", "KDN block");

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

    @Autowired
    private FileStorageService fileStorageService;

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
                    boolean matches = "Notice".equals(op) ? expected.equals(actual) : expected.equals("ok") == actual.equals("ok")
                            && (!expected.equals("not yet") || actual.equals("not yet"))
                            && !actual.startsWith("5") && expected.equals("400") == actual.equals("400");
                    if (!matches) {
                        mismatches.add(status + " | " + who.label() + " | " + op + ": expected " + expected + ", got " + actual);
                    }
                    report.append(" | ").append(matches ? "" : "‼ ").append(actual);
                }
                report.append(" |\n");
                String notice = row.get("Notice");
                if ("READ_ONLY".equals(notice) || "NOT_ASSIGNED".equals(notice)) {
                    for (String write : WRITES) {
                        if ("ok".equals(row.get(write))) {
                            mismatches.add(status + " | " + who.label() + " | notice " + notice + " but " + write + " is allowed");
                        }
                    }
                }
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
        // SoQM Team and All controls see every control, drafts included (Read Only as well)
        boolean seesAll = active && (soqm || who.scope() == AccessScope.ALL);
        boolean inF = inFacilitator(who, status);
        boolean inCO = inOperator(who, status);
        boolean inPO = inProcessOwner(who, status);
        boolean listed = inF || inCO || inPO;
        boolean shared = who.place() == Place.SHARED;
        boolean own = listed || shared;
        // My controls = assigned, shared or creator; KDN = every KDN control, drafts included, whoever is on it,
        // and never another control (decision of 2026-10-07)
        boolean creator = who.place() == Place.CREATOR;
        boolean kdnUser = who.scope() == AccessScope.KDN;
        boolean inScope = kdnUser ? who.kdnControl() : who.scope() == AccessScope.ALL || own || creator;
        boolean sees = active && (seesAll || inScope);
        // A draft someone is only shared with opens once initiated, except for those who see it anyway
        boolean notYet = "DRAFT".equals(status) && shared && !(active && (seesAll || kdnUser));
        boolean writer = active && who.level() != AccessLevel.READ_ONLY;
        // A completed control is locked for everyone, SoQM included (decision 4); renaming is not an edit
        boolean soqmEdits = soqm && !"COMPLETED".equals(status);
        // A User with Edit acts only in the field they are listed in (also with All controls)
        boolean actsInStep = writer && who.level() == AccessLevel.PARTICIPANT && listed
                && (who.scope() != AccessScope.KDN || who.kdnControl());
        boolean participantStep = actsInStep && (("IN_PROGRESS".equals(status) && inF)
                || ("REVIEW".equals(status) && inCO) || ("PROCESS_OWNER_REVIEW".equals(status) && inPO));
        // Control Steps Performed and Results: one field for both steps, the Facilitator's in In Progress and the
        // Control Operator's in Review; Control Operator's Program: SoQM Team only (the Control Operator sends it,
        // SoQM Team puts it in), every status but Completed; SoQM both
        boolean stepsField = soqmEdits || (actsInStep && (("IN_PROGRESS".equals(status) && inF)
                || ("REVIEW".equals(status) && inCO)));
        boolean operatorField = soqmEdits;

        return switch (op) {
            // The page's notice (no buttons the server refuses): Read Only and KDN everywhere, a User with Edit
            // in no Control role field "not assigned", nobody else; "-" when the page does not open
            case "Notice" -> !sees || notYet ? "-"
                    : !writer ? "READ_ONLY"
                    : soqm || listed ? "NONE" : "NOT_ASSIGNED";
            // The "KDN control" mark on the control's page: SoQM Team only, on a KDN control
            case "KDN mark" -> soqm && who.kdnControl() ? "ok" : "refused";
            // The Controls list (and the dashboard tiles counted from it) holds every control the user sees
            case "In Controls list" -> sees ? "ok" : "refused";
            // The Action Centre's lists: the component's (and the KDN list for a KDN control) hold the same
            // controls, each row opening its own control; a non-KDN control is never in the KDN list
            case "In component list" -> sees ? "ok" : "refused";
            case "View page" -> !sees ? "refused"
                    : notYet ? "not yet" : "ok";
            // Control Operator's Program is on the Details tab of everyone whose page opens, one person or not
            case "Program on page" -> !sees ? "refused"
                    : notYet ? "not yet" : "ok";
            case "Read API", "History", "Download" -> sees && !notYet ? "ok" : "refused";
            case "Save details", "Upload" -> sees && writer && (soqmEdits || participantStep) ? "ok" : "refused";
            case "Steps field" -> sees && writer && stepsField ? "ok" : "refused";
            case "Operator's Program" -> sees && writer && operatorField ? "ok" : "refused";
            case "Edit control", "Assign" -> soqmEdits ? "ok" : "refused";
            case "Rename ID" -> soqm ? "ok" : "refused";
            // "KDN" appearing at or going from the start of the ID changes who sees the control: SoQM gives a comment
            case "Rename ±KDN, no comment" -> soqm ? "400" : "refused";
            case "Rename ±KDN" -> soqm ? "ok" : "refused";
            // SoQM Team performs every step, the Control roles' ones on their behalf (decision 3); Read
            // Only and KDN none
            case "Step" -> switch (status) {
                case "DRAFT", "SOQM_HEAD_REVIEW" -> soqm ? "ok" : "refused";
                case "IN_PROGRESS", "REVIEW", "PROCESS_OWNER_REVIEW" -> participantStep || soqm ? "ok" : "refused";
                default -> "refused"; // a completed control: no step, also not for Shared With
            };
            case "Return" -> switch (status) {
                case "REVIEW", "PROCESS_OWNER_REVIEW" -> participantStep || soqm ? "ok" : "refused";
                case "SOQM_HEAD_REVIEW" -> soqm ? "ok" : "refused";
                default -> "refused";
            };
            // Back to any earlier working status in one move: SoQM from every later status, Completed included
            // (decision 4); a participant only their own return (Control Operator to In Progress, Process
            // Owner to Review). On to the next status: whoever performs that step ("Step"); never further
            case "Move to In Progress", "Move to Review", "Move to SoQM review", "Move to PO review" -> {
                String target = BACK_TO.get(op);
                int from = WORKING.indexOf(status);
                int to = WORKING.indexOf(target);
                boolean ownReturn = participantStep && (("REVIEW".equals(status) && "IN_PROGRESS".equals(target))
                        || ("PROCESS_OWNER_REVIEW".equals(status) && "REVIEW".equals(target)));
                if (from >= 0 && to < from) {
                    yield soqm || ownReturn ? "ok" : "refused";
                }
                yield from >= 0 && to == from + 1 ? expectedControlOp(who, status, "Step") : "refused";
            }
            case "Excel (completed)" -> "COMPLETED".equals(status) && sees && (soqm || shared) ? "ok" : "refused";
            default -> throw new IllegalArgumentException(op);
        };
    }

    private static boolean expectedUserOp(Who who, String op) {
        boolean active = !who.anonymous() && !who.disabled();
        boolean soqm = active && who.level() == AccessLevel.SOQM;
        return switch (op) {
            case "Create control", "Export button", "Assignment picker" -> soqm;
            // Admin Panel, users and audit: SoQM Team and only SoQM Team
            case "Admin Panel", "Admin Panel change" -> soqm;
            // Action Centre "KDN" card: SoQM Team, All controls and KDN always; My controls only with a KDN
            // control among theirs, and no My controls row of the matrix is on one
            case "KDN block" -> active && (soqm || who.scope() == AccessScope.ALL || who.scope() == AccessScope.KDN);
            default -> throw new IllegalArgumentException(op);
        };
    }

    // ------------------------------------------------------------------ running the operations

    private Map<String, String> runControlOps(Who who, String status) throws Exception {
        Map<String, String> row = new LinkedHashMap<>();
        MockHttpSession session = sessions.get(who.key());

        Control control = control(who, status);
        row.put("In Controls list", inControlsList(control, session));
        row.put("In component list", inComponentLists(control, session));
        row.put("View page", page(get("/view-control/{id}", control.getId()), session));
        row.put("Program on page", programOnPage(control, session));
        row.put("Notice", notice(control, session));
        row.put("KDN mark", kdnMark(control, session));
        row.put("Read API", answer(get("/api/control-details").param("controlId", String.valueOf(control.getId())), session));
        row.put("History", answer(get("/api/controls/{id}/changelog", control.getId()), session));
        row.put("Download", answer(get("/api/attachments/download/{name}", control.getAttachmentDetailsPath())
                .param("controlId", String.valueOf(control.getId())), session));
        row.put("Excel (completed)", "COMPLETED".equals(status)
                ? answer(get("/api/controls/{id}/export/completed", control.getId()), session) : "n/a");
        row.put("Save details", answer(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"" + stepFieldOf(who, status) + "\":\"Saved by " + who.key() + "\"}"), session));
        row.put("Steps field", answer(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Steps by " + who.key() + "\"}"), session));
        row.put("Operator's Program", answer(post("/api/control-details").with(csrf().asHeader())
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
        // From a KDN ID to one not starting with "KDN", or the other way round
        String flipped = who.kdnControl() ? "HR-RN-" + control.getId() : "KDN-RN-" + control.getId();
        row.put("Rename ±KDN, no comment", answer(post("/api/controls/{id}/rename-id", control.getId()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newControlId\":\"" + flipped + "\"}"), session));
        row.put("Rename ±KDN", answer(post("/api/controls/{id}/rename-id", control.getId()).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newControlId\":\"" + flipped + "\",\"comment\":\"Renamed by " + who.key() + "\"}"), session));

        // With a comment: SoQM needs one when it makes a participant's step. A completed control has no next
        // step (the old shared resubmit is gone; SoQM returns it, "Back to ...")
        row.put("Step", "COMPLETED".equals(status) ? "n/a"
                : answer(step(status, control(who, status)).param("comments", "Moved on by " + who.key()), session));
        String returnUrl = switch (status) {
            case "REVIEW" -> "/api/workflow/return-to-facilitator";
            // Elsewhere there is no return: trying one must change nothing
            default -> "/api/workflow/return-to-operator";
        };
        row.put("Return", answer(post(returnUrl).with(csrf().asHeader())
                .param("controlId", String.valueOf(control(who, status).getId()))
                .param("comments", "Returned by " + who.key()), session));
        for (Map.Entry<String, String> back : BACK_TO.entrySet()) {
            row.put(back.getKey(), answer(post("/api/workflow/move").with(csrf().asHeader())
                    .param("controlId", String.valueOf(control(who, status).getId()))
                    .param("targetStatus", back.getValue())
                    .param("comments", "Back by " + who.key()), session));
        }
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
            default -> post("/api/workflow/complete-control").with(csrf().asHeader()).param("controlId", id);
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
                .param("role", "USER")
                .param("visibility", "MY")
                .param("access", "EDIT")
                .param("enabled", "true"), session));
        MvcResult dashboard = perform(get("/"), session);
        ModelAndView dashboardView = dashboard.getModelAndView();
        row.put("KDN block", dashboard.getResponse().getStatus() == 200 && dashboardView != null
                ? (dashboardView.getModel().get("kdnSummary") != null ? "ok" : "hidden")
                : outcome(dashboard));
        return row;
    }

    /**
     * The notice of the control's page; with one, the page has neither the Edit button nor the workflow
     * buttons ("buttons!" in the cell when it does).
     */
    private String notice(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/view-control/{id}", control.getId()), session);
        ModelAndView mav = result.getModelAndView();
        if (result.getResponse().getStatus() != 200 || mav == null || !"view-control".equals(mav.getViewName())) {
            return "-";
        }
        String notice = String.valueOf(mav.getModel().get("accessNotice"));
        String html = result.getResponse().getContentAsString();
        boolean buttons = html.contains("id=\"editBtn\"") || html.contains("id=\"workflow-buttons-container\"");
        boolean banner = html.contains("id=\"accessBanner\"");
        if (!"NONE".equals(notice) && (buttons || !banner)) {
            return notice + " buttons!";
        }
        return notice;
    }

    /** "ok" when the control's page has the Control Operator's Program field, "none" when not, else as "View page". */
    private String programOnPage(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/view-control/{id}", control.getId()), session);
        ModelAndView mav = result.getModelAndView();
        if (result.getResponse().getStatus() != 200 || mav == null || !"view-control".equals(mav.getViewName())) {
            return mav != null && "control-not-available".equals(mav.getViewName()) ? "not yet" : outcome(result);
        }
        String html = result.getResponse().getContentAsString();
        return html.contains("id=\"controlOperatorReview\"")
                && html.contains(">" + HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL) + "<") ? "ok" : "none";
    }

    /** "ok" when the control's page carries the "KDN control" mark, "none" when it does not, else as "View page". */
    private String kdnMark(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/view-control/{id}", control.getId()), session);
        ModelAndView mav = result.getModelAndView();
        if (result.getResponse().getStatus() != 200 || mav == null || !"view-control".equals(mav.getViewName())) {
            return mav != null && "control-not-available".equals(mav.getViewName()) ? "not yet" : outcome(result);
        }
        return result.getResponse().getContentAsString().contains("vc-kdn-badge") ? "ok" : "none";
    }

    /** "ok" when /controls lists the control, "absent" when it does not, otherwise what the page answered. */
    private String inControlsList(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/controls"), session);
        ModelAndView mav = result.getModelAndView();
        if (result.getResponse().getStatus() != 200 || mav == null || !"controls".equals(mav.getViewName())) {
            return outcome(result);
        }
        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> listed = (List<ControlResponseDTO>) mav.getModel().get("controls");
        return listed.stream().anyMatch(dto -> control.getId().equals(dto.getId())) ? "ok" : "absent";
    }

    /**
     * "ok" when /component/HR and /component/ALL list the control with a row opening its page, and so does
     * /component/KDN for a KDN control; "absent" when a list leaves it out, "in KDN list!" when a non-KDN control shows up there,
     * otherwise what the page answered. Searched by Control ID, so no page of the list is missed.
     */
    private String inComponentLists(Control control, MockHttpSession session) throws Exception {
        boolean kdnControl = AccessPolicy.isKdnControl(control.getControlId());
        java.util.Set<String> answers = new java.util.LinkedHashSet<>();
        for (String path : List.of("/component/HR", "/component/ALL", "/component/KDN")) {
            MvcResult result = perform(get(path).param("q", control.getControlId()).param("size", "50"), session);
            ModelAndView mav = result.getModelAndView();
            if (result.getResponse().getStatus() != 200 || mav == null || !"component-controls".equals(mav.getViewName())) {
                answers.add(outcome(result));
                continue;
            }
            ComponentControlsList.Result list = (ComponentControlsList.Result) mav.getModel().get("list");
            boolean listed = list.rows().stream().anyMatch(row -> control.getId().equals(row.id())
                    && row.href().equals("/view-control/" + control.getId()))
                    && result.getResponse().getContentAsString().contains("data-href=\"/view-control/" + control.getId() + "\"");
            if (path.endsWith("KDN") && !kdnControl) {
                if (listed) {
                    answers.add("in KDN list!");
                }
                continue;
            }
            answers.add(listed ? "ok" : "absent");
        }
        return answers.size() == 1 ? answers.iterator().next() : String.join(" / ", answers);
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
        facilitator = saveUser("rm-fac", AccessLevel.PARTICIPANT, AccessScope.OWN);
        operator = saveUser("rm-op", AccessLevel.PARTICIPANT, AccessScope.OWN);
        soqmLead = saveUser("rm-soqm-lead", AccessLevel.SOQM, AccessScope.ALL);
        owner = saveUser("rm-po", AccessLevel.PARTICIPANT, AccessScope.OWN);
        adminTarget = saveUser("rm-admin-target", AccessLevel.PARTICIPANT, AccessScope.OWN);

        for (Who who : PEOPLE) {
            if (who.anonymous()) {
                continue;
            }
            User user = saveUser("rm-" + who.key(), who.level(), who.scope());
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
    private Control control(Who who, String status) throws Exception {
        Control control = new Control();
        ++controlCount;
        control.setControlId(who.kdnControl()
                ? String.format(KDN_ID_FORMS.get(controlCount % KDN_ID_FORMS.size()), controlCount)
                : "kdn-inside".equals(who.key()) ? "X-KDN-RM-" + controlCount : "HR-RM-" + controlCount);
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
        control.setCreatedBy(who.place() == Place.CREATOR && users.get(who.key()) != null ? users.get(who.key()) : soqmLead);
        control.setCreatedAt(LocalDateTime.now());
        // One stored attachment, for "Download"
        control.setAttachmentDetailsPath(fileStorageService.saveFile(new MockMultipartFile("file", "evidence.pdf",
                "application/pdf", "%PDF-1.4 evidence".getBytes(StandardCharsets.UTF_8)), control.getControlId()));
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

    /** The Details field the holder of the current step writes ("Save details"). */
    private static String stepFieldOf(Who who, String status) {
        return switch (status) {
            case "PROCESS_OWNER_REVIEW" -> "processOwnerComments";
            default -> "controlStepsPerformed";
        };
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setMail(name + "@matrix.test");
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
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
