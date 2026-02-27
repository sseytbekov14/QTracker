package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.ControlHistoryService;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ControlController.class)
class ControlControllerExportExcelTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IControlService controlService;

    @MockBean
    private UserService userService;

    @MockBean
    private ControlAssignmentService controlAssignmentService;

    @MockBean
    private ControlDetailsService controlDetailsService;

    @MockBean
    private ControlDocumentsService controlDocumentsService;

    @MockBean
    private IPerformanceService performanceService;

    @MockBean
    private AdminAuditService adminAuditService;

    @MockBean
    private ControlHistoryService controlHistoryService;

    @MockBean
    private ControlPermissionService controlPermissionService;

    @MockBean
    private com.kpmg.qtracker.service.ControlIdGeneratorService controlIdGeneratorService;

    @MockBean
    private StatusDisplayMapper statusDisplayMapper;

    @Test
    void exportExcel_includesExpectedHeadersAndOrder() throws Exception {
        User currentUser = user("SoQM User");
        currentUser.setRole("SOQM_LEAD");
        currentUser.setMail("soqm@kpmg.kz");

        Control control = new Control();
        control.setId(101L);
        control.setControlId("CTRL-101");
        control.setComponent("Finance");
        control.setControlType("Manual");
        control.setControlFrequency("Monthly");
        control.setControlStatus("IN_PROGRESS");
        control.setDeadline(LocalDate.of(2026, 2, 20));

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlId(101L);
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 10));
        assignment.setControlOperator(List.of("operator@kpmg.kz"));
        assignment.setProcessOwner(List.of("owner@kpmg.kz"));
        assignment.setSoqmLead(List.of("soqm@kpmg.kz"));

        when(controlService.getAllControls()).thenReturn(List.of(control));
        when(controlAssignmentService.getAssignmentByControlId(101L)).thenReturn(assignment);
        when(userService.getUserByEmail("operator@kpmg.kz")).thenReturn(Optional.of(user("Op User")));
        when(userService.getUserByEmail("owner@kpmg.kz")).thenReturn(Optional.of(user("Owner User")));
        when(userService.getUserByEmail("soqm@kpmg.kz")).thenReturn(Optional.of(user("SoQM User")));

        byte[] content = mockMvc.perform(get("/api/controls/export/excel")
                        .accept(MediaType.APPLICATION_OCTET_STREAM)
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("№");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("CONTROL ID");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Component");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Control Type");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Frequency of Control");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("Control operation date");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("Control Operator");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("Process Owner");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("SoQM Head/Delegate");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("Deadline");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("Performance Status");

            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("CTRL-101");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Finance");
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("Manual");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("Monthly");
            assertThat(row.getCell(5).getStringCellValue()).isEqualTo("10.02.2026");
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("Op User");
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo("Owner User");
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("SoQM User");
            assertThat(row.getCell(9).getStringCellValue()).isEqualTo("20.02.2026");
            assertThat(row.getCell(10).getStringCellValue()).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    void exportExcel_whenNotSoqm_returns403() throws Exception {
        User currentUser = user("Operator User");
        currentUser.setRole("CONTROL_OPERATOR");
        currentUser.setMail("operator@kpmg.kz");

        mockMvc.perform(get("/api/controls/export/excel")
                        .accept(MediaType.APPLICATION_OCTET_STREAM)
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isForbidden());
    }

    private User user(String displayName) {
        User user = new User();
        user.setDisplayName(displayName);
        return user;
    }
}

