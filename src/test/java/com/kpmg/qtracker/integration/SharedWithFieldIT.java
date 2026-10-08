package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.HtmlUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Control Shared With on View Control as the server renders it: every stored address as a chip (a repeated
 * one once), the marks of AccessPolicy.sharedAccess for SoQM Team, and the search only for SoQM Team on a
 * control they may change. Everyone else only sees the names.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:shared-with-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads-shared-with"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class SharedWithFieldIT {

    private static final String PASSWORD = "Test#123";
    private static final Pattern CHIP = Pattern.compile("<li class=\"sw-chip[^\"]*\"([^>]*)>(.*?)</li>", Pattern.DOTALL);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    private static int addressCount;

    private User soqm;
    private User fac;
    private User op;
    private User po;
    private User reader;
    private User readerAll;
    private User off;
    private User kdn;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("soqm-" + s, AccessLevel.SOQM, AccessScope.ALL, true);
        fac = saveUser("fac-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        op = saveUser("op-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        po = saveUser("po-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        reader = saveUser("reader-" + s, AccessLevel.READ_ONLY, AccessScope.OWN, true);
        readerAll = saveUser("reader-all-" + s, AccessLevel.READ_ONLY, AccessScope.ALL, true);
        off = saveUser("off-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        kdn = saveUser("kdn-" + s, AccessLevel.READ_ONLY, AccessScope.KDN, true);
    }

    @Test
    void soqmTeam_seesEveryStoredAddressOnce_withWhatThePlaceGivesEach_andTheSearch() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 6) + "@outside.test";
        Control control = control("HR", "IN_PROGRESS", String.join(",", reader.getMail(),
                reader.getMail().toUpperCase(), off.getMail(), ghost, kdn.getMail(), readerAll.getMail()));

        String field = field(control, login(soqm));
        List<Chip> chips = chips(field);

        // Stored order, the repeated address (another case) once, the address without a user kept as written
        assertThat(chips).extracting(Chip::mail)
                .containsExactly(reader.getMail(), off.getMail(), ghost, kdn.getMail(), readerAll.getMail());
        assertThat(field).contains(">5 people<");
        assertThat(chip(chips, reader.getMail()).html()).contains(">" + reader.getDisplayName() + "<")
                .contains(">" + reader.getMail() + "<").doesNotContain("sw-note");
        assertThat(chip(chips, off.getMail()).html()).contains(">Disabled<");
        assertThat(chip(chips, off.getMail()).attributes()).contains("is-disabled").contains("data-access=\"DISABLED\"");
        assertThat(chip(chips, ghost).html()).contains(">Not in the system<");
        assertThat(chip(chips, ghost).attributes()).contains("data-refusal=\"is not a QTracker user\"");
        // A KDN user on a control that is not a KDN control: the policy hides it from them, the save refuses them
        assertThat(chip(chips, kdn.getMail()).html()).contains(">Will not see this control<");
        assertThat(chip(chips, kdn.getMail()).attributes())
                .contains("data-refusal=\"sees only KDN controls and cannot be added to this control\"");
        assertThat(chip(chips, readerAll.getMail()).html()).doesNotContain("sw-note");

        assertThat(field).contains("Can view this control and download files, no editing")
                .contains("id=\"sharedWithSearchInput\"").contains("role=\"combobox\"")
                .contains("aria-controls=\"sharedWithListbox\"").contains("id=\"sharedWithListbox\"")
                .contains("role=\"listbox\"").contains("aria-multiselectable=\"true\"")
                .contains("data-editable=\"true\"")
                // Remove buttons are added in edit mode only, by the page script
                .doesNotContain("sw-chip-remove");
        assertThat(field).contains("Not shared with anyone"); // the empty state, hidden while people are listed
        assertThat(field).containsPattern("id=\"sharedWithEmpty\"[^>]*hidden");
    }

    @Test
    void viewOnly_namesWithoutMarks_andNoSearch_forEveryoneElse() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 6) + "@outside.test";
        Control control = control("HR", "IN_PROGRESS",
                String.join(",", reader.getMail(), off.getMail(), ghost, kdn.getMail()));

        for (User viewer : List.of(readerAll, reader, fac, po)) {
            String field = field(control, login(viewer));
            assertThat(chips(field)).as(viewer.getDisplayName()).extracting(Chip::mail)
                    .containsExactly(reader.getMail(), off.getMail(), ghost, kdn.getMail());
            assertThat(field).as(viewer.getDisplayName())
                    .contains(">" + off.getDisplayName() + "<")
                    .contains("Can view this control and download files, no editing")
                    .doesNotContain("Not in the system", ">Disabled<", "Will not see this control", "data-access=",
                            "data-refusal=", "data-note=")
                    .doesNotContain("sharedWithSearchInput", "sharedWithListbox", "sw-chip-remove", "role=\"combobox\"")
                    .contains("data-editable=\"false\"");
        }
    }

    @Test
    void empty_saysNotSharedWithAnyone_withoutACount() throws Exception {
        Control control = control("HR", "IN_PROGRESS", null);

        for (User viewer : List.of(soqm, readerAll)) {
            String field = field(control, login(viewer));
            assertThat(chips(field)).isEmpty();
            assertThat(field).contains("<ul class=\"sw-chips\" id=\"sharedWithChips\" aria-labelledby=\"sharedWithLabel\" "
                    + "aria-describedby=\"sharedWithHint\"></ul>");
            assertThat(field).containsPattern("<p class=\"sw-empty\" id=\"sharedWithEmpty\">Not shared with anyone</p>");
            assertThat(field).containsPattern("id=\"sharedWithCount\"[^>]*hidden");
        }
    }

    @Test
    void longList_foldsBehindShowAll_onlyAboveSix() throws Exception {
        List<String> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(saveUser("many-" + i + "-" + UUID.randomUUID().toString().substring(0, 6),
                    AccessLevel.READ_ONLY, AccessScope.OWN, true).getMail());
        }
        Control many = control("HR", "IN_PROGRESS", String.join(",", eight));
        Control six = control("HR", "IN_PROGRESS", String.join(",", eight.subList(0, 6)));
        MockHttpSession viewer = login(readerAll);

        String manyField = field(many, viewer);
        assertThat(chips(manyField)).hasSize(8);
        assertThat(manyField).contains(">8 people<").contains("is-collapsed")
                .contains("aria-expanded=\"false\" aria-controls=\"sharedWithChips\">Show all 8</button>");
        String sixField = field(six, viewer);
        assertThat(chips(sixField)).hasSize(6);
        assertThat(sixField).doesNotContain("Show all", "is-collapsed");
    }

    @Test
    void draft_tellsSoqmWhoOpensItOnlyOnceInitiated() throws Exception {
        Control draft = control("HR", "DRAFT", String.join(",", reader.getMail(), readerAll.getMail(), fac.getMail()));

        List<Chip> chips = chips(field(draft, login(soqm)));
        // My controls, only shared: the draft opens once initiated; All controls see drafts; the Facilitator is on it
        assertThat(chip(chips, reader.getMail()).html()).contains(">Will see it once the control is initiated<");
        assertThat(chip(chips, reader.getMail()).attributes()).contains("is-after-initiation");
        assertThat(chip(chips, readerAll.getMail()).html()).doesNotContain("sw-note");
        assertThat(chip(chips, fac.getMail()).html()).doesNotContain("sw-note");
    }

    @Test
    void kdnUser_onAKdnControl_seesIt() throws Exception {
        Control control = control("KDN", "IN_PROGRESS", kdn.getMail());

        Chip chip = chips(field(control, login(soqm))).get(0);
        assertThat(chip.html()).doesNotContain("sw-note");
        assertThat(chip.attributes()).contains("data-access=\"VIEWS\"").doesNotContain("data-refusal");
    }

    @Test
    void completedControl_soqmSeesTheMarks_butNoSearch() throws Exception {
        Control control = control("HR", "COMPLETED", off.getMail());

        String field = field(control, login(soqm));
        assertThat(field).contains(">Disabled<").contains("data-editable=\"false\"")
                .doesNotContain("sharedWithSearchInput", "sharedWithListbox");
    }

    @Test
    void savedWithARepeatedAddress_showsThePersonOnce() throws Exception {
        Control control = control("HR", "IN_PROGRESS", null);
        MockHttpSession session = login(soqm);

        MvcResult saved = mockMvc.perform(post("/api/control-assignment").with(csrf().asHeader()).with(ownAddress())
                        .session(session).contentType("application/json")
                        .content("{\"controlId\":" + control.getId() + ",\"controlSharedWith\":[\""
                                + reader.getMail() + "\",\"" + reader.getMail().toUpperCase() + "\"]}"))
                .andReturn();
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);

        assertThat(chips(field(control, session))).extracting(Chip::mail).containsExactly(reader.getMail());
    }

    /**
     * The page stops Save on the people the server would refuse, with the server's own reason (data-refusal); the
     * server's refusal names the field as "Control Shared With: <mail> <reason>", which the page shows under it.
     */
    @Test
    void theRefusalShownBeforeSaving_isTheServers_andNothingIsStored() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 6) + "@outside.test";
        Control control = control("HR", "IN_PROGRESS", reader.getMail());
        MockHttpSession session = login(soqm);

        for (String refused : List.of(ghost, kdn.getMail())) {
            Control shown = control("HR", "IN_PROGRESS", refused);
            String reason = Pattern.compile("data-refusal=\"([^\"]*)\"").matcher(chips(field(shown, session)).get(0).attributes())
                    .results().findFirst().orElseThrow().group(1);

            MvcResult saved = mockMvc.perform(post("/api/control-assignment").with(csrf().asHeader()).with(ownAddress())
                            .session(session).contentType("application/json")
                            .content("{\"controlId\":" + control.getId() + ",\"controlSharedWith\":[\""
                                    + reader.getMail() + "\",\"" + refused + "\"]}"))
                    .andReturn();

            assertThat(saved.getResponse().getStatus()).isEqualTo(400);
            assertThat(saved.getResponse().getContentAsString())
                    .isEqualTo("VALIDATION_ERROR: Control Shared With: " + refused + " " + HtmlUtils.htmlUnescape(reason));
            assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlSharedWith())
                    .isEqualTo(reader.getMail());
        }
    }

    // ------------------------------------------------------------------ the field elsewhere

    @Test
    void changelog_namesThePeople_inTheStoredOrder_likeTheField() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 6) + "@outside.test";
        Control control = control("HR", "IN_PROGRESS", reader.getMail());
        MockHttpSession session = login(soqm);
        // A stored address without a user (older data) stays in the list it is saved with
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlSharedWith(reader.getMail() + "," + ghost);
        assignmentRepository.save(assignment);

        MvcResult saved = mockMvc.perform(post("/api/control-assignment").with(csrf().asHeader()).with(ownAddress())
                        .session(session).contentType("application/json")
                        .content("{\"controlId\":" + control.getId() + ",\"controlSharedWith\":[\""
                                + readerAll.getMail() + "\",\"" + reader.getMail() + "\"]}"))
                .andReturn();
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);

        String changelog = mockMvc.perform(get("/api/controls/{id}/changelog", control.getId()).with(ownAddress())
                        .session(session))
                .andReturn().getResponse().getContentAsString();
        JsonNode change = null;
        for (JsonNode entry : new ObjectMapper().readTree(changelog)) {
            for (JsonNode fieldChange : entry.path("fieldChanges")) {
                if ("Control Shared With".equals(fieldChange.path("field").asText())) {
                    change = fieldChange;
                }
            }
        }
        assertThat(change).as("Control Shared With in the Changelog").isNotNull();
        assertThat(change.path("oldValue").asText())
                .isEqualTo(reader.getDisplayName() + " (" + reader.getMail() + "), " + ghost);
        assertThat(change.path("newValue").asText()).isEqualTo(readerAll.getDisplayName() + " (" + readerAll.getMail()
                + "), " + reader.getDisplayName() + " (" + reader.getMail() + ")");
    }

    @Test
    void excel_namesTheFieldAsThePageDoes_withTheAddressesInStoredOrder() throws Exception {
        Control control = control("HR", "COMPLETED", readerAll.getMail() + "," + reader.getMail());

        MvcResult result = mockMvc.perform(get("/api/controls/{id}/export/completed", control.getId())
                        .with(ownAddress()).session(login(soqm)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, String> rows = new LinkedHashMap<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (Row row : workbook.getSheetAt(0)) {
                rows.put(row.getCell(0).getStringCellValue(),
                        row.getCell(1) != null ? row.getCell(1).getStringCellValue() : null);
            }
        }
        assertThat(rows).doesNotContainKey("Shared With")
                .containsEntry("Control Shared With", readerAll.getMail() + ", " + reader.getMail());
    }

    // ------------------------------------------------------------------ helpers

    private record Chip(String mail, String attributes, String html) {
    }

    private static List<Chip> chips(String field) {
        List<Chip> chips = new ArrayList<>();
        Matcher matcher = CHIP.matcher(field);
        while (matcher.find()) {
            Matcher mail = Pattern.compile("data-mail=\"([^\"]*)\"").matcher(matcher.group(1));
            assertThat(mail.find()).as("data-mail of %s", matcher.group()).isTrue();
            chips.add(new Chip(HtmlUtils.htmlUnescape(mail.group(1)), matcher.group(0), matcher.group(2)));
        }
        return chips;
    }

    private static Chip chip(List<Chip> chips, String mail) {
        return chips.stream().filter(chip -> chip.mail().equalsIgnoreCase(mail)).findFirst()
                .orElseThrow(() -> new AssertionError(mail + " has no chip"));
    }

    /** The Shared With field of the page: from its container to its hidden input. */
    private String field(Control control, MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/view-control/{id}", control.getId()).with(ownAddress()).session(session))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String page = result.getResponse().getContentAsString();
        int start = page.indexOf("id=\"sharedWithField\"");
        int end = page.indexOf("id=\"controlSharedWithHidden\"", start);
        assertThat(start).as("Shared With field").isPositive();
        assertThat(end).isPositive();
        return page.substring(page.lastIndexOf("<div", start), end);
    }

    private Control control(String idPrefix, String status, String sharedWith) {
        Control control = new Control();
        control.setControlId(idPrefix + "-SW-" + UUID.randomUUID().toString().substring(0, 8));
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("HR");
        control.setOperatedBy("Finance");
        control.setPriority("High");
        control.setNonAuditServicesApplicability("No");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control.setSoqmYear(SoqmYear.current(LocalDate.now()));
        control.setCreatedBy(soqm);
        control.setCreatedAt(LocalDateTime.now());
        control = controlRepository.save(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(fac.getMail());
        assignment.setControlOperator(op.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(po.getMail());
        assignment.setControlSharedWith(sharedWith);
        assignment.setControlOperationDate(LocalDate.now().plusDays(10));
        assignmentRepository.save(assignment);
        return control;
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope, boolean enabled) {
        User user = new User();
        user.setMail(name + "@shared.test");
        user.setDisplayName("Person " + name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(false);
        user.setEnabled(enabled);
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

    /** RateLimitingFilter counts logins per address: every request comes from an address of its own. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor ownAddress() {
        String address = "10.8." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
