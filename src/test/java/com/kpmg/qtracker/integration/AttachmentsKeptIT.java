package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.IControlService;
import db.migration.V30__Documents_attachments_into_details;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Files never go except by an explicit "delete file" (2026-11-20, bug: the Control Operator's files went when the
 * Process Owner uploaded in Review). For every role and action - upload, saving a form, moving a step on or back, on
 * behalf, reopening and editing Completed, Create Control, date / frequency changes, deactivating another control,
 * parallel uploads - each file stays: on the control's list, with its upload record (author and time unchanged), and on
 * the disk with its own content (downloaded).
 */
class AttachmentsKeptIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    IControlService controlService;
    @Autowired
    DataSource dataSource;

    /** The people of one control and their sessions. */
    private final class Cast {
        final User soqm = soqm("ak-soqm");
        final User fac = myEdit("ak-fac");
        final User op = myEdit("ak-op");
        final User po = myEdit("ak-po");
        final User reader = myRead("ak-ro");
        final MockHttpSession soqmS;
        final MockHttpSession facS;
        final MockHttpSession opS;
        final MockHttpSession poS;
        final Control control;
        /** Each file uploaded so far: its stored name and its content. */
        final Map<String, String> files = new LinkedHashMap<>();
        /** Author and upload time of each file, as first recorded. */
        Map<String, String> records = new LinkedHashMap<>();

        Cast(String status) throws Exception {
            control = control("AK", status, people().facilitator(fac).operator(op).soqm(soqm).owner(po).shared(reader));
            soqmS = login(soqm);
            facS = login(fac);
            opS = login(op);
            poS = login(po);
            // The file the control is made with (ReleaseScenarioSupport.control)
            for (String name : listed(control)) {
                files.put(name, "%PDF-1.4 evidence");
            }
            records = records(control);
        }

        String up(MockHttpSession session, String name) throws Exception {
            String content = "%PDF-1.4 " + name + " " + System.nanoTime();
            MvcResult result = call(session, multipart("/api/attachments/upload/{id}", control.getId())
                    .file(new MockMultipartFile("attachmentDetails", name, "application/pdf",
                            content.getBytes(StandardCharsets.UTF_8))));
            assertThat(result.getResponse().getStatus()).as(name + ": " + body(result)).isEqualTo(200);
            String stored = json(result).path("detailsFiles").asText();
            files.put(stored, content);
            return stored;
        }

        /** Every file so far on the list, its record unchanged, on the disk with its content. */
        void allKept(String after) throws Exception {
            List<String> listed = listed(control);
            assertThat(listed).as(after + ": list").containsAll(files.keySet());
            Map<String, String> now = records(control);
            for (Map.Entry<String, String> record : records.entrySet()) {
                assertThat(now).as(after + ": record of " + record.getKey()).containsEntry(record.getKey(), record.getValue());
            }
            records = now;
            for (Map.Entry<String, String> file : files.entrySet()) {
                MvcResult download = call(soqmS, get("/api/attachments/download/{name}", file.getKey())
                        .param("controlId", String.valueOf(control.getId())));
                assertThat(download.getResponse().getStatus()).as(after + ": download " + file.getKey()).isEqualTo(200);
                assertThat(download.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .as(after + ": content of " + file.getKey()).isEqualTo(file.getValue());
            }
        }

        void details(MockHttpSession session, String field, String text) throws Exception {
            MvcResult r = callJson(session, post("/api/control-details"),
                    "{\"controlId\":" + control.getId() + ",\"" + field + "\":\"" + text + "\"}");
            assertThat(r.getResponse().getStatus()).as(field + ": " + body(r)).isEqualTo(200);
        }

        void move(MockHttpSession session, String target, String comment) throws Exception {
            MvcResult r = call(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                    .param("targetStatus", target).param("comments", comment));
            assertThat(r.getResponse().getStatus()).as("move to " + target + ": " + body(r)).isEqualTo(200);
        }
    }

    private List<String> listed(Control control) {
        String list = jdbc.queryForObject("SELECT attachment_details_path FROM controls WHERE id = ?", String.class,
                control.getId());
        return list == null ? List.of() : Arrays.stream(list.split(";")).map(String::trim).filter(n -> !n.isEmpty()).toList();
    }

    private Map<String, String> records(Control control) {
        Map<String, String> records = new LinkedHashMap<>();
        jdbc.query("SELECT file_name, uploaded_by_email, uploaded_at FROM control_attachments WHERE control_id = ?"
                + " AND tab = 'DETAILS'", rs -> {
            records.put(rs.getString(1), rs.getString(2) + "@" + rs.getTimestamp(3));
        }, control.getId());
        return records;
    }

    // ---------------------------------------------------------------- A, B, C: each role on its step

    @Test
    void eachRole_uploadsAndSaves_onItsStep_andMovesOn_theOthersFilesStay() throws Exception {
        Cast c = new Cast("IN_PROGRESS");
        c.up(c.facS, "fac-1.pdf");
        c.allKept("facilitator upload");
        c.details(c.facS, "controlStepsPerformed", "Steps by the facilitator");
        c.allKept("facilitator saves the form");
        c.up(c.soqmS, "soqm-in-progress.pdf");
        c.move(c.facS, "REVIEW", "");
        c.allKept("to Review");

        c.up(c.opS, "op-1.pdf");
        c.allKept("operator upload");
        c.details(c.opS, "controlStepsPerformed", "Steps checked by the operator");
        c.allKept("operator saves the form");
        c.move(c.opS, "SOQM_HEAD_REVIEW", "");
        c.allKept("to SoQM Admin Team Review");

        c.up(c.soqmS, "soqm-1.pdf");
        c.details(c.soqmS, "soqmHeadComments", "SoQM checked");
        assertThat(status(c.soqmS, put("/api/controls/{id}", c.control.getId()).contentType("application/json")
                .content("{\"priority\":\"Low\"}"))).isEqualTo(200);
        c.allKept("SoQM upload and saves");
        c.move(c.soqmS, "PROCESS_OWNER_REVIEW", "");
        c.allKept("to Process Owner Review");

        // The reported case: the Process Owner uploads in the final review
        c.up(c.poS, "po-1.pdf");
        c.allKept("process owner upload");
        c.details(c.poS, "processOwnerComments", "PO reviewed");
        c.allKept("process owner saves the form");
        c.move(c.poS, "COMPLETED", "");
        c.allKept("completed");
    }

    @Test
    void returns_onBehalf_reopenAndCompletedEdit_keepEveryFile() throws Exception {
        Cast c = new Cast("REVIEW");
        c.details(c.soqmS, "controlStepsPerformed", "Steps");
        c.up(c.opS, "op-review.pdf");
        c.move(c.opS, "IN_PROGRESS", "Redo the sample");
        c.allKept("operator returns to the facilitator");
        c.up(c.facS, "fac-after-return.pdf");
        c.move(c.facS, "REVIEW", "");
        // On behalf: SoQM Admin Team moves the operator's step
        c.move(c.soqmS, "SOQM_HEAD_REVIEW", "On behalf of the operator");
        c.allKept("SoQM on behalf of the operator");
        c.details(c.soqmS, "soqmHeadComments", "SoQM");
        c.move(c.soqmS, "PROCESS_OWNER_REVIEW", "");
        c.up(c.poS, "po-before-return.pdf");
        c.move(c.poS, "REVIEW", "Back to the operator");
        c.allKept("process owner returns to the operator");
        c.up(c.opS, "op-after-return.pdf");
        c.move(c.opS, "SOQM_HEAD_REVIEW", "");
        c.move(c.soqmS, "REVIEW", "SoQM returns");
        c.allKept("SoQM returns to the operator");
        c.move(c.opS, "SOQM_HEAD_REVIEW", "");
        c.move(c.soqmS, "PROCESS_OWNER_REVIEW", "");
        c.details(c.poS, "processOwnerComments", "PO");
        c.move(c.poS, "COMPLETED", "");
        c.allKept("completed");
        // Completed changed in place, with a reason: a form save and an upload
        assertThat(status(c.soqmS, put("/api/controls/{id}", c.control.getId()).contentType("application/json")
                .content("{\"priority\":\"Low\",\"editReason\":\"Typo\"}"))).isEqualTo(200);
        MvcResult upload = call(c.soqmS, multipart("/api/attachments/upload/{id}", c.control.getId())
                .file(new MockMultipartFile("attachmentDetails", "after-completion.pdf", "application/pdf",
                        "%PDF-1.4 late".getBytes(StandardCharsets.UTF_8))).param("editReason", "Missing evidence"));
        assertThat(upload.getResponse().getStatus()).as(body(upload)).isEqualTo(200);
        c.files.put(json(upload).path("detailsFiles").asText(), "%PDF-1.4 late");
        c.allKept("completed edit");
        // Reopened to an earlier step
        c.move(c.soqmS, "IN_PROGRESS", "Reopened");
        c.allKept("reopened");
    }

    @Test
    void aStaleCopyOfTheControl_savedAfterAnUpload_doesNotDropTheFile() throws Exception {
        // What a form save in another tab, or one running beside the upload, does: it read the control before
        Cast c = new Cast("IN_PROGRESS");
        Control stale = controlRepository.findById(c.control.getId()).orElseThrow();
        c.up(c.facS, "uploaded-meanwhile.pdf");
        stale.setControlDescription("Saved from the other tab");
        controlService.updateControl(stale);
        c.allKept("stale save after an upload");
        // And the other way round: the stale copy still holds a file deleted since - it does not come back
        Control stale2 = controlRepository.findById(c.control.getId()).orElseThrow();
        String gone = c.up(c.facS, "to-delete.pdf");
        assertThat(status(c.facS, delete("/api/attachments/delete/{id}", c.control.getId()).param("filename", gone))).isEqualTo(200);
        c.files.remove(gone);
        c.records.remove(gone);
        controlService.updateControl(stale2);
        assertThat(listed(c.control)).doesNotContain(gone);
        c.allKept("stale save after a delete");
    }

    @Test
    void severalFilesInOneRequest_andTwoInARow_allStay() throws Exception {
        Cast c = new Cast("IN_PROGRESS");
        MockMultipartHttpServletRequestBuilder request = multipart("/api/attachments/upload/{id}", c.control.getId());
        for (String name : List.of("one.pdf", "two.pdf", "three.pdf")) {
            request.file(new MockMultipartFile("attachmentDetails", name, "application/pdf",
                    ("%PDF-1.4 " + name).getBytes(StandardCharsets.UTF_8)));
        }
        MvcResult result = call(c.facS, request);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        for (String name : json(result).path("detailsFiles").asText().split(";")) {
            c.files.put(name, "%PDF-1.4 " + name);
        }
        c.up(c.facS, "four.pdf");
        c.up(c.facS, "five.pdf");
        c.allKept("several and in a row");
    }

    // ---------------------------------------------------------------- D: same name, migrated files, other actions

    @Test
    void theSameName_fromTwoRoles_bothStay_withTheirOwnContent() throws Exception {
        Cast c = new Cast("REVIEW");
        String first = c.up(c.opS, "evidence-same.pdf");
        String second = c.up(c.soqmS, "evidence-same.pdf");
        assertThat(second).isNotEqualTo(first);
        c.allKept("same name twice");
    }

    @Test
    void filesCopiedFromDocuments_stay() throws Exception {
        Cast c = new Cast("REVIEW");
        String folder = com.kpmg.qtracker.service.FileStorageService.controlFolder(c.control.getControlId(), c.control.getId());
        String stored = fileStorageService.saveFile(new MockMultipartFile("f", "old-documents.pdf", "application/pdf",
                "%PDF-1.4 documents".getBytes(StandardCharsets.UTF_8)), folder);
        jdbc.update("UPDATE controls SET attachment_documents_path = ? WHERE id = ?", stored, c.control.getId());
        jdbc.update("INSERT INTO control_attachments (control_id, tab, file_name, uploaded_by_email, uploaded_stage, uploaded_at)"
                + " VALUES (?, 'DOCUMENTS', ?, ?, 'REVIEW', CURRENT_TIMESTAMP)", c.control.getId(), stored, c.op.getMail());
        try (Connection connection = dataSource.getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V30__Documents_attachments_into_details().migrate(context);
        }
        c.files.put(stored, "%PDF-1.4 documents");
        c.records = records(c.control);
        c.up(c.opS, "after-migration.pdf");
        c.details(c.opS, "controlStepsPerformed", "Steps");
        c.move(c.opS, "SOQM_HEAD_REVIEW", "");
        c.allKept("migrated file, upload, save, move");
    }

    @Test
    void createControl_dateAndFrequencyChanges_andDeactivatingAnother_leaveTheFiles() throws Exception {
        Cast c = new Cast("COMPLETED");
        // Create Control: the source keeps its files, the new cycle gets none
        MvcResult next = callJson(c.soqmS, post("/api/controls/{id}/next-cycle", c.control.getId()),
                "{\"controlId\":\"" + c.control.getControlId() + "-NEXT\"}");
        assertThat(next.getResponse().getStatus()).as(body(next)).isEqualTo(200);
        long nextId = json(next).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT attachment_details_path FROM controls WHERE id = ?", String.class, nextId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM control_attachments WHERE control_id = ?", Integer.class, nextId)).isZero();
        c.allKept("Create Control");
        // Another control: date / frequency changed, then deactivated
        Cast other = new Cast("DRAFT");
        MvcResult dates = callJson(other.soqmS, post("/api/control-assignment"), "{\"controlId\":" + other.control.getId()
                + ",\"facilitator\":[\"" + other.fac.getMail() + "\"],\"controlOperator\":[\"" + other.op.getMail()
                + "\"],\"soqmLead\":[\"" + other.soqm.getMail() + "\"],\"processOwner\":[\"" + other.po.getMail()
                + "\"],\"controlOperationDate\":\"" + LocalDate.now().plusDays(9) + "\"}");
        assertThat(dates.getResponse().getStatus()).as(body(dates)).isEqualTo(200);
        assertThat(status(other.soqmS, put("/api/controls/{id}", other.control.getId()).contentType("application/json")
                .content("{\"controlFrequency\":\"Quarterly\"}"))).isEqualTo(200);
        other.allKept("date and frequency changed");
        assertThat(status(other.soqmS, post("/api/controls/{id}/deactivate", other.control.getId())
                .contentType("application/json").content("{\"confirmControlId\":\"" + other.control.getControlId() + "\"}")))
                .isEqualTo(200);
        c.allKept("another control deactivated");
    }

    // ---------------------------------------------------------------- E: who may not

    @ParameterizedTest
    @ValueSource(strings = {"read-only", "not-assigned", "kdn"})
    void readOnly_notAssigned_andKdn_cannotUploadOrDelete_kdnDownloads(String who) throws Exception {
        User soqm = soqm("akn-soqm");
        User fac = myEdit("akn-fac");
        User reader = myRead("akn-ro");
        User outsider = allEdit("akn-out");
        User kdnUser = kdn("akn-kdn");
        Control control = control("KDN-AKN", "IN_PROGRESS", people().facilitator(fac).operator(myEdit("akn-op"))
                .soqm(soqm).owner(myEdit("akn-po")).shared(reader));
        MockHttpSession facS = login(fac);
        MvcResult up = call(facS, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", "fac.pdf", "application/pdf", "%PDF-1.4 fac".getBytes())));
        String stored = json(up).path("detailsFiles").asText();
        User user = switch (who) {
            case "read-only" -> reader;
            case "not-assigned" -> outsider;
            default -> kdnUser;
        };
        MockHttpSession session = login(user);
        assertThat(status(session, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", "x.pdf", "application/pdf", "%PDF-1.4 x".getBytes()))))
                .as(who + " upload").isEqualTo(403);
        assertThat(status(session, delete("/api/attachments/delete/{id}", control.getId()).param("filename", stored)))
                .as(who + " delete").isEqualTo(403);
        assertThat(listed(control)).contains(stored);
        int download = status(session, get("/api/attachments/download/{name}", stored)
                .param("controlId", String.valueOf(control.getId())));
        if ("kdn".equals(who) || "read-only".equals(who)) {
            assertThat(download).as(who + " download").isEqualTo(200);
        }
    }

    @Test
    void anotherParticipantsFile_isNotTheirsToDelete_soqmMay() throws Exception {
        Cast c = new Cast("REVIEW");
        String opFile = c.up(c.opS, "op-own.pdf");
        String soqmFile = c.up(c.soqmS, "soqm-own.pdf");
        assertThat(status(c.facS, delete("/api/attachments/delete/{id}", c.control.getId()).param("filename", opFile))).isEqualTo(403);
        assertThat(status(c.poS, delete("/api/attachments/delete/{id}", c.control.getId()).param("filename", opFile))).isEqualTo(403);
        assertThat(status(c.opS, delete("/api/attachments/delete/{id}", c.control.getId()).param("filename", soqmFile))).isEqualTo(403);
        c.allKept("refused deletes");
        assertThat(status(c.opS, delete("/api/attachments/delete/{id}", c.control.getId()).param("filename", opFile))).isEqualTo(200);
        c.files.remove(opFile);
        c.records.remove(opFile);
        assertThat(status(c.soqmS, delete("/api/attachments/delete/{id}", c.control.getId()).param("filename", soqmFile))).isEqualTo(200);
        c.files.remove(soqmFile);
        c.records.remove(soqmFile);
        assertThat(listed(c.control)).doesNotContain(opFile, soqmFile);
        c.allKept("own deletes");
    }

    // ---------------------------------------------------------------- F: at the same time

    @Test
    void twoRolesUpload_atTheSameTime_bothStay_alsoUnderOneName() throws Exception {
        Cast c = new Cast("REVIEW");
        for (String[] names : new String[][] {{"parallel-op.pdf", "parallel-soqm.pdf"}, {"parallel-same.pdf", "parallel-same.pdf"}}) {
            String opContent = "%PDF-1.4 op " + names[0];
            String soqmContent = "%PDF-1.4 soqm " + names[1];
            List<MvcResult> results = together(
                    () -> call(c.opS, multipart("/api/attachments/upload/{id}", c.control.getId())
                            .file(new MockMultipartFile("attachmentDetails", names[0], "application/pdf", opContent.getBytes()))),
                    () -> call(c.soqmS, multipart("/api/attachments/upload/{id}", c.control.getId())
                            .file(new MockMultipartFile("attachmentDetails", names[1], "application/pdf", soqmContent.getBytes()))));
            assertThat(results.get(0).getResponse().getStatus()).as(body(results.get(0))).isEqualTo(200);
            assertThat(results.get(1).getResponse().getStatus()).as(body(results.get(1))).isEqualTo(200);
            String opStored = json(results.get(0)).path("detailsFiles").asText();
            String soqmStored = json(results.get(1)).path("detailsFiles").asText();
            assertThat(opStored).isNotEqualTo(soqmStored);
            c.files.put(opStored, opContent);
            c.files.put(soqmStored, soqmContent);
            c.allKept("parallel " + names[0]);
        }
    }

    private static List<MvcResult> together(java.util.concurrent.Callable<MvcResult> first,
                                            java.util.concurrent.Callable<MvcResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<MvcResult> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> answers = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
