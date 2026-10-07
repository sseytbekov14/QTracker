package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attachment download for everyone who sees the control: SoQM Team, a User on the control (Edit or Read Only),
 * a User who sees all controls, KDN on a KDN control. Others get 403, a file the control does not list or that
 * is gone from disk 404 - always as JSON, never as a text body the browser would save in place of the file.
 * Names with Cyrillic letters, spaces and underscores, files from both tabs and old files from the upload root.
 */
@SpringBootTest(properties = "file.upload.dir=target/it-uploads-download")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AttachmentDownloadIT {

    private static final Path UPLOADS = Path.of("target/it-uploads-download").toAbsolutePath();
    private static final String MISSING = "missing_on_disk.pdf";

    /** Details tab files, by name with their content type. */
    private static final Map<String, String> DETAILS = new LinkedHashMap<>();
    /** Documents tab files, by name with their content type. */
    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DETAILS.put("Отчёт_за_май.pdf", "application/pdf");
        DETAILS.put("Evidence_file_1.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        DETAILS.put("old_form.doc", "application/msword");
        DOCUMENTS.put("Сверка кассы.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        DOCUMENTS.put("ledger_2025.xls", "application/vnd.ms-excel");
        DOCUMENTS.put("data_export.csv", "text/csv");
        DOCUMENTS.put("scan_01.png", "image/png");
        DOCUMENTS.put("Фото проверки.jpg", "image/jpeg");
        DOCUMENTS.put("photo_2.jpeg", "image/jpeg");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private UserRepository userRepository;

    private final List<User> users = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private String s;
    private String legacyRootFile;
    private User soqm;
    private User assigned;
    private User notAssigned;
    private User kdn;
    private User readOnlyShared;
    private User readOnlyOutsider;
    private User readOnlyAll;
    private User editAll;
    private Control hr;
    private Control kdnControl;

    @BeforeEach
    void setUp() throws Exception {
        s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("dl-soqm-" + s, AccessLevel.SOQM, AccessScope.ALL);
        assigned = saveUser("dl-fac-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        notAssigned = saveUser("dl-other-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        kdn = saveUser("dl-kdn-" + s, AccessLevel.READ_ONLY, AccessScope.KDN);
        readOnlyShared = saveUser("dl-ro-shared-" + s, AccessLevel.READ_ONLY, AccessScope.OWN);
        readOnlyOutsider = saveUser("dl-ro-out-" + s, AccessLevel.READ_ONLY, AccessScope.OWN);
        readOnlyAll = saveUser("dl-ro-all-" + s, AccessLevel.READ_ONLY, AccessScope.ALL);
        editAll = saveUser("dl-edit-all-" + s, AccessLevel.PARTICIPANT, AccessScope.ALL);

        // Uploaded before control folders existed: in the upload root
        legacyRootFile = "20250101_120000_" + s + "_legacy.pdf";
        Files.createDirectories(UPLOADS);
        Files.writeString(UPLOADS.resolve(legacyRootFile), "legacy " + s);

        hr = control("HR-DL-" + s + "/FY26/KZ",
                String.join(";", DETAILS.keySet()),
                String.join(";", DOCUMENTS.keySet()) + ";" + legacyRootFile + ";" + MISSING);
        Path hrFolder = Files.createDirectories(UPLOADS.resolve("HR-DL-" + s + "_FY26_KZ"));
        for (String name : DETAILS.keySet()) {
            Files.writeString(hrFolder.resolve(name), "details " + name);
        }
        for (String name : DOCUMENTS.keySet()) {
            Files.writeString(hrFolder.resolve(name), "documents " + name);
        }

        kdnControl = control("KDN-DL-" + s, "kdn_cash.pdf", null);
        Files.writeString(Files.createDirectories(UPLOADS.resolve("KDN-DL-" + s)).resolve("kdn_cash.pdf"), "kdn " + s);
    }

    @AfterEach
    void tearDown() {
        for (Control control : controls) {
            assignmentRepository.findByControlId(control.getId()).ifPresent(assignmentRepository::delete);
            controlRepository.deleteById(control.getId());
        }
        users.forEach(u -> userRepository.findById(u.getId()).ifPresent(userRepository::delete));
    }

    @Test
    void soqmTeam_downloadsEveryFileOfBothTabs_withItsNameAndType() throws Exception {
        Map<String, String> all = new LinkedHashMap<>(DETAILS);
        all.putAll(DOCUMENTS);
        for (Map.Entry<String, String> file : all.entrySet()) {
            String tab = DETAILS.containsKey(file.getKey()) ? "details " : "documents ";
            MvcResult result = download(soqm, hr, file.getKey())
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, file.getValue()))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().bytes((tab + file.getKey()).getBytes(StandardCharsets.UTF_8)))
                    .andReturn();
            ContentDisposition disposition = ContentDisposition.parse(
                    result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
            assertThat(disposition.isAttachment()).as(file.getKey()).isTrue();
            // filename*=UTF-8''... keeps Cyrillic letters, spaces and underscores
            assertThat(disposition.getFilename()).isEqualTo(file.getKey());
            assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("filename*=UTF-8''");
        }
        download(soqm, kdnControl, "kdn_cash.pdf").andExpect(status().isOk()).andExpect(content().string("kdn " + s));
    }

    @Test
    void oldFileInTheUploadRoot_isDownloaded() throws Exception {
        download(assigned, hr, legacyRootFile)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(content().string("legacy " + s));
    }

    @Test
    void user_onTheControl_downloads_userNotOnIt_gets403Json() throws Exception {
        download(assigned, hr, "Отчёт_за_май.pdf").andExpect(status().isOk());
        download(assigned, hr, "Сверка кассы.xlsx").andExpect(status().isOk());
        // Sees every control (Visibility All) with Edit access
        download(editAll, hr, "Отчёт_за_май.pdf").andExpect(status().isOk());

        expectJson(download(notAssigned, hr, "Отчёт_за_май.pdf"), 403, "ACCESS_DENIED", "Access denied");
        expectJson(download(notAssigned, kdnControl, "kdn_cash.pdf"), 403, "ACCESS_DENIED", "Access denied");
    }

    @Test
    void kdn_downloadsFromAKdnControl_notFromAnother() throws Exception {
        download(kdn, kdnControl, "kdn_cash.pdf")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(content().string("kdn " + s));

        expectJson(download(kdn, hr, "Отчёт_за_май.pdf"), 403, "ACCESS_DENIED", "Access denied");
    }

    @Test
    void readOnlyUser_sharedOrSeeingAll_downloads_otherReadOnlyUser_gets403() throws Exception {
        download(readOnlyShared, hr, "Сверка кассы.xlsx").andExpect(status().isOk());
        download(readOnlyAll, hr, "Отчёт_за_май.pdf").andExpect(status().isOk());
        download(readOnlyAll, kdnControl, "kdn_cash.pdf").andExpect(status().isOk());

        expectJson(download(readOnlyOutsider, hr, "Отчёт_за_май.pdf"), 403, "ACCESS_DENIED", "Access denied");
    }

    @Test
    void fileListedButGoneFromDisk_is404Json_andAWarningNamesControlAndFile(CapturedOutput output) throws Exception {
        expectJson(download(soqm, hr, MISSING), 404, "FILE_NOT_FOUND", "File not found");
        expectJson(download(kdn, kdnControl, MISSING), 404, "FILE_NOT_FOUND", "File not found");

        assertThat(output).contains("Attachment missing on disk: control HR-DL-" + s + "/FY26/KZ (id " + hr.getId()
                + "), file " + MISSING);
        // Which control and file, not where the files lie
        String warning = output.getAll().lines().filter(line -> line.contains("Attachment missing on disk")
                && line.contains(s)).findFirst().orElseThrow();
        assertThat(warning).contains("WARN").doesNotContain(UPLOADS.toString()).doesNotContain("it-uploads-download");
    }

    @Test
    void fileTheControlDoesNotList_isNotServed_even_ifOnDisk() throws Exception {
        // On disk in the KDN control's folder, asked for through the HR control
        expectJson(download(soqm, hr, "kdn_cash.pdf"), 404, "FILE_NOT_FOUND", "File not found");
        expectJson(download(soqm, hr, "never_uploaded.pdf"), 404, "FILE_NOT_FOUND", "File not found");
    }

    @Test
    void requestWithoutControl_orForAnUnknownControl_isJson() throws Exception {
        expectJson(mockMvc.perform(get("/api/attachments/download/{name}", "kdn_cash.pdf")
                        .sessionAttr("currentUser", soqm).with(user(soqm.getMail()).roles("SOQM"))),
                400, "BAD_REQUEST", "controlId is required");
        Control unknown = new Control();
        unknown.setId(Long.MAX_VALUE);
        expectJson(download(soqm, unknown, "kdn_cash.pdf"), 404, "NOT_FOUND", "Control not found");
    }

    // ------------------------------------------------------------------ helpers

    private static void expectJson(ResultActions actions, int status, String code, String message) throws Exception {
        actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message));
    }

    private ResultActions download(User reader, Control control, String name) throws Exception {
        return mockMvc.perform(get("/api/attachments/download/{name}", name)
                .param("controlId", String.valueOf(control.getId()))
                .sessionAttr("currentUser", reader)
                .with(user(reader.getMail()).roles(String.valueOf(reader.getAccessLevel()))));
    }

    /** Facilitator: the assigned user; shared with the Read Only user; SoQM lead: the SoQM user. */
    private Control control(String controlId, String details, String documents) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus("IN_PROGRESS");
        control.setCreatedBy(soqm);
        control.setCreatedAt(LocalDateTime.now());
        control.setAttachmentDetailsPath(details);
        control.setAttachmentDocumentsPath(documents);
        control = controlRepository.save(control);
        controls.add(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseGet(ControlAssignment::new);
        assignment.setControlId(control.getId());
        assignment.setFacilitator(assigned.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setControlSharedWith(readOnlyShared.getMail());
        assignmentRepository.save(assignment);
        return control;
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = userRepository.save(TestUsers.user(name + "@example.test", level, scope, false));
        users.add(user);
        return user;
    }
}
