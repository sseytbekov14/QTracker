package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NotificationTemplateServiceTest {

    private NotificationTemplateService service;
    private Control control;

    @BeforeEach
    void setUp() {
        service = new NotificationTemplateService();
        ReflectionTestUtils.setField(service, "baseUrl", "http://example.test");

        control = new Control();
        control.setId(42L);
        control.setControlId("CTRL-42");
        control.setControlDescription("Test control");
    }

    @Test
    void render_allTemplateTypes_haveSubjectAndBody() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        for (NotificationTemplateService.TemplateType type : NotificationTemplateService.TemplateType.values()) {
            NotificationTemplateService.NotificationTemplate template =
                    service.render(type, control, deadline, false, "Test User", "Control Operator");
            assertNotNull(template, "template should not be null for " + type);
            assertNotNull(template.getSubject(), "subject should not be null for " + type);
            assertFalse(template.getSubject().trim().isEmpty(), "subject should not be blank for " + type);
            assertNotNull(template.getBody(), "body should not be null for " + type);
            assertFalse(template.getBody().trim().isEmpty(), "body should not be blank for " + type);
        }
    }

    @Test
    void render_operatorToSoqm_resubmitted_hasSubjectAndBody() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM,
                        control,
                        deadline,
                        true,
                        "Test User",
                        "SoQM Head/Delegate");
        assertNotNull(template);
        assertNotNull(template.getSubject());
        assertFalse(template.getSubject().trim().isEmpty());
        assertNotNull(template.getBody());
        assertFalse(template.getBody().trim().isEmpty());
    }
}
