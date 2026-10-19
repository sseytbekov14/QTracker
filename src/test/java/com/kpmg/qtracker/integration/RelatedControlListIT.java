package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.config.RelatedControlsImportCommand;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.RelatedControlRef;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.RelatedControlRefRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.RelatedControlsImport;
import com.kpmg.qtracker.service.RelatedControlsImport.Outcome;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The list Other Related Controls is chosen from (related_control_ref), on a made-up Excel file of 30 rows: the import
 * (dry run, --apply, the same file again, --deactivate-missing, refusals, the command) and GET /api/related-controls
 * (SoQM Admin Team only).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:related-control-list-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "file.upload.dir=target/it-uploads-related-list"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@ExtendWith(OutputCaptureExtension.class)
class RelatedControlListIT {

    private static final String PASSWORD = "Test#123";
    /** The 24 plain Control IDs of the file, rows 2 to 25. */
    private static final List<String> IDS = IntStream.rangeClosed(1, 24)
            .mapToObj(i -> String.format("SYN-CTRL-%02d/FY27/Central", i)).toList();
    private static final int ADDED = 27; // the 24, the number of row 30, the formula of row 31, row 34

    @Autowired
    private RelatedControlsImport relatedControlsImport;
    @Autowired
    private RelatedControlsImportCommand command;
    @Autowired
    private RelatedControlRefRepository refRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    @TempDir
    Path folder;

    private static int addressCount;

    @BeforeEach
    void emptyList() {
        refRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    // ------------------------------------------------------------------ the import

    @Test
    void dryRun_reportsEveryRow_writesNothing() throws IOException {
        RelatedControlsImport.Report report = relatedControlsImport.run(synthetic(), false, false, "tester");

        assertThat(report.refused()).isFalse();
        assertThat(report.sheet()).isEqualTo("Controls");
        assertThat(report.lines()).hasSize(33); // rows 2 to 34; the header is not read
        assertThat(report.count(Outcome.ADDED)).isEqualTo(ADDED);
        Map<Integer, String> skipped = report.lines(Outcome.SKIPPED).stream()
                .collect(Collectors.toMap(RelatedControlsImport.Line::row, RelatedControlsImport.Line::reason));
        assertThat(skipped).containsOnlyKeys(26, 27, 28, 29, 32, 33);
        assertThat(skipped.get(26)).isEqualTo("empty");
        assertThat(skipped.get(27)).isEqualTo("the same Control ID as row 2 (case and spaces do not count)");
        assertThat(skipped.get(28)).isEqualTo("longer than 255 characters");
        assertThat(skipped.get(29)).isEqualTo("holds \";\", which separates the values");
        assertThat(skipped.get(32)).isEqualTo("the formula gives an error");
        assertThat(skipped.get(33)).isEqualTo("empty");
        assertThat(added(report)).contains("SYN-CTRL-01/FY27/Central", "12345", "SYN-F1", "SYN-LAST")
                .doesNotContain("OTHER-SHEET-1", "column B");

        assertThat(report.render()).contains("DRY RUN, nothing written")
                .contains("would be added: 27, would be updated: 0, unchanged: 0, skipped: 6")
                .contains("row   27  skipped: the same Control ID as row 2")
                .contains("Run again with --apply");
        assertThat(refRepository.count()).isZero();
        assertThat(auditLogRepository.count()).isZero();
    }

    @Test
    void apply_addsTheControlIds_trimmed_withOneAuditEntry() throws IOException {
        RelatedControlsImport.Report report = relatedControlsImport.run(synthetic(), true, false, "tester");

        assertThat(report.render()).contains("APPLIED").contains("added: 27");
        assertThat(stored().keySet()).hasSize(ADDED).contains("SYN-CTRL-03/FY27/Central", "12345", "SYN-F1")
                .doesNotContain(" SYN-CTRL-03/FY27/Central ");
        assertThat(stored().values()).allMatch(RelatedControlRef::isActive);
        assertThat(stored().get("SYN-CTRL-01/FY27/Central").getControlKey()).isEqualTo("syn-ctrl-01/fy27/central");
        assertThat(stored().get("SYN-CTRL-01/FY27/Central").getCreatedAt()).isNotNull();

        List<AdminAuditLog> audit = auditLogRepository.findAll();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getActionType()).isEqualTo(RelatedControlsImport.AUDIT_ACTION);
        assertThat(audit.get(0).getAdminEmail()).isEqualTo("system");
        assertThat(audit.get(0).getAdminName()).isEqualTo("Related controls import (tester)");
        assertThat(audit.get(0).getActionDescription()).isEqualTo("Other Related Controls list from controls.xlsx: "
                + "added 27, updated 0, unchanged 0, skipped 6, made inactive 0");
        assertThat(audit.get(0).getPreviousValues()).isEqualTo("active Control IDs: 0");
        assertThat(audit.get(0).getNewValues()).isEqualTo("active Control IDs: 27");
    }

