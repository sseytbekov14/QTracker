package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FileAttachmentController.class)
class FileAttachmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileStorageService fileStorageService;

    @MockBean
    private ControlService controlService;

    @MockBean
    private AdminAuditService adminAuditService;

    @Test
    void uploadDetails_overLimit_returnsBadRequest() throws Exception {
        Control control = new Control();
        control.setId(1L);
        control.setControlId("HR11");
        control.setAttachmentDetailsPath(buildList(50));
        when(controlService.getControlById(1L)).thenReturn(Optional.of(control));

        MockMultipartFile file = new MockMultipartFile(
                "attachmentDetails",
                "file.txt",
                "text/plain",
                "data".getBytes()
        );

        mockMvc.perform(multipart("/api/attachments/upload/1").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Maximum 50 files allowed for Details attachments."));
    }

    @Test
    void uploadDocuments_overLimit_returnsBadRequest() throws Exception {
        Control control = new Control();
        control.setId(2L);
        control.setControlId("HR12");
        control.setAttachmentDocumentsPath(buildList(50));
        when(controlService.getControlById(2L)).thenReturn(Optional.of(control));

        MockMultipartFile file = new MockMultipartFile(
                "attachmentDocuments",
                "file.txt",
                "text/plain",
                "data".getBytes()
        );

        mockMvc.perform(multipart("/api/attachments/upload/2").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Maximum 50 files allowed for Documents attachments."));
    }

    @Test
    void uploadDetails_logsAttachmentAdded() throws Exception {
        Control control = new Control();
        control.setId(3L);
        control.setControlId("HR13");
        when(controlService.getControlById(3L)).thenReturn(Optional.of(control));
        when(fileStorageService.saveFile(any(), any())).thenReturn("test.txt");
        when(controlService.updateControl(any(Control.class))).thenReturn(control);

        User user = new User();
        user.setMail("user@test.com");
        user.setDisplayName("Test User");

        MockMultipartFile file = new MockMultipartFile(
                "attachmentDetails",
                "test.txt",
                "text/plain",
                "data".getBytes()
        );

        mockMvc.perform(multipart("/api/attachments/upload/3")
                        .file(file)
                        .sessionAttr("currentUser", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(adminAuditService).logActionWithChanges(
                eq("user@test.com"),
                eq("Test User"),
                eq("ATTACHMENT_ADDED"),
                any(Control.class),
                eq("Attachment DETAILS"),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void deleteDetails_logsAttachmentRemoved() throws Exception {
        Control control = new Control();
        control.setId(4L);
        control.setControlId("HR14");
        control.setAttachmentDetailsPath("old.txt");
        when(controlService.getControlById(4L)).thenReturn(Optional.of(control));
        when(controlService.updateControl(any(Control.class))).thenReturn(control);

        User user = new User();
        user.setMail("user@test.com");
        user.setDisplayName("Test User");

        mockMvc.perform(delete("/api/attachments/delete/4")
                        .param("filename", "old.txt")
                        .param("type", "details")
                        .sessionAttr("currentUser", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(adminAuditService).logActionWithChanges(
                eq("user@test.com"),
                eq("Test User"),
                eq("ATTACHMENT_REMOVED"),
                any(Control.class),
                eq("Attachment DETAILS"),
                anyString(),
                anyString(),
                anyString()
        );
    }

    private String buildList(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append("file").append(i).append(".txt");
        }
        return builder.toString();
    }
}
