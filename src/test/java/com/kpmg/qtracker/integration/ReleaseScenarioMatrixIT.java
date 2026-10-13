package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release test pass, stage B: every kind of user x every status x every operation of a control, through the real
 * sign-in. The expected cell is written below from the decisions (project notes, 4_roles_matrix.md, section 5 of
 * the specification), not from AccessPolicy. Every write runs on a control of its own. The table goes to
 * target/release-scenario/matrix.md; the test fails listing every cell that differs.
 */
class ReleaseScenarioMatrixIT extends ReleaseScenarioSupport {

    static final List<String> STATUSES = List.of(
            "DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");
    static final List<String> OPS = List.of("View", "List", "Read API", "History", "Download",
            "Control tab", "Details: step field", "Details: definition", "Assignment", "Shared With", "Documents",
            "Upload", "Delete file", "Delete own upload", "Step", "Return", "Rename ID", "Excel (completed)");
    /** A refused workflow move: 403 (not this person's step) or 409 (not from this status). */
    static final String REFUSED = "403/409";

    enum Place { NONE, STEP, OTHER_STEP, SHARED }

    record Row(String key, String label, AccessLevel level, AccessScope scope, Place place, boolean kdnControl,
               boolean disabled, boolean anonymous) {
        boolean soqm() {
            return level == AccessLevel.SOQM;
        }

        boolean edit() {
            return level == AccessLevel.PARTICIPANT;
        }
    }

    static final AccessLevel EDIT = AccessLevel.PARTICIPANT;
    static final AccessLevel READ = AccessLevel.READ_ONLY;

    static final List<Row> ROWS = List.of(
            new Row("soqm", "SoQM Admin Team", AccessLevel.SOQM, AccessScope.ALL, Place.NONE, false, false, false),
            new Row("soqm-kdn", "SoQM Admin Team, KDN control", AccessLevel.SOQM, AccessScope.ALL, Place.NONE, true, false, false),
            new Row("my-step", "User · My · Edit, in the field of the step", EDIT, AccessScope.OWN, Place.STEP, false, false, false),
            new Row("my-other", "User · My · Edit, in another step's field", EDIT, AccessScope.OWN, Place.OTHER_STEP, false, false, false),
            new Row("my-none", "User · My · Edit, not on it", EDIT, AccessScope.OWN, Place.NONE, false, false, false),
            new Row("my-shared", "User · My · Edit, Shared With only", EDIT, AccessScope.OWN, Place.SHARED, false, false, false),
            new Row("myro-shared", "User · My · Read Only, Shared With", READ, AccessScope.OWN, Place.SHARED, false, false, false),
            new Row("myro-step", "User · My · Read Only, in the step field (old data)", READ, AccessScope.OWN, Place.STEP, false, false, false),
            new Row("allro", "User · All · Read Only, not on it", READ, AccessScope.ALL, Place.NONE, false, false, false),
            new Row("alledit-step", "User · All · Edit, in the field of the step", EDIT, AccessScope.ALL, Place.STEP, false, false, false),
            new Row("alledit-none", "User · All · Edit, not on it", EDIT, AccessScope.ALL, Place.NONE, false, false, false),
            new Row("kdn", "KDN, not on it, KDN control", READ, AccessScope.KDN, Place.NONE, true, false, false),
            new Row("kdn-step", "KDN, in the step field, KDN control", READ, AccessScope.KDN, Place.STEP, true, false, false),
            new Row("kdn-hr", "KDN, Shared With, non-KDN control", READ, AccessScope.KDN, Place.SHARED, false, false, false),
            new Row("disabled", "User · My · Edit, in the step field, disabled", EDIT, AccessScope.OWN, Place.STEP, false, true, false),
            new Row("anonymous", "not signed in", EDIT, AccessScope.OWN, Place.NONE, false, false, true));

    private User facilitator;
    private User operator;
    private User soqmLead;
    private User owner;
    private User watcher;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, MockHttpSession> sessions = new LinkedHashMap<>();

    // ---- expectations, from the decisions ----

