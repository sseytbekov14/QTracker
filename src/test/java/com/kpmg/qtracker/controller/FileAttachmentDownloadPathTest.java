package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAttachmentService;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlRenameService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Download on the real file storage, without Spring Security: no request firewall stands in front,
 * so the names that would leave the control folder reach the controller and must be refused there.
 */
class FileAttachmentDownloadPathTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private FileStorageService storage;
    private final User user = new User();

    @BeforeEach
    void setUp() throws Exception {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        Files.createDirectories(uploads.resolve("HR1"));
        Files.createDirectories(uploads.resolve("HR2"));
        Files.writeString(uploads.resolve("HR1").resolve("report.pdf"), "in folder");
        Files.writeString(uploads.resolve("HR2").resolve("secret.pdf"), "other control");
        Files.writeString(uploads.resolve("20260101_abcd1234_old.pdf"), "in root");
        Files.writeString(tempDir.resolve("outside.txt"), "outside uploads");

        storage = spy(new FileStorageService());
        ReflectionTestUtils.setField(storage, "uploadDir", uploads.toString());

        // Listed on the control as if a list had been tampered with: the storage is the last line
        Control control = new Control();
        control.setId(1L);
        control.setControlId("HR1");
        control.setAttachmentDetailsPath("report.pdf;20260101_abcd1234_old.pdf;gone.pdf;../HR2/secret.pdf;"
                + "..\\HR2\\secret.pdf;../../outside.txt");

        ControlService controlService = mock(ControlService.class);
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.requireReadable(1L, user)).thenReturn(control);
        ControlAttachmentService attachmentService =
                new ControlAttachmentService(mock(ControlAttachmentRepository.class), controlService);

        ControlRenameService renameService = mock(ControlRenameService.class);
        when(renameService.attachmentFolders(control)).thenReturn(List.of("HR1"));
        FileAttachmentController controller = new FileAttachmentController(storage, controlService,
                mock(ControlPermissionService.class), mock(AdminAuditService.class), attachmentService,
                permissionService, renameService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listedFiles_comeFromTheControlFolder_orTheUploadRoot() throws Exception {
        download("report.pdf").andExpect(status().isOk()).andExpect(content().string("in folder"));
        download("20260101_abcd1234_old.pdf").andExpect(status().isOk()).andExpect(content().string("in root"));
    }

    @Test
    void fileOfAnotherControl_isNotServed() throws Exception {
        download("secret.pdf").andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("File not found"));
    }

    @Test
    void listedFileMissingOnDisk_is404Json() throws Exception {
        download("gone.pdf").andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void withoutControlId_is400Json() throws Exception {
        mockMvc.perform(get("/api/attachments/download/report.pdf").sessionAttr("currentUser", user))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("controlId is required"));
    }

    @Test
    void namesThatLeaveTheFolder_areRefused_evenWhenListed() throws Exception {
        // Decoded once by Spring: "../HR2/secret.pdf"
        download("..%2FHR2%2Fsecret.pdf").andExpect(status().isNotFound());
        // Decoded by Spring, then by the controller: "%2e%2e%2f..." becomes "../HR2/secret.pdf"
        download("%252e%252e%252fHR2%252fsecret.pdf").andExpect(status().isNotFound());
        // Backslashes: "..\HR2\secret.pdf"
        download("..%5CHR2%5Csecret.pdf").andExpect(status().isNotFound());
        download("%2e%2e%2f%2e%2e%2foutside.txt").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));

        // The names did reach the storage, which refused them
        verify(storage, org.mockito.Mockito.times(2)).downloadFile(eq("../HR2/secret.pdf"), eq(List.of("HR1")));
        verify(storage).downloadFile(eq("..\\HR2\\secret.pdf"), eq(List.of("HR1")));
        verify(storage).downloadFile(eq("../../outside.txt"), eq(List.of("HR1")));
    }

    private org.springframework.test.web.servlet.ResultActions download(String encodedName) throws Exception {
        return mockMvc.perform(get(URI.create("/api/attachments/download/" + encodedName + "?controlId=1"))
                .sessionAttr("currentUser", user));
    }
}
