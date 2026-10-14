package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.controller.ViewController;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.service.ComponentControlsList;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.ModelAndView;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release test pass, stage G: what the lists, tiles, cards and exports count and show, for one person whose
 * controls are known, and the Changelog and in-app notifications.
 */
class ReleaseScenarioListsIT extends ReleaseScenarioSupport {

    // ---- tiles = lists = cards ----

    @Test
    void dashboardTiles_controlsCounters_filteredLists_andComponentCards_countTheSameControls() throws Exception {
        User me = myEdit("ls-me");
        User soqm = soqm("ls-soqm");
        People mine = people().facilitator(me).operator(myEdit("ls-op")).soqm(soqm).owner(myEdit("ls-po"));
        List<Control> controls = new ArrayList<>();
        for (String status : List.of("DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED")) {
            controls.add(control("RS-LS", status, mine));
        }
        controls.add(deadline(control("RS-LS", "IN_PROGRESS", mine, c -> c.setComponent("EP")), today().minusDays(2)));
        controls.add(deadline(control("RS-LS", "REVIEW", mine, c -> c.setComponent("EP")), today()));
        Control late = deadline(control("RS-LS", "COMPLETED", mine, c -> c.setComponent("A&C")), today().minusDays(5));
        completed(late, today().minusDays(1));
        controls.add(late);
        // Someone else's control: counted nowhere for me
        control("RS-LS", "IN_PROGRESS", people().facilitator(myEdit("ls-other")).soqm(soqm));
        MockHttpSession session = login(me);

        ModelAndView dashboard = call(session, get("/")).getModelAndView();
        Map<String, Object> tiles = dashboard.getModel();
        assertThat(number(tiles, "totalControls")).isEqualTo(9L);
        assertThat(number(tiles, "completedControls")).isEqualTo(2L);
        assertThat(number(tiles, "overdueControls")).isEqualTo(1L);
        assertThat(number(tiles, "activeControls")).isEqualTo(6L);

        // The tiles' links: the All components list with the same filter, as many rows as the tile says
        Map<String, String> links = Map.of("totalControls", "/component/ALL", "activeControls",
                "/component/ALL?status=ACTIVE", "completedControls", "/component/ALL?status=COMPLETED",
                "overdueControls", "/component/ALL?status=OVERDUE");
        String html = body(call(session, get("/")));
        links.forEach((counter, href) -> assertThat(html).as(counter).contains("href=\"" + href.replace("&", "&amp;") + "\""));
        for (Map.Entry<String, String> link : links.entrySet()) {
            assertThat((long) listRows(session, link.getValue()).size()).as(link.getValue())
                    .isEqualTo(number(tiles, link.getKey()));
        }
        List<ComponentControlsList.Row> all = listRows(session, "/component/ALL");
        assertThat(all).extracting(ComponentControlsList.Row::id)
                .containsExactlyInAnyOrderElementsOf(controls.stream().map(Control::getId).toList());
        assertThat(all.stream().filter(ComponentControlsList.Row::closedLate).map(ComponentControlsList.Row::id))
                .containsExactly(late.getId());
        assertThat(all.stream().filter(ComponentControlsList.Row::overdue)).hasSize(1);

        // Action Centre cards = the component lists
        ModelAndView centre = call(session, get("/action-centre")).getModelAndView();
        Map<String, Object> model = centre != null && centre.getModel().containsKey("componentSummaries")
                ? centre.getModel() : dashboard.getModel();
        @SuppressWarnings("unchecked")
        List<ViewController.ComponentSummary> cards = (List<ViewController.ComponentSummary>) model.get("componentSummaries");
        long cardTotal = 0;
        for (ViewController.ComponentSummary card : cards) {
            ComponentControlsList.Result listed = (ComponentControlsList.Result) call(session,
                    get("/component/{code}", card.code()).param("size", "50")).getModelAndView().getModel().get("list");
            assertThat(listed.counts().total()).as(card.code()).isEqualTo(card.total());
            assertThat(listed.matching()).as(card.code()).isEqualTo((int) card.total());
            assertThat(listed.counts().overdue()).as(card.code()).isEqualTo(card.overdue());
            cardTotal += card.total();
        }
        assertThat(cardTotal).isEqualTo(9L);
        ComponentControlsList.Result hr = (ComponentControlsList.Result) call(session, get("/component/HR"))
                .getModelAndView().getModel().get("list");
        assertThat(ComponentControlsList.COLUMNS).hasSize(10);
        assertThat(hr.rows()).hasSize(6);
    }