    /** The field of the step a status is waiting for: F, CO or PO; none for draft, SoQM review and completed. */
    static String stepSlot(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "F";
            case "REVIEW" -> "CO";
            case "PROCESS_OWNER_REVIEW" -> "PO";
            default -> null;
        };
    }

    /** Where the row's person stands: STEP = the step's field (F on draft/SoQM review, PO on completed). */
    static String slot(Row row, String status) {
        return switch (row.place()) {
            case STEP -> stepSlot(status) != null ? stepSlot(status) : "COMPLETED".equals(status) ? "PO" : "F";
            case OTHER_STEP -> switch (status) {
                case "DRAFT" -> "CO";
                case "IN_PROGRESS", "SOQM_HEAD_REVIEW" -> "PO";
                default -> "F";
            };
            default -> null;
        };
    }

    /** View page: ok, "not yet" (a draft someone only shares), 403, login. */
    static String sees(Row row, String status) {
        if (row.anonymous() || row.disabled()) {
            return "login";
        }
        if (row.soqm()) {
            return "ok";
        }
        if (row.scope() == AccessScope.KDN) {
            // KDN: every KDN control in every status, drafts too; never another control
            return row.kdnControl() ? "ok" : "403";
        }
        if (row.scope() == AccessScope.ALL) {
            return "ok";
        }
        return switch (row.place()) {
            case STEP, OTHER_STEP -> "ok";
            case SHARED -> "DRAFT".equals(status) ? "not yet" : "ok";
            case NONE -> "403";
        };
    }

    static String expected(Row row, String status, String op) {
        String sees = sees(row, status);
        boolean completed = "COMPLETED".equals(status);
        if ("login".equals(sees)) {
            return "Excel (completed)".equals(op) && !completed ? "n/a" : "Step".equals(op) && completed ? "n/a" : "login";
        }
        switch (op) {
            case "View":
                return sees;
            case "List":
                return "403".equals(sees) ? "absent" : "ok";
            case "Read API", "History", "Download":
                return "ok".equals(sees) ? "ok" : "403";
            case "Excel (completed)":
                if (!completed) {
                    return "n/a";
                }
                // SoQM Team, or someone the control is shared with (assigned people: no)
                return row.soqm() || (row.place() == Place.SHARED && "ok".equals(sees)) ? "ok" : "403";
            default:
                break;
        }
        if (!"ok".equals(sees)) {
            return "Step".equals(op) && completed ? "n/a" : List.of("Step", "Return").contains(op) ? REFUSED : "403";
        }
        if (row.soqm()) {
            return switch (op) {
                case "Step" -> completed ? "n/a" : "ok";
                // Returns of the old endpoints: Control Operator to Facilitator (review), SoQM and Process Owner
                // to the Control Operator; nothing to return from draft and In Progress; completed: Move only
                case "Return" -> List.of("REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW").contains(status)
                        ? "ok" : REFUSED;
                default -> "ok";
            };
        }
        // A User with Edit in the field whose step it is: the step field, attachments, the step and its return
        boolean onStep = row.edit() && row.place() == Place.STEP && stepSlot(status) != null;
        return switch (op) {
            case "Details: step field", "Upload", "Delete own upload" -> onStep ? "ok" : "403";
            // A field the step does not own, sent with the step field's save: kept as stored (mergeControlDetails)
            case "Details: definition" -> onStep ? "ignored" : "403";
            case "Step" -> completed ? "n/a" : onStep ? "ok" : REFUSED;
            case "Return" -> onStep && !"IN_PROGRESS".equals(status) ? "ok" : REFUSED;
            default -> "403";
        };
    }

    // ---- the run ----

    @Test
    void everyRoleStatusAndOperation_answersAsDecided() throws Exception {
        facilitator = myEdit("mx-fac");
        operator = myEdit("mx-op");
        soqmLead = soqm("mx-soqm");
        owner = myEdit("mx-po");
        watcher = myEdit("mx-watch");
        for (Row row : ROWS) {
            if (row.anonymous()) {
                continue;
            }
            User user = person("mx-" + row.key(), row.level(), row.scope());
            users.put(row.key(), user);
            sessions.put(row.key(), login(user));
            if (row.disabled()) {
                disable(user);
            }
        }

        Map<String, Map<String, Map<String, String>>> actual = new LinkedHashMap<>();
        List<String> differences = new ArrayList<>();
        for (String status : STATUSES) {
            Map<String, Map<String, String>> byRow = new LinkedHashMap<>();
            for (Row row : ROWS) {
                Map<String, String> cells = run(row, status);
                byRow.put(row.key(), cells);
                for (String op : OPS) {
                    String want = expected(row, status, op);
                    String got = cells.get(op);
                    boolean same = REFUSED.equals(want) ? List.of("403", "409").contains(got) : want.equals(got);
                    if (!same) {
                        differences.add(status + " | " + row.label() + " | " + op + ": expected " + want + ", got " + got);
                    }
                }
            }
            actual.put(status, byRow);
        }
        write(actual, differences);
        assertThat(differences).as("cells that differ from the decisions").isEmpty();
    }

    private Map<String, String> run(Row row, String status) throws Exception {
        MockHttpSession session = sessions.get(row.key());
        Map<String, String> cells = new LinkedHashMap<>();
        Control read = control(row, status);
        String id = String.valueOf(read.getId());
        cells.put("View", page(session, get("/view-control/{id}", id)));
        cells.put("List", listed(session, read));
        cells.put("Read API", answer(session, get("/api/control-details").param("controlId", id)));
        cells.put("History", answer(session, get("/api/controls/{id}/changelog", id)));
        cells.put("Download", answer(session, get("/api/attachments/download/{f}", read.getAttachmentDetailsPath())
                .param("controlId", id)));
        cells.put("Control tab", write(session, row, status, c -> put("/api/controls/{id}", c.getId())
                .contentType("application/json")
                .content("{\"controlDescription\":\"Matrix description\",\"editReason\":\"Matrix\"}"),
                c -> "Matrix description".equals(reload(c).getControlDescription())));
        String stepField = List.of("PROCESS_OWNER_REVIEW", "COMPLETED").contains(status)
                ? "processOwnerComments" : "controlStepsPerformed";
        cells.put("Details: step field", write(session, row, status, c -> post("/api/control-details")
                .contentType("application/json")
                .content("{\"controlId\":" + c.getId() + ",\"" + stepField + "\":\"Matrix text\",\"editReason\":\"Matrix\"}"),
                c -> "Matrix text".equals("processOwnerComments".equals(stepField) ? details(c).getProcessOwnerComments()
                        : details(c).getControlStepsPerformed())));
        cells.put("Details: definition", write(session, row, status, c -> post("/api/control-details")
                .contentType("application/json")
                .content("{\"controlId\":" + c.getId() + ",\"processActivities\":\"Matrix activities\",\"editReason\":\"Matrix\"}"),
                c -> "Matrix activities".equals(details(c).getProcessActivities())));
        cells.put("Assignment", write(session, row, status, c -> post("/api/control-assignment")
                .contentType("application/json")
                .content("{\"controlId\":" + c.getId() + ",\"editReason\":\"Matrix\"}")));
        cells.put("Shared With", write(session, row, status, c -> post("/api/control-assignment")
                .contentType("application/json")
                .content("{\"controlId\":" + c.getId() + ",\"controlSharedWith\":[" + sharedPlus(c) + "],"
                        + "\"editReason\":\"Matrix\"}"),
                c -> String.valueOf(assignmentRepository.findByControlId(c.getId()).orElseThrow().getControlSharedWith())
                        .contains(watcher.getMail())));
        cells.put("Documents", write(session, row, status, c -> post("/api/control-documents")
                .contentType("application/json")
                .content("{\"controlId\":" + c.getId() + ",\"soqmDevelopmentMaterials\":\"Yes\",\"editReason\":\"Matrix\"}")));
        cells.put("Upload", write(session, row, status, c -> upload(c, "matrix.pdf")));
        cells.put("Delete file", write(session, row, status, c -> delete("/api/attachments/delete/{id}", c.getId())
                .param("filename", c.getAttachmentDetailsPath()).param("type", "details").param("editReason", "Matrix")));
        cells.put("Delete own upload", deleteOwnUpload(session, row, status));
        cells.put("Step", "COMPLETED".equals(status) ? "n/a" : write(session, row, status, c -> step(status, c),
                c -> !status.equals(reload(c).getPerformanceStatus())));
        cells.put("Return", write(session, row, status, c -> returnStep(status, c),
                c -> !status.equals(reload(c).getPerformanceStatus())));
        cells.put("Rename ID", write(session, row, status, c -> post("/api/controls/{id}/rename-id", c.getId())
                .contentType("application/json").content("{\"newControlId\":\"" + c.getControlId() + "-R\"}"),
                c -> reload(c).getControlId().endsWith("-R")));
        cells.put("Excel (completed)", "COMPLETED".equals(status)
                ? answer(session, get("/api/controls/{id}/export/completed", id)) : "n/a");
        return cells;
    }

    interface Request {
        MockHttpServletRequestBuilder of(Control control) throws Exception;
    }

    interface Effect {
        boolean seen(Control control);
    }

    /** A write on a control of its own. */
    private String write(MockHttpSession session, Row row, String status, Request request) throws Exception {
        return write(session, row, status, request, control -> true);
    }

    /** A write whose change can be read back: "ok" when it is there, "ignored" when 2xx left it unchanged. */
    private String write(MockHttpSession session, Row row, String status, Request request, Effect effect)
            throws Exception {
        Control control = control(row, status);
        String answer = answer(session, request.of(control));
        return "ok".equals(answer) && !effect.seen(control) ? "ignored" : answer;
    }

    private com.kpmg.qtracker.entity.ControlDetails details(Control control) {
        return detailsRepository.findByControlId(control.getId()).orElseThrow();
    }

    private String deleteOwnUpload(MockHttpSession session, Row row, String status) throws Exception {
        Control control = control(row, status);
        String name = "own-" + next() + ".pdf";
        MvcResult uploaded = call(session, upload(control, name));
        if (uploaded.getResponse().getStatus() != 200) {
            return answerOf(uploaded);
        }
        return answer(session, delete("/api/attachments/delete/{id}", control.getId())
                .param("filename", storedName(reload(control).getAttachmentDetailsPath(), name))
                .param("type", "details").param("editReason", "Matrix"));
    }

    private static String storedName(String list, String original) {
        for (String name : list.split(";")) {
            if (name.trim().endsWith(original)) {
                return name.trim();
            }
        }
        return original;
    }

    private MockHttpServletRequestBuilder upload(Control control, String name) {
        return multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", name, "application/pdf",
                        "%PDF-1.4 matrix".getBytes(StandardCharsets.UTF_8)))
                .param("editReason", "Matrix");
    }

    private String sharedPlus(Control control) {
        String stored = assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlSharedWith();
        StringBuilder out = new StringBuilder("\"" + watcher.getMail() + "\"");
        if (stored != null && !stored.isBlank()) {
            for (String mail : stored.split(",")) {
                out.append(",\"").append(mail.trim()).append('"');
            }
        }
        return out.toString();
    }

    private static MockHttpServletRequestBuilder step(String status, Control control) {
        String id = String.valueOf(control.getId());
        return switch (status) {
            case "DRAFT" -> post("/api/performance/initiate").param("controlId", id)
                    .param("soqmYear", SoqmYear.current(today()));
            case "IN_PROGRESS" -> post("/api/workflow/submit-to-control-operator").param("controlId", id)
                    .param("comments", "Matrix step");
            case "REVIEW" -> post("/api/workflow/submit-to-soqm-lead").param("controlId", id)
                    .param("comments", "Matrix step");
            case "SOQM_HEAD_REVIEW" -> post("/api/workflow/submit-to-process-owner").param("controlId", id)
                    .param("comments", "Matrix step");
            default -> post("/api/workflow/complete-control").param("controlId", id).param("comments", "Matrix step");
        };
    }

    private static MockHttpServletRequestBuilder returnStep(String status, Control control) {
        String path = "REVIEW".equals(status) ? "/api/workflow/return-to-facilitator" : "/api/workflow/return-to-operator";
        return post(path).param("controlId", String.valueOf(control.getId())).param("comments", "Matrix return");
    }

    private Control control(Row row, String status) throws Exception {
        User user = users.get(row.key());
        String slot = user == null ? null : slot(row, status);
        People people = people()
                .facilitator(with(facilitator, user, "F".equals(slot)))
                .operator(with(operator, user, "CO".equals(slot)))
                .soqm(soqmLead)
                .owner(with(owner, user, "PO".equals(slot)));
        if (user != null && row.place() == Place.SHARED) {
            people.shared(user);
        }
        return control(row.kdnControl() ? "KDN-MX" : "HR-MX", status, people);
    }

    private static User[] with(User base, User user, boolean add) {
        return add ? new User[]{base, user} : new User[]{base};
    }

    // ---- answers ----

    private String answer(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        return answerOf(call(session, request));
    }

    private static String answerOf(MvcResult result) {
        int status = result.getResponse().getStatus();
        return status >= 200 && status < 300 ? "ok" : outcome(result);
    }

    private String page(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = call(session, request);
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

    private String listed(MockHttpSession session, Control control) throws Exception {
        MvcResult result = call(session, get("/controls"));
        ModelAndView mav = result.getModelAndView();
        if (result.getResponse().getStatus() != 200 || mav == null || !"controls".equals(mav.getViewName())) {
            return outcome(result);
        }
        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> rows = (List<ControlResponseDTO>) mav.getModel().get("controls");
        return rows.stream().anyMatch(dto -> control.getId().equals(dto.getId())) ? "ok" : "absent";
    }

    // ---- the table ----

    private void write(Map<String, Map<String, Map<String, String>>> actual, List<String> differences) throws Exception {
        StringBuilder md = new StringBuilder("Generated by ReleaseScenarioMatrixIT. Cell: what the application answered; "
                + "‼ = differs from the decided rule (expected value after the arrow).\n");
        for (String status : STATUSES) {
            md.append("\n### ").append(status).append("\n\n| User | ").append(String.join(" | ", OPS)).append(" |\n|---|");
            md.append("---|".repeat(OPS.size())).append('\n');
            for (Row row : ROWS) {
                md.append("| ").append(row.label());
                for (String op : OPS) {
                    String got = actual.get(status).get(row.key()).get(op);
                    String want = expected(row, status, op);
                    boolean same = REFUSED.equals(want) ? List.of("403", "409").contains(got) : want.equals(got);
                    md.append(" | ").append(same ? got : "‼ " + got + " → " + want);
                }
                md.append(" |\n");
            }
        }
        md.append("\nDifferences: ").append(differences.size()).append('\n');
        differences.forEach(d -> md.append("- ").append(d).append('\n'));
        Path out = Path.of("target", "release-scenario", "matrix.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
    }

    // ---- operations without a control ----

    @Test
    void creatingControlsAndTheExcelOfTheList_areForSoqmTeamOnly() throws Exception {
        List<User> others = List.of(myEdit("mx-c-my"), allEdit("mx-c-all"), myRead("mx-c-ro"), allRead("mx-c-allro"),
                kdn("mx-c-kdn"));
        User soqm = soqm("mx-c-soqm");
        String json = "{\"controlId\":\"%s\",\"controlFrequency\":\"Monthly\",\"controlType\":\"Preventive\","
                + "\"controlCategory\":\"Manual\",\"component\":\"HR\",\"operatedBy\":\"Finance\",\"priority\":\"High\","
                + "\"nonAuditServicesApplicability\":\"No\",\"controlDescription\":\"New\",\"prp\":\"PRP\"}";
        for (User user : others) {
            MockHttpSession session = login(user);
            String newId = "HR-NEW-" + next() + "-" + RUN;
            assertThat(callJson(session, post("/api/controls"), json.formatted(newId)).getResponse().getStatus())
                    .as("create by %s", user.getMail()).isEqualTo(403);
            assertThat(controlRepository.findByControlId(newId)).isEmpty();
            assertThat(status(session, get("/api/controls/export/excel"))).as("excel by %s", user.getMail()).isEqualTo(403);
            assertThat(status(session, get("/new-control"))).as("new-control page by %s", user.getMail()).isIn(302, 403);
        }
        MockHttpSession session = login(soqm);
        String newId = "HR-NEW-" + next() + "-" + RUN;
        assertThat(callJson(session, post("/api/controls"), json.formatted(newId)).getResponse().getStatus()).isEqualTo(200);
        Control created = controlRepository.findByControlId(newId).orElseThrow();
        assertThat(created.getPerformanceStatus()).isEqualTo("DRAFT");
        MvcResult excel = call(session, get("/api/controls/export/excel"));
        assertThat(excel.getResponse().getStatus()).isEqualTo(200);
        assertThat(excel.getResponse().getContentType()).contains("spreadsheetml");
        assertThat(outcome(mockMvc.perform(get("/api/controls/export/excel").with(ownAddress())).andReturn()))
                .isEqualTo("login");
    }
}
