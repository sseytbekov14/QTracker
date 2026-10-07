package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAttachmentService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.ControlRenameService;
import com.kpmg.qtracker.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FileAttachmentController.class)
@TestPropertySource(properties = "file.upload.max-file-size-mb=1")
@AutoConfigureMockMvc(addFilters = false)
class FileAttachmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileStorageService fileStorageService;

    @MockBean
    private ControlService controlService;

    @MockBean
    private ControlPermissionService controlPermissionService;

    @MockBean
    private AdminAuditService adminAuditService;

    @MockBean
    private ControlAttachmentService controlAttachmentService;

    @MockBean
    private PermissionService permissionService;

    @MockBean
    private ControlRenameService controlRenameService;

    @Test
    void uploadDetails_overLimit_returnsBadRequest() throws Exception {
        Control control = new Control();
        control.setId(1L);
        control.setControlId("HR11");
        control.setAttachmentDetailsPath(buildList(50));
        when(controlService.getControlById(1L)).thenReturn(Optional.of(control));

        User user = new User();

        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setMail("user@test.com");
        user.setDisplayName("Test User");
        when(controlPermissionService.resolve(any(Control.class), any(User.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true, false, false, false, false, false));

        MockMultipartFile file = new MockMultipartFile(
                "attachmentDetails",
                "file.pdf",
                "application/pdf",
                "data".getBytes()
        );

        mockMvc.perform(multipart("/api/attachments/upload/1").file(file)
                        .sessionAttr("currentUser", user))
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

        User user = new User();

        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setMail("user@test.com");
        user.setDisplayName("Test User");
        when(controlPermissionService.resolve(any(Control.class), any(User.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true, false, false, false, false, false));

        MockMultipartFile file = new MockMultipartFile(
                "attachmentDocuments",
                "file.pdf",
                "application/pdf",
                "data".getBytes()
        );

        mockMvc.perform(multipart("/api/attachments/upload/2").file(file)
                        .sessionAttr("currentUser", user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Maximum 50 files allowed for Documents attachments."));
    }

    @Test
    void uploadDetails_logsAttachmentAdded() throws Exception {
        Control control = new Control();
        control.setId(3L);
        control.setControlId("HR13");
        when(controlService.getControlById(3L)).thenReturn(Optional.of(control));
        when(fileStorageService.saveFile(any(), any())).thenReturn("test.pdf");
        when(controlService.updateControl(any(Control.class))).thenReturn(control);

        User user = new User();

        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setMail("user@test.com");
        user.setDisplayName("Test User");
        when(controlPermissionService.resolve(any(Control.class), any(User.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true, false, false, false, false, false));

        MockMultipartFile file = new MockMultipartFile(
                "attachmentDetails",
                "test.pdf",
                "application/pdf",
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

        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setMail("user@test.com");
        user.setDisplayName("Test User");
        when(controlPermissionService.resolve(any(Control.class), any(User.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true, false, false, false, false, false));
        when(controlAttachmentService.canDelete(any(Control.class), eq("DETAILS"), eq("old.txt"), any(User.class), any()))
                .thenReturn(true);
        when(controlAttachmentService.removeFromControl(any(Control.class), eq("DETAILS"), eq("old.txt"))).thenReturn(true);

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

    @Test
    void upload_fileTooLarge_returnsBadRequestAndSavesNothing() throws Exception {
        User user = mockEditableControl(5L, null);
        MockMultipartFile file = new MockMultipartFile(
                "attachmentDetails", "big.pdf", "application/pdf", new byte[1024 * 1024 + 1]);

        mockMvc.perform(multipart("/api/attachments/upload/5").file(file).sessionAttr("currentUser", user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("File \"big.pdf\" exceeds the maximum size of 1 MB."));

        verify(fileStorageService, never()).saveFile(any(), any());
    }

    @Test
    void upload_nameAlreadyAttached_returnsBadRequest() throws Exception {
        User user = mockEditableControl(6L, "Report.pdf;other.pdf");
        MockMultipartFile file = new MockMultipartFile(
                "attachmentDetails", "report.PDF", "application/pdf", "data".getBytes());

        mockMvc.perform(multipart("/api/attachments/upload/6").file(file).sessionAttr("currentUser", user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "File \"report.PDF\" is already attached in Details. Delete the existing file or rename the new one."));

        verify(fileStorageService, never()).saveFile(any(), any());
    }

    @Test
    void upload_sameNameTwiceInSelection_returnsBadRequest() throws Exception {
        User user = mockEditableControl(7L, null);
        MockMultipartFile first = new MockMultipartFile(
                "attachmentDetails", "a.pdf", "application/pdf", "1".getBytes());
        MockMultipartFile second = new MockMultipartFile(
                "attachmentDetails", "a.pdf", "application/pdf", "2".getBytes());

        mockMvc.perform(multipart("/api/attachments/upload/7").file(first).file(second)
                        .sessionAttr("currentUser", user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File \"a.pdf\" is selected more than once for Details."));

        verify(fileStorageService, never()).saveFile(any(), any());
    }

    @Test
    void upload_unsupportedType_returnsBadRequest() throws Exception {
        User user = mockEditableControl(8L, null);
        MockMultipartFile file = new MockMultipartFile(
                "attachmentDocuments", "script.exe", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart("/api/attachments/upload/8").file(file).sessionAttr("currentUser", user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(fileStorageService, never()).saveFile(any(), any());
    }

    private User mockEditableControl(Long id, String detailsPath) {
        Control control = new Control();
        control.setId(id);
        control.setControlId("HR" + id);
        control.setAttachmentDetailsPath(detailsPath);
        when(controlService.getControlById(id)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(any(Control.class), any(User.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true, false, false, false, false, false));
        User user = new User();
        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setMail("user@test.com");
        user.setDisplayName("Test User");
        return user;
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