    /** D-08: the Active tile and the list behind it (the Controls page is gone, the list follows the tile's rule). */
    @Test
    void activeTile_equalsTheListBehindItsLink() throws Exception {
        for (User me : List.of(myEdit("at-my"), allEdit("at-all"))) {
            User soqm = soqm("at-soqm");
            People mine = people().facilitator(me).operator(myEdit("at-op")).soqm(soqm).owner(myEdit("at-po"));
            control("RS-AT", "IN_PROGRESS", mine);
            control("RS-AT", "REVIEW", mine);
            deadline(control("RS-AT", "IN_PROGRESS", mine), today().minusDays(3));
            MockHttpSession session = login(me);
            assertThat((long) listRows(session, "/component/ALL?status=ACTIVE").size()).as(me.getMail())
                    .isEqualTo(number(call(session, get("/")).getModelAndView().getModel(), "activeControls"));
        }
    }

    @Test
    void kdnMark_onlyForSoqmTeam_andTheKdnListCountsTheKdnControls() throws Exception {
        User soqm = soqm("km-soqm");
        User kdnUser = kdn("km-kdn");
        User allEdit = allEdit("km-all");
        Control kdnControl = control("KDN-KM", "IN_PROGRESS", people().soqm(soqm));
        Control hr = control("HR-KM", "IN_PROGRESS", people().soqm(soqm));
        Set<Long> marked = new java.util.HashSet<>();
        listRows(login(soqm), "/component/ALL").stream().filter(ComponentControlsList.Row::kdn)
                .forEach(row -> marked.add(row.id()));
        assertThat(marked).contains(kdnControl.getId()).doesNotContain(hr.getId());
        assertThat(listRows(login(allEdit), "/component/ALL")).noneMatch(ComponentControlsList.Row::kdn);
        List<ComponentControlsList.Row> kdnRows = listRows(login(kdnUser), "/kdn");
        assertThat(kdnRows).extracting(ComponentControlsList.Row::id).contains(kdnControl.getId()).doesNotContain(hr.getId());
    }

    // ---- Excel of the list ----

    @Test
    void excelOfTheList_theListsColumns_cyrillic_noFormula_twoThousandRows_soqmAdminTeamOnly() throws Exception {
        User soqm = soqm("xl-soqm");
        User named = userRepository.findById(soqm.getId()).orElseThrow();
        named.setDisplayName("Ерлан Тестов ✅");
        userRepository.save(named);
        List<Control> many = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Control c = new Control();
            c.setControlId("HR-XL-" + RUN + "-" + i);
            c.setControlFrequency("Monthly");
            c.setControlType("Preventive");
            c.setComponent("HR");
            c.setControlStatus("ACTIVE");
            c.setPerformanceStatus("IN_PROGRESS");
            c.setControlDescription("Сверка " + i);
            c.setCreatedAt(LocalDateTime.now());
            many.add(c);
        }
        controlRepository.saveAll(many);
        Control dated = control("HR-XLD", "PROCESS_OWNER_REVIEW", people().soqm(soqm), c -> c.setControlCategory("=1+2"));
        ControlAssignment assignment = assignmentRepository.findByControlId(dated.getId()).orElseThrow();
        assignment.setControlOperationDate(LocalDate.of(2027, 3, 1));
        assignmentRepository.save(assignment);

        // Everyone else: 403 on every list's export
        for (User other : List.of(myEdit("xl-my"), allEdit("xl-all"), myRead("xl-ro"), allRead("xl-allro"), kdn("xl-kdn"))) {
            MockHttpSession session = login(other);
            for (String path : List.of("/component/ALL/export", "/component/HR/export", "/component/KDN/export")) {
                assertThat(call(session, get(path)).getResponse().getStatus()).as("%s %s", other.getMail(), path)
                        .isEqualTo(403);
            }
        }

