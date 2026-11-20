package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import db.migration.V30__Documents_attachments_into_details;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * V30 on a running application: a file that was only on the Documents tab is, after the migration, on the Details
 * list with its upload record and downloads with its content; the number of files is the same before and after.
 */
class DocumentsIntoDetailsIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    @Test
    void aDocumentsFile_isInDetails_andDownloads() throws Exception {
        User soqm = soqm("did-soqm");
        User operator = myEdit("did-op");
        Control control = control("HR-DID", "REVIEW", people().facilitator(myEdit("did-fac")).operator(operator)
                .soqm(soqm).owner(myEdit("did-po")));
        // As before the change: a file on disk in the control's folder, listed only on the Documents tab
        String folder = com.kpmg.qtracker.service.FileStorageService.controlFolder(control.getControlId(), control.getId());
        String stored = fileStorageService.saveFile(new MockMultipartFile("f", "operator-evidence.pdf", "application/pdf",
                "%PDF-1.4 operator".getBytes(StandardCharsets.UTF_8)), folder);
        jdbc.update("UPDATE controls SET attachment_documents_path = ? WHERE id = ?", stored, control.getId());
        jdbc.update("INSERT INTO control_attachments (control_id, tab, file_name, uploaded_by_email, uploaded_stage, uploaded_at)"
                + " VALUES (?, 'DOCUMENTS', ?, ?, 'REVIEW', CURRENT_TIMESTAMP)", control.getId(), stored, operator.getMail());
        String detailsBefore = jdbc.queryForObject("SELECT attachment_details_path FROM controls WHERE id = ?",
                String.class, control.getId());
        int filesBefore = names(detailsBefore).length + 1;

        try (Connection connection = dataSource.getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V30__Documents_attachments_into_details().migrate(context);
            new V30__Documents_attachments_into_details().migrate(context);
        }

        String details = jdbc.queryForObject("SELECT attachment_details_path FROM controls WHERE id = ?", String.class,
                control.getId());
        assertThat(names(details)).hasSize(filesBefore).contains(stored);
        assertThat(jdbc.queryForObject("SELECT uploaded_by_email FROM control_attachments WHERE control_id = ?"
                + " AND tab = 'DETAILS' AND file_name = ?", String.class, control.getId(), stored)).isEqualTo(operator.getMail());

        MockHttpSession session = login(soqm);
        assertThat(json(call(session, get("/api/attachments/info/{id}", control.getId())))
                .path("attachmentDetailsPath").asText()).contains(stored);
        MvcResult download = call(session, get("/api/attachments/download/{name}", stored).param("controlId",
                String.valueOf(control.getId())));
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4 operator");
    }

    private static String[] names(String list) {
        return list == null || list.isBlank() ? new String[0]
                : java.util.Arrays.stream(list.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
