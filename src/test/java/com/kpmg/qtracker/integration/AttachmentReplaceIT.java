package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * A file of an attached name (2026-11-23): Keep both (no replace) keeps the two, the new one as "name (1)"; Replace puts
 * the new content under the same name - for whoever may delete the attached file, not on a completed control - with
 * the upload record and the audit saying who replaced it.
 */
class AttachmentReplaceIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

    private MvcResult upload(MockHttpSession session, Control control, String name, String content, String... replace)
            throws Exception {
        var request = multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", name, "application/pdf", content.getBytes(StandardCharsets.UTF_8)));
        for (String target : replace) {
            request.param("replace", target);
        }
        return call(session, request);
    }

    private List<String> listed(Control control) {
        String list = jdbc.queryForObject("SELECT attachment_details_path FROM controls WHERE id = ?", String.class, control.getId());
        return list == null ? List.of() : Arrays.stream(list.split(";")).map(String::trim).filter(n -> !n.isEmpty()).toList();
    }

    private String content(MockHttpSession session, Control control, String name) throws Exception {
        return call(session, get("/api/attachments/download/{n}", name).param("controlId", String.valueOf(control.getId())))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void keepBoth_replace_byWhoMay_andTheRecordSaysWho() throws Exception {
        User soqm = soqm("rp-soqm");
        User op = myEdit("rp-op");
        User fac = myEdit("rp-fac");
        Control control = control("RP", "REVIEW", people().facilitator(fac).operator(op).soqm(soqm).owner(myEdit("rp-po")));
        MockHttpSession opS = login(op);
        MockHttpSession soqmS = login(soqm);
        int before = listed(control).size();

        assertThat(upload(opS, control, "scan.pdf", "%PDF-1.4 v1").getResponse().getStatus()).isEqualTo(200);
        // Keep both: no replace
        assertThat(upload(opS, control, "scan.pdf", "%PDF-1.4 copy").getResponse().getStatus()).isEqualTo(200);
        assertThat(listed(control)).contains("scan.pdf", "scan (1).pdf").hasSize(before + 2);
        // Replace by the uploader in the same step: same name, new content, no new entry
        MvcResult replaced = upload(opS, control, "Scan.PDF", "%PDF-1.4 v2", "scan.pdf");
        assertThat(replaced.getResponse().getStatus()).as(body(replaced)).isEqualTo(200);
        assertThat(json(replaced).path("replacedFiles").asText()).isEqualTo("scan.pdf");
        assertThat(listed(control)).hasSize(before + 2);
        assertThat(content(soqmS, control, "scan.pdf")).isEqualTo("%PDF-1.4 v2");
        assertThat(content(soqmS, control, "scan (1).pdf")).isEqualTo("%PDF-1.4 copy");
        // Not the uploader: refused, nothing changes
        assertThat(upload(login(fac), control, "scan.pdf", "%PDF-1.4 fac", "scan.pdf").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(content(soqmS, control, "scan.pdf")).isEqualTo("%PDF-1.4 v2");
        // SoQM Admin Team may replace anyone's; the record now names them
        assertThat(upload(soqmS, control, "scan.pdf", "%PDF-1.4 v3", "scan.pdf").getResponse().getStatus()).isEqualTo(200);
        assertThat(content(soqmS, control, "scan.pdf")).isEqualTo("%PDF-1.4 v3");
        assertThat(jdbc.queryForObject("SELECT uploaded_by_email FROM control_attachments WHERE control_id = ? AND tab = 'DETAILS'"
                + " AND file_name = 'scan.pdf'", String.class, control.getId())).isEqualTo(soqm.getMail());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE control_id = ? AND action_type = 'ATTACHMENT_REPLACED'",
                Integer.class, control.getId())).isEqualTo(2);
        // A replace of a name that is not attached is a plain upload
        assertThat(upload(opS, control, "new.pdf", "%PDF-1.4 new", "new.pdf").getResponse().getStatus()).isEqualTo(200);
        assertThat(listed(control)).contains("new.pdf");
    }

    @Test
    void onACompletedControl_replaceIsRefused_keepBothWorks() throws Exception {
        User soqm = soqm("rp2-soqm");
        Control control = control("RP2", "COMPLETED", people().facilitator(myEdit("rp2-fac")).operator(myEdit("rp2-op"))
                .soqm(soqm).owner(myEdit("rp2-po")));
        MockHttpSession session = login(soqm);
        String attached = listed(control).get(0);
        var request = multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", attached, "application/pdf", "%PDF-1.4 x".getBytes()))
                .param("replace", attached).param("editReason", "Better scan");
        MvcResult refused = call(session, request);
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused)).contains("cannot be replaced on a completed control");
        var keep = multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", attached, "application/pdf", "%PDF-1.4 x".getBytes()))
                .param("editReason", "Better scan");
        assertThat(call(session, keep).getResponse().getStatus()).isEqualTo(200);
        assertThat(listed(control)).hasSize(2);
    }
}