        long start = System.nanoTime();
        MvcResult excel = call(login(soqm), get("/component/ALL/export").param("q", "HR-XL-" + RUN + "-"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(excel.getResponse().getStatus()).isEqualTo(200);
        assertThat(excel.getResponse().getHeader("Content-Disposition")).contains(".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).as("rows: the search HR-XL").isEqualTo(2000);
            List<String> header = new ArrayList<>();
            for (Cell cell : sheet.getRow(0)) {
                header.add(cell.getStringCellValue());
            }
            assertThat(header).containsExactly("Control ID", "Control Type", "Control Frequency",
                    "Facilitator / Preparer(s)", "Control Operator", "Process Owner", "SoQM Team / Delegate",
                    "Control Category", "Control Operation Date", "Performance Status");
            for (Row row : sheet) {
                for (Cell cell : row) {
                    assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                }
            }
            assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("In Progress");
        }

        // The rows asked for: search and status; names, dates and the status as text; a formula stays text
        MvcResult one = call(login(soqm), get("/component/HR/export").param("q", dated.getControlId())
                .param("status", "PROCESS_OWNER_REVIEW"));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(one.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            DataFormatter formatter = new DataFormatter();
            StringBuilder line = new StringBuilder();
            sheet.getRow(1).forEach(cell -> line.append(formatter.formatCellValue(cell)).append('|'));
            assertThat(line.toString()).contains(dated.getControlId(), "Ерлан Тестов ✅", "01.03.2027",
                    "Process Owner Review", "=1+2");
            Cell category = sheet.getRow(1).getCell(7);
            assertThat(category.getCellType()).isEqualTo(CellType.STRING);
            assertThat(category.getCellStyle().getQuotePrefixed()).isTrue();
        }
        System.out.println("RELEASE-SCENARIO list Excel of 2000 rows (" + controlRepository.count() + " controls): "
                + millis + " ms");
        assertThat(millis).as("the export of 2000 rows").isLessThan(20_000);
    }

    // ---- Changelog ----

    @Test
    void changelog_listsEveryChangeNewestFirst_withTheAuthorAndTheValues() throws Exception {
        User soqm = soqm("cl-soqm");
        User fac = myEdit("cl-fac");
        Control control = control("RS-CL", "IN_PROGRESS", people().soqm(soqm).facilitator(fac)
                .operator(myEdit("cl-op")).owner(myEdit("cl-po")));
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession facSession = login(fac);
        assertThat(callJson(soqmSession, put("/api/controls/{id}", control.getId()), "{\"prp\":\"PRP v2\"}")
                .getResponse().getStatus()).isEqualTo(200);
        Thread.sleep(30);
        assertThat(callJson(facSession, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"controlStepsPerformed\":\"Steps v2\"}").getResponse().getStatus()).isEqualTo(200);
        Thread.sleep(30);
        assertThat(call(facSession, post("/api/workflow/submit-to-control-operator").param("controlId",
                String.valueOf(control.getId()))).getResponse().getStatus()).isEqualTo(200);

        JsonNode log = json(call(soqmSession, get("/api/controls/{id}/changelog", control.getId())));
        List<String> order = new ArrayList<>();
        log.forEach(entry -> order.add(entry.path("actorEmail").asText() + " " + entry.path("eventName").asText()));
        assertThat(order.get(0)).startsWith(fac.getMail()).contains("Submitted");
        assertThat(order.get(1)).startsWith(fac.getMail());
        assertThat(order.get(2)).startsWith(soqm.getMail());
        assertThat(log.get(1).toString()).contains("Steps performed").contains("Steps v2");
        assertThat(log.get(2).toString()).contains("PRP v2");
        for (JsonNode entry : log) {
            assertThat(entry.path("createdAt").asText()).isNotBlank();
        }
    }

    // ---- in-app notifications ----

    @Test
    void notifications_unreadCount_markOne_markAll_andHiddenFromOthers() throws Exception {
        User me = myEdit("nt-me");
        Control control = control("RS-NT", "IN_PROGRESS", people().facilitator(me));
        List<Notification> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Notification n = new Notification();
            n.setUserId(me.getId());
            n.setControlId(control.getId());
            n.setType("WORKFLOW_STEP");
            n.setTitle("Step " + i);
            n.setMessage("Message " + i);
            n.setIsRead(false);
            n.setCreatedAt(LocalDateTime.now().minusMinutes(i));
            mine.add(notificationRepository.save(n));
        }
        MockHttpSession session = login(me);
        assertThat(number(call(session, get("/")).getModelAndView().getModel(), "unreadNotifications")).isEqualTo(3L);
        MvcResult one = call(session, post("/notifications/{id}/read", mine.get(0).getId()));
        assertThat(one.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(one)).contains("2");
        assertThat(number(call(session, get("/")).getModelAndView().getModel(), "unreadNotifications")).isEqualTo(2L);
        // Opening a notification marks it read
        call(session, get("/notification/{id}", mine.get(1).getId()));
        assertThat(notificationRepository.findById(mine.get(1).getId()).orElseThrow().getIsRead()).isTrue();
        assertThat(call(session, post("/notifications/mark-all-read")).getResponse().getRedirectedUrl()).contains("notifications");
        assertThat(number(call(session, get("/")).getModelAndView().getModel(), "unreadNotifications")).isEqualTo(0L);
    }

    // ---- helpers ----

    private Control deadline(Control control, LocalDate deadline) {
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlOperationDate(deadline.minusDays(14));
        assignment.setControlOperationDeadline(deadline);
        assignmentRepository.save(assignment);
        Control stored = reload(control);
        stored.setDeadline(deadline);
        return controlRepository.save(stored);
    }

    private void completed(Control control, LocalDate on) {
        WorkflowHistory done = new WorkflowHistory();
        done.setControlId(control.getId());
        done.setActionType(WorkflowActionType.APPROVE);
        done.setFromStep("PROCESS_OWNER_REVIEW");
        done.setToStep("COMPLETED");
        done.setPerformedByEmail("owner@release.test");
        done.setCreatedAt(on.atTime(12, 0));
        historyRepository.save(done);
    }

    private static long number(Map<String, Object> model, String key) {
        return ((Number) model.get(key)).longValue();
    }

}
