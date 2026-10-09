package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.SoqmYear;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release test pass, stage D: the fields of the Control, Details, Assignment and Documents tabs - what each step
 * requires, lengths and limits, text of every kind (HTML, emoji, Cyrillic, line breaks, spaces at the ends), XSS
 * strings on the page and in the Excel, the two steps fields, Shared With, the Control ID and what a save keeps.
 */
class ReleaseScenarioFieldsIT extends ReleaseScenarioSupport {

    private static final String XSS = "<script>alert('x')</script><img src=x onerror=alert(1)>";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // ---- required fields on each move ----

    @Test
    void initiate_listsEveryMissingField_andEveryStepRequiresItsField() throws Exception {
        User soqm = soqm("rq-soqm");
        User fac = myEdit("rq-fac");
        User op = myEdit("rq-op");
        User po = myEdit("rq-po");
        MockHttpSession session = login(soqm);

        Control empty = control("RS-RQ", "DRAFT", people(), c -> c.setSoqmYear(null));
        ControlAssignment assignment = assignmentRepository.findByControlId(empty.getId()).orElseThrow();
        assignment.setControlOperationDate(null);
        assignmentRepository.save(assignment);
        MvcResult refused = call(session, post("/api/performance/initiate").param("controlId", String.valueOf(empty.getId())));
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused)).contains("Facilitator", "Control Operator", "SoQM Team / Delegate", "Process Owner",
                "Control Operation Date", "SoQM Year");
        assertThat(reload(empty).getPerformanceStatus()).isEqualTo("DRAFT");

        // Steps: the steps field (blank or spaces), SoQM comments, Process Owner comments
        People everyone = people().facilitator(fac).operator(op).soqm(soqm).owner(po);
        for (String status : List.of("IN_PROGRESS", "REVIEW")) {
            Control control = control("RS-RQ", status, everyone);
            ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
            details.setControlStepsPerformed("   ");
            detailsRepository.save(details);
            User actor = "IN_PROGRESS".equals(status) ? fac : op;
            String path = "IN_PROGRESS".equals(status) ? "/api/workflow/submit-to-control-operator"
                    : "/api/workflow/submit-to-soqm-lead";
            MvcResult result = call(login(actor), post(path).param("controlId", String.valueOf(control.getId())));
            assertThat(result.getResponse().getStatus()).as(status).isEqualTo(400);
            assertThat(body(result)).contains("Control Steps Performed and Results");
        }
        Control soqmReview = control("RS-RQ", "SOQM_HEAD_REVIEW", everyone);
        ControlDetails details = detailsRepository.findByControlId(soqmReview.getId()).orElseThrow();
        details.setSoqmHeadComments("");
        detailsRepository.save(details);
        assertThat(body(call(session, post("/api/workflow/submit-to-process-owner")
                .param("controlId", String.valueOf(soqmReview.getId()))))).contains("SoQM Head/Team Comments");
        Control ownerReview = control("RS-RQ", "PROCESS_OWNER_REVIEW", everyone);
        details = detailsRepository.findByControlId(ownerReview.getId()).orElseThrow();
        details.setProcessOwnerComments(null);
        detailsRepository.save(details);
        assertThat(body(call(login(po), post("/api/workflow/complete-control")
                .param("controlId", String.valueOf(ownerReview.getId()))))).contains("Process Owner Comments");

        // The next person must be there: no Control Operator, no submit to them
        Control noOperator = control("RS-RQ", "IN_PROGRESS", people().facilitator(fac).soqm(soqm).owner(po));
        assertThat(status(login(fac), post("/api/workflow/submit-to-control-operator")
                .param("controlId", String.valueOf(noOperator.getId())))).isEqualTo(400);
        assertThat(reload(noOperator).getPerformanceStatus()).isEqualTo("IN_PROGRESS");
    }

    // ---- lengths ----

    @Test
    void lengths_atTheLimitAreSaved_oneMoreIsRefusedWithTheFieldName_nothingIsCut() throws Exception {
        User soqm = soqm("ln-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("RS-LN", "IN_PROGRESS", people().soqm(soqm));
        String id = String.valueOf(control.getId());

        // Control tab: description and PRP 1000
        assertThat(saveControl(session, control, "controlDescription", "d".repeat(1000))).isEqualTo(200);
        assertThat(reload(control).getControlDescription()).hasSize(1000);
        MvcResult tooLong = callJson(session, put("/api/controls/{id}", id),
                json("controlDescription", "d".repeat(1001)));
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooLong)).contains("Control Description must be at most 1000 characters");
        assertThat(reload(control).getControlDescription()).hasSize(1000);

        // Details: 255 fields and 2000 text areas, with line breaks counted as they are sent
        String lines = ("line\n").repeat(400);
        assertThat(lines).hasSize(2000);
        assertThat(saveDetails(session, control, "processName", "p".repeat(255))).isEqualTo(200);
        assertThat(saveDetails(session, control, "processName", "p".repeat(256))).isEqualTo(400);
        assertThat(saveDetails(session, control, "controlStepsPerformed", lines)).isEqualTo(200);
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo(lines);
        assertThat(saveDetails(session, control, "controlStepsPerformed", lines + "x")).isEqualTo(400);
        assertThat(saveDetails(session, control, "processActivities", "a".repeat(2001))).isEqualTo(400);

        // A new Control ID: 255 kept, 256 refused
        String longId = "HR-" + RUN + "-" + "9".repeat(255 - 4 - RUN.length());
        assertThat(longId).hasSize(255);
        assertThat(callJson(session, post("/api/controls"), newControl(longId)).getResponse().getStatus()).isEqualTo(200);
        assertThat(callJson(session, post("/api/controls"), newControl(longId + "9")).getResponse().getStatus())
                .isEqualTo(400);

        // A return comment: 2000 kept, 2001 refused
        Control review = control("RS-LN", "REVIEW", people().soqm(soqm).facilitator(myEdit("ln-fac"))
                .operator(myEdit("ln-op")));
        assertThat(status(session, post("/api/workflow/return-to-facilitator").param("controlId", String.valueOf(review.getId()))
                .param("comments", "c".repeat(2001)))).isEqualTo(400);
        assertThat(status(session, post("/api/workflow/return-to-facilitator").param("controlId", String.valueOf(review.getId()))
                .param("comments", "c".repeat(2000)))).isEqualTo(200);
    }

    // ---- text of every kind ----

    @Test
    void htmlEmojiCyrillicLineBreaksAndSpaces_areSavedAndReadBackAsTyped() throws Exception {
        User soqm = soqm("tx-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("RS-TX", "IN_PROGRESS", people().soqm(soqm));
        List<String> texts = List.of(
                "<b>bold</b> & \"quotes\" 'single' <tag attr=\"1\">",
                "Контроль сверки 😀👍🏽 ✅",
                "first line\nsecond line\r\nthird line",
                "Tab\there; percent %d; braces {0} ${x} #{y} \\ backslash",
                XSS);
        for (String text : texts) {
            assertThat(saveDetails(session, control, "controlStepsPerformed", text)).as(text).isEqualTo(200);
            JsonNode read = json(call(session, get("/api/control-details").param("controlId", String.valueOf(control.getId()))));
            assertThat(read.path("controlStepsPerformed").asText()).as(text).isEqualTo(text);
            assertThat(saveControl(session, control, "controlDescription", text)).as(text).isEqualTo(200);
            assertThat(reload(control).getControlDescription()).as(text).isEqualTo(text);
        }
        // Spaces at the ends: recorded as the application keeps them (see the report)
        assertThat(saveDetails(session, control, "processName", "  padded name  ")).isEqualTo(200);
        String kept = detailsRepository.findByControlId(control.getId()).orElseThrow().getProcessName();
        assertThat(kept).isIn("  padded name  ", "padded name");
    }

    @Test
    void xssStrings_areEscapedOnThePage_andAreTextCellsInTheExcel() throws Exception {
        User soqm = soqm("xs-soqm");
        User po = myEdit("xs-po");
        MockHttpSession session = login(soqm);
        Control control = control("RS-XSS", "COMPLETED", people().soqm(soqm).owner(po).facilitator(myEdit("xs-f"))
                .operator(myEdit("xs-o")), c -> c.setControlDescription(XSS));
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setControlStepsPerformed(XSS);
        details.setControlOperatorReview(XSS);
        details.setProcessOwnerComments("=HYPERLINK(\"http://evil.test\",\"click\")");
        details.setProcessName("+SUM(1,2)");
        details.setDepartment("@cmd");
        detailsRepository.save(details);

        String page = body(call(session, get("/view-control/{id}", control.getId())));
        assertThat(page).doesNotContain("<script>alert('x')</script>").doesNotContain("<img src=x onerror");
        assertThat(page).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");

        MvcResult excel = call(session, get("/api/controls/{id}/export/completed", control.getId()));
        assertThat(excel.getResponse().getStatus()).isEqualTo(200);
        List<String> formulas = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.FORMULA) {
                            formulas.add(cell.getAddress() + " " + cell.getCellFormula());
                        } else if (cell.getCellType() == CellType.STRING) {
                            values.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        assertThat(formulas).as("no formula cells").isEmpty();
        assertThat(values).anyMatch(v -> v.contains("HYPERLINK")).anyMatch(v -> v.contains("<script>"));
        // The list export too
        MvcResult list = call(session, get("/api/controls/export/excel"));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(list.getResponse().getContentAsByteArray()))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        assertThat(cell.getCellType()).as(cell.getAddress().toString()).isNotEqualTo(CellType.FORMULA);
                    }
                }
            }
        }
    }

    // ---- the two steps fields and the Program ----

    @Test
    void stepsField_facilitatorInProgress_operatorInReview_programOnlySoqmTeam() throws Exception {
        User soqm = soqm("sf-soqm");
        User fac = myEdit("sf-fac");
        User op = myEdit("sf-op");
        User po = myEdit("sf-po");
        People everyone = people().facilitator(fac).operator(op).soqm(soqm).owner(po);
        MockHttpSession facSession = login(fac);
        MockHttpSession opSession = login(op);
        MockHttpSession poSession = login(po);
        MockHttpSession soqmSession = login(soqm);

        Control inProgress = control("RS-SF", "IN_PROGRESS", everyone);
        assertThat(saveDetails(facSession, inProgress, "controlStepsPerformed", "F text")).isEqualTo(200);
        assertThat(saveDetails(opSession, inProgress, "controlStepsPerformed", "CO too early")).isEqualTo(403);
        Control review = control("RS-SF", "REVIEW", everyone);
        assertThat(saveDetails(opSession, review, "controlStepsPerformed", "CO text")).isEqualTo(200);
        assertThat(saveDetails(facSession, review, "controlStepsPerformed", "F too late")).isEqualTo(403);
        assertThat(detailsRepository.findByControlId(review.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("CO text");

        for (Control control : List.of(inProgress, review)) {
            for (MockHttpSession participant : List.of(facSession, opSession, poSession)) {
                assertThat(saveDetails(participant, control, "controlOperatorReview", "Program by a participant"))
                        .isEqualTo(403);
            }
            assertThat(saveDetails(soqmSession, control, "controlOperatorReview", "Program pasted by SoQM")).isEqualTo(200);
            assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlOperatorReview())
                    .isEqualTo("Program pasted by SoQM");
        }
    }

    // ---- Shared With ----

    @Test
    void sharedWith_duplicatesAndCase_unknownDisabledAndKdnPeople() throws Exception {
        User soqm = soqm("sw-soqm");
        User reader = myRead("sw-reader");
        User gone = disable(myEdit("sw-gone"));
        User kdnUser = kdn("sw-kdn");
        MockHttpSession session = login(soqm);
        People everyone = people().soqm(soqm).facilitator(myEdit("sw-fac")).operator(myEdit("sw-op"))
                .owner(myEdit("sw-po"));
        Control control = control("RS-SW", "IN_PROGRESS", everyone);
        Control kdnControl = control("KDN-SW", "IN_PROGRESS", everyone);

        MvcResult repeated = callJson(session, post("/api/control-assignment"),
                sharedJson(control, reader.getMail(), reader.getMail(), " " + reader.getMail() + " "));
        assertThat(repeated.getResponse().getStatus()).as(body(repeated)).isEqualTo(200);
        // Stored as sent, repeats and spaces included (the page never sends them: S3-13 in the report); the
        // person gets access all the same
        String stored = assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlSharedWith();
        assertThat(stored).contains(reader.getMail());
        assertThat(status(login(reader), get("/view-control/{id}", control.getId()))).isEqualTo(200);
        // Another case of the same address is the same person
        assertThat(saveShared(session, control, reader.getMail().toUpperCase())).isEqualTo(200);
        assertThat(status(login(reader), get("/view-control/{id}", control.getId()))).isEqualTo(200);

        MvcResult unknown = callJson(session, post("/api/control-assignment"), sharedJson(control,
                "nobody-" + RUN + "@release.test"));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(unknown)).contains("nobody-" + RUN);
        // A disabled person may stay listed (marked "Disabled" for SoQM Team), and gets no access
        assertThat(saveShared(session, control, gone.getMail())).isEqualTo(200);
        assertThat(signIn(gone.getMail(), PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(saveShared(session, control, kdnUser.getMail())).isEqualTo(400);
        assertThat(saveShared(session, kdnControl, kdnUser.getMail())).isEqualTo(200);
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlSharedWith())
                .doesNotContain("nobody-").doesNotContain(kdnUser.getMail());
    }

    // ---- Control ID ----

    @Test
    void controlId_generatedUniqueTrimmed_kdnRule_andRenameKeepsTheFilesAndChangesWhoSeesIt() throws Exception {
        User soqm = soqm("id-soqm");
        User kdnUser = kdn("id-kdn");
        MockHttpSession session = login(soqm);

        MvcResult generated = call(session, get("/api/controls/generate-id").param("component", "HR")
                .param("frequency", "Monthly"));
        assertThat(generated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(generated)).isNotBlank();

        String id = "HR-UQ-" + RUN + "-" + next();
        assertThat(callJson(session, post("/api/controls"), newControl(id)).getResponse().getStatus()).isEqualTo(200);
        assertThat(callJson(session, post("/api/controls"), newControl(id)).getResponse().getStatus()).isIn(400, 409);
        // The same ID with spaces around: the page trims it, the API stores it as sent (S3-11 in the report)
        MvcResult spaced = callJson(session, post("/api/controls"), newControl("  " + id + "  "));
        assertThat(spaced.getResponse().getStatus()).isIn(200, 400, 409);
        MvcResult otherCase = callJson(session, post("/api/controls"), newControl(id.toLowerCase()));
        // Case: recorded as it is (see the report)
        assertThat(otherCase.getResponse().getStatus()).isIn(200, 400, 409);

        // KDN rule: starts with KDN after trimming, any case
        Control kdnLike = control("kdn-ID", "IN_PROGRESS", people().soqm(soqm));
        Control inside = control("X-KDN-ID", "IN_PROGRESS", people().soqm(soqm));
        MockHttpSession kdnSession = login(kdnUser);
        assertThat(status(kdnSession, get("/view-control/{id}", kdnLike.getId()))).isEqualTo(200);
        assertThat(status(kdnSession, get("/view-control/{id}", inside.getId()))).isEqualTo(403);

        // Rename to an ID in use: refused; to a KDN ID: comment needed, files follow, KDN then sees it
        Control hr = control("HR-RN", "IN_PROGRESS", people().soqm(soqm));
        String file = reload(hr).getAttachmentDetailsPath();
        assertThat(renameStatus(session, hr, kdnLike.getControlId(), "x")).isIn(400, 409);
        String newId = "KDN-RN-" + RUN + "-" + next();
        assertThat(renameStatus(session, hr, newId, null)).isEqualTo(400);
        assertThat(renameStatus(session, hr, newId, "Now a KDN control")).isEqualTo(200);
        assertThat(reload(hr).getControlId()).isEqualTo(newId);
        assertThat(status(session, get("/api/attachments/download/{f}", file).param("controlId", String.valueOf(hr.getId()))))
                .isEqualTo(200);
        assertThat(status(kdnSession, get("/view-control/{id}", hr.getId()))).isEqualTo(200);
        assertThat(status(kdnSession, get("/api/attachments/download/{f}", file).param("controlId", String.valueOf(hr.getId()))))
                .isEqualTo(200);
    }

    // ---- what a save keeps ----

    @Test
    void savingATab_keepsWhatItDoesNotSend_refusesBlankRequiredFields_andKeepsOtherTabs() throws Exception {
        User soqm = soqm("dl-soqm");
        User fac = myEdit("dl-fac");
        MockHttpSession session = login(soqm);
        Control control = control("RS-DL", "IN_PROGRESS", people().soqm(soqm).facilitator(fac).operator(myEdit("dl-op"))
                .owner(myEdit("dl-po")));
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setHomogeneity("Homogeneous");
        details.setReferencesToControl("REF-1");
        details.setProcessName("Payroll");
        detailsRepository.save(details);

        // Control tab with one field
        assertThat(saveControl(session, control, "prp", "PRP 2")).isEqualTo(200);
        Control after = reload(control);
        assertThat(after.getControlDescription()).isEqualTo("Release test control");
        assertThat(after.getControlType()).isEqualTo("Preventive");
        assertThat(after.getControlFrequency()).isEqualTo("Monthly");
        // A required field sent blank is refused, nothing saved
        assertThat(saveControl(session, control, "controlType", "")).isEqualTo(400);
        assertThat(saveControl(session, control, "controlType", "   ")).isEqualTo(400);
        assertThat(reload(control).getControlType()).isEqualTo("Preventive");
        // A value that is not in the list: recorded as the application keeps it (see the report)
        int unlisted = saveControl(session, control, "controlType", "Made-up type");
        assertThat(unlisted).isIn(200, 400);

        // Details with one field: the others stay, the Details fields shared with the control row too
        assertThat(saveDetails(session, control, "department", "Finance 2")).isEqualTo(200);
        ControlDetails afterDetails = detailsRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(afterDetails.getProcessName()).isEqualTo("Payroll");
        assertThat(afterDetails.getHomogeneity()).isEqualTo("Homogeneous");
        assertThat(afterDetails.getReferencesToControl()).isEqualTo("REF-1");
        assertThat(afterDetails.getControlStepsPerformed()).isEqualTo("Steps performed");
        assertThat(afterDetails.getSoqmHeadComments()).isEqualTo("SoQM comments");
        // ... and the next Control tab save does not write the row's old copy back over them
        assertThat(saveControl(session, control, "controlDescription", "After details")).isEqualTo(200);
        assertThat(saveDetails(session, control, "soqmHeadComments", "SoQM comments 2")).isEqualTo(200);
        assertThat(saveControl(session, control, "prp", "PRP 3")).isEqualTo(200);
        afterDetails = detailsRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(afterDetails.getSoqmHeadComments()).isEqualTo("SoQM comments 2");
        assertThat(afterDetails.getHomogeneity()).isEqualTo("Homogeneous");

        // Assignment with only Shared With: the people and the date stay
        ControlAssignment before = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(saveShared(session, control, myRead("dl-reader").getMail())).isEqualTo(200);
        ControlAssignment afterAssignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(afterAssignment.getFacilitator()).isEqualTo(before.getFacilitator());
        assertThat(afterAssignment.getProcessOwner()).isEqualTo(before.getProcessOwner());
        assertThat(afterAssignment.getControlOperationDate()).isEqualTo(before.getControlOperationDate());
        assertThat(afterAssignment.getControlOperationDeadline()).isEqualTo(before.getControlOperationDeadline());
        // Removing a required person by sending an empty list is refused
        MvcResult noFacilitator = callJson(session, post("/api/control-assignment"),
                "{\"controlId\":" + control.getId() + ",\"facilitator\":[]}");
        assertThat(noFacilitator.getResponse().getStatus()).isIn(200, 400);
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getFacilitator())
                .as("Facilitator after an empty list (status %s)", noFacilitator.getResponse().getStatus())
                .isNotBlank();
    }

    // ---- helpers ----

    private int saveControl(MockHttpSession session, Control control, String field, String value) throws Exception {
        return callJson(session, put("/api/controls/{id}", control.getId()), json(field, value)).getResponse().getStatus();
    }

    private int saveDetails(MockHttpSession session, Control control, String field, String value) throws Exception {
        ObjectNode node = JSON.objectNode().put("controlId", control.getId()).put(field, value);
        return callJson(session, post("/api/control-details"), node.toString()).getResponse().getStatus();
    }

    private int saveShared(MockHttpSession session, Control control, String... mails) throws Exception {
        return callJson(session, post("/api/control-assignment"), sharedJson(control, mails)).getResponse().getStatus();
    }

    private static String sharedJson(Control control, String... mails) {
        ObjectNode node = JSON.objectNode().put("controlId", control.getId());
        var list = node.putArray("controlSharedWith");
        for (String mail : mails) {
            list.add(mail);
        }
        return node.toString();
    }

    private int renameStatus(MockHttpSession session, Control control, String newId, String comment) throws Exception {
        ObjectNode node = JSON.objectNode().put("newControlId", newId);
        if (comment != null) {
            node.put("comment", comment);
        }
        return callJson(session, post("/api/controls/{id}/rename-id", control.getId()), node.toString())
                .getResponse().getStatus();
    }

    private static String json(String field, String value) {
        return JSON.objectNode().put(field, value).toString();
    }

    private static String newControl(String controlId) {
        return JSON.objectNode().put("controlId", controlId).put("controlFrequency", "Monthly")
                .put("controlCategory", "Manual").put("controlType", "Preventive").put("component", "HR")
                .put("operatedBy", "Finance").put("priority", "High").put("nonAuditServicesApplicability", "No")
                .put("controlDescription", "New").put("prp", "PRP").put("soqmYear", SoqmYear.current(today()))
                .toString();
    }
}