    @Test
    void theSameFileAgain_changesNothing() throws IOException {
        Path file = synthetic();
        relatedControlsImport.run(file, true, false, "tester");
        Map<String, Long> ids = stored().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getId()));

        RelatedControlsImport.Report again = relatedControlsImport.run(file, true, false, "tester");

        assertThat(again.count(Outcome.ADDED)).isZero();
        assertThat(again.count(Outcome.UPDATED)).isZero();
        assertThat(again.count(Outcome.UNCHANGED)).isEqualTo(ADDED);
        assertThat(stored().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getId()))).isEqualTo(ids);
        assertThat(auditLogRepository.count()).isEqualTo(2);
    }

    @Test
    void anInactiveId_comesBack_anotherSpelling_isTaken() throws IOException {
        Path file = synthetic();
        relatedControlsImport.run(file, true, false, "tester");
        RelatedControlRef inactive = stored().get("SYN-CTRL-05/FY27/Central");
        inactive.setActive(false);
        RelatedControlRef lower = stored().get("SYN-CTRL-06/FY27/Central");
        lower.setControlId("syn-ctrl-06/fy27/central");
        refRepository.saveAll(List.of(inactive, lower));

        RelatedControlsImport.Report dry = relatedControlsImport.run(file, false, false, "tester");
        assertThat(dry.count(Outcome.UPDATED)).isEqualTo(2);
        assertThat(refRepository.findById(inactive.getId()).orElseThrow().isActive()).isFalse();

        RelatedControlsImport.Report report = relatedControlsImport.run(file, true, false, "tester");
        assertThat(report.lines(Outcome.UPDATED)).extracting(RelatedControlsImport.Line::reason)
                .containsExactly("made active again", "written \"syn-ctrl-06/fy27/central\" before");
        assertThat(refRepository.findById(inactive.getId()).orElseThrow().isActive()).isTrue();
        assertThat(refRepository.findById(lower.getId()).orElseThrow().getControlId()).isEqualTo("SYN-CTRL-06/FY27/Central");
        assertThat(refRepository.count()).isEqualTo(ADDED);
    }

    @Test
    void anIdNoLongerInTheFile_staysActive_unlessDeactivateMissing_andNothingIsDeleted() throws IOException {
        relatedControlsImport.run(synthetic(), true, false, "tester");
        Path shorter = workbook(IDS.subList(0, 20));

        RelatedControlsImport.Report kept = relatedControlsImport.run(shorter, true, false, "tester");
        assertThat(kept.deactivated()).isEmpty();
        assertThat(kept.render()).contains("made inactive: 0 (only with --deactivate-missing");
        assertThat(stored().values()).allMatch(RelatedControlRef::isActive);

        RelatedControlsImport.Report dry = relatedControlsImport.run(shorter, false, true, "tester");
        assertThat(dry.deactivated()).containsExactly("12345", "SYN-CTRL-21/FY27/Central", "SYN-CTRL-22/FY27/Central",
                "SYN-CTRL-23/FY27/Central", "SYN-CTRL-24/FY27/Central", "SYN-F1", "SYN-LAST");
        assertThat(stored().values()).allMatch(RelatedControlRef::isActive);

        relatedControlsImport.run(shorter, true, true, "tester");
        assertThat(refRepository.count()).isEqualTo(ADDED);
        assertThat(stored().get("SYN-CTRL-21/FY27/Central").isActive()).isFalse();
        assertThat(stored().get("SYN-CTRL-20/FY27/Central").isActive()).isTrue();
        assertThat(auditLogRepository.findAll().stream().max(java.util.Comparator.comparing(AdminAuditLog::getId))
                .orElseThrow().getActionDescription()).endsWith("made inactive 7");
    }

    @Test
    void aFileThatIsNoWorkbook_orMissing_isRefused_andSoIsDeactivatingEverything() throws IOException {
        Path text = folder.resolve("controls.xlsx");
        Files.writeString(text, "CONTROL ID\nSYN-1\n");
        assertThat(relatedControlsImport.run(text, true, false, "tester").refusal())
                .startsWith("the file is not an Excel workbook");
        assertThat(relatedControlsImport.run(folder.resolve("none.xlsx"), true, false, "tester").refusal())
                .startsWith("the file cannot be read");

        relatedControlsImport.run(synthetic(), true, false, "tester");
        RelatedControlsImport.Report empty = relatedControlsImport.run(workbook(List.of()), true, true, "tester");
        assertThat(empty.refused()).isTrue();
        assertThat(empty.render()).contains("REFUSED, nothing written: the file has no Control ID");
        assertThat(stored().values()).allMatch(RelatedControlRef::isActive);
        assertThat(auditLogRepository.count()).isEqualTo(1);
    }

    @Test
    void theCommand_printsTheReport_andWritesOnlyWithApply(CapturedOutput output) throws Exception {
        Path file = synthetic();
        command.run(new DefaultApplicationArguments("--import-related-controls=" + file));
        assertThat(command.getExitCode()).isZero();
        assertThat(output.getOut()).contains("DRY RUN, nothing written");
        assertThat(refRepository.count()).isZero();

        command.run(new DefaultApplicationArguments("--import-related-controls", file.toString(), "--apply"));
        assertThat(command.getExitCode()).isZero();
        assertThat(refRepository.count()).isEqualTo(ADDED);

        command.run(new DefaultApplicationArguments("--import-related-controls"));
        assertThat(command.getExitCode()).isEqualTo(1);
        assertThat(output.getOut()).contains("REFUSED, nothing written: give the file");
        assertThat(RelatedControlsImportCommand.requested("--import-related-controls=x.xlsx")).isTrue();
        assertThat(RelatedControlsImportCommand.requested("--import-users=x.csv")).isFalse();
    }

    // ------------------------------------------------------------------ GET /api/related-controls

    @Test
    void theList_givesSoqmTheActiveIds_byControlId_withASearchInAnyCase_andALimit() throws Exception {
        MockHttpSession soqm = login(saveUser("rl-soqm", AccessLevel.SOQM, AccessScope.ALL));
        assertThat(list(soqm, "")).isEmpty();

        relatedControlsImport.run(synthetic(), true, false, "tester");
        RelatedControlRef inactive = stored().get("SYN-CTRL-02/FY27/Central");
        inactive.setActive(false);
        refRepository.save(inactive);

        List<String> first = list(soqm, "");
        assertThat(first).hasSize(20).startsWith("12345", "SYN-CTRL-01/FY27/Central", "SYN-CTRL-03/FY27/Central");
        assertThat(list(soqm, "?limit=100")).hasSize(ADDED - 1).doesNotContain("SYN-CTRL-02/FY27/Central");
        assertThat(list(soqm, "?q=ctrl-1&limit=3"))
                .containsExactly("SYN-CTRL-10/FY27/Central", "SYN-CTRL-11/FY27/Central", "SYN-CTRL-12/FY27/Central");
        assertThat(list(soqm, "?q= F1 ")).containsExactly("SYN-F1");
        assertThat(list(soqm, "?q=_")).isEmpty();
        assertThat(list(soqm, "?limit=500")).hasSize(ADDED - 1);
    }

    @Test
    void theList_isRefused_toEveryoneWhoDoesNotFillTheField_KdnIncluded() throws Exception {
        relatedControlsImport.run(synthetic(), true, false, "tester");
        List<User> others = List.of(
                saveUser("rl-fac", AccessLevel.PARTICIPANT, AccessScope.OWN),
                saveUser("rl-edit-all", AccessLevel.PARTICIPANT, AccessScope.ALL),
                saveUser("rl-ro-own", AccessLevel.READ_ONLY, AccessScope.OWN),
                saveUser("rl-ro-all", AccessLevel.READ_ONLY, AccessScope.ALL),
                saveUser("rl-kdn", AccessLevel.READ_ONLY, AccessScope.KDN));
        for (User user : others) {
            assertThat(perform(get("/api/related-controls"), login(user)).getResponse().getStatus())
                    .as(user.getMail()).isEqualTo(403);
        }
        int anonymous = mockMvc.perform(get("/api/related-controls").with(ownAddress())).andReturn().getResponse()
                .getStatus();
        assertThat(anonymous).isNotEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private List<String> list(MockHttpSession session, String query) throws Exception {
        MvcResult result = perform(get("/api/related-controls" + query), session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        json.forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<String> added(RelatedControlsImport.Report report) {
        return report.lines(Outcome.ADDED).stream().map(RelatedControlsImport.Line::controlId).toList();
    }

    private Map<String, RelatedControlRef> stored() {
        return refRepository.findAll().stream()
                .collect(Collectors.toMap(RelatedControlRef::getControlId, Function.identity()));
    }

    /**
     * controls.xlsx, sheet Controls: header CONTROL ID; rows 2-25 the 24 IDs (row 4 with spaces around); row 26 empty,
     * 27 row 2 in lower case, 28 too long, 29 with ";", 30 the number 12345, 31 a formula giving SYN-F1, 32 a formula
     * giving an error, 33 missing, 34 SYN-LAST. Column B and a second sheet hold values that are not read.
     */
    private Path synthetic() throws IOException {
        Path file = folder.resolve("controls.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("Controls");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("CONTROL ID");
            header.createCell(1).setCellValue("column B");
            for (int i = 0; i < IDS.size(); i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(i == 2 ? "  " + IDS.get(i) + "  " : IDS.get(i));
                row.createCell(1).setCellValue("column B");
            }
            sheet.createRow(25).createCell(0).setCellValue("   ");
            sheet.createRow(26).createCell(0).setCellValue(IDS.get(0).toLowerCase());
            sheet.createRow(27).createCell(0).setCellValue("SYN-" + "X".repeat(260));
            sheet.createRow(28).createCell(0).setCellValue("SYN-A;SYN-B");
            sheet.createRow(29).createCell(0).setCellValue(12345d);
            sheet.createRow(30).createCell(0).setCellFormula("\"SYN-\"&\"F1\"");
            sheet.createRow(31).createCell(0).setCellFormula("1/0");
            sheet.createRow(33).createCell(0).setCellValue("SYN-LAST");
            workbook.createSheet("Other").createRow(1).createCell(0).setCellValue("OTHER-SHEET-1");
            workbook.write(out);
        }
        return file;
    }

    /** A plain file: header and these IDs. */
    private Path workbook(List<String> ids) throws IOException {
        Path file = folder.resolve("short-" + UUID.randomUUID() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("Controls");
            sheet.createRow(0).createCell(0).setCellValue("CONTROL ID");
            for (int i = 0; i < ids.size(); i++) {
                sheet.createRow(i + 1).createCell(0).setCellValue(ids.get(i));
            }
            workbook.write(out);
        }
        return file;
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setMail(name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@related-list.test");
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(false);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult login = mockMvc.perform(post("/login").with(csrf()).with(ownAddress())
                        .param("username", user.getMail())
                        .param("password", PASSWORD))
                .andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).as("login of %s", user.getMail()).isEqualTo("/");
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        return mockMvc.perform(request.with(ownAddress()).session(session)).andReturn();
    }

    /** RateLimitingFilter counts logins per address: every request comes from an address of its own. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor ownAddress() {
        String address = "10.17." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
