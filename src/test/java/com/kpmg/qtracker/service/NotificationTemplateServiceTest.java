package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void render_activation_usesOfficialText() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.ACTIVATION,
                        control,
                        deadline,
                        false,
                        "Test User",
                        "Control Operator");
        String body = template.getBody();
        assertTrue(body.contains("Dear Test User,"));
        assertTrue(body.contains("The scheduled control CTRL-42 / Test control has been activated today."));
        assertTrue(body.contains("deadline 04.02.2026"));
        assertTrue(body.contains("http://example.test/view-control/42"));
        assertFalse(body.contains("null"));
    }

    @Test
    void render_reminder1_usesOfficialText() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.REMINDER_1,
                        control,
                        deadline,
                        false,
                        "Test User",
                        "Control Operator");
        String body = template.getBody();
        assertTrue(body.contains("We kindly remind you to complete the control CTRL-42, Test control."));
        assertTrue(body.contains("http://example.test/view-control/42"));
        assertTrue(body.contains("The deadline of the control is on 04.02.2026"));
        assertFalse(body.contains("null"));
    }

    @Test
    void render_reminder2_usesOfficialText() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.REMINDER_2,
                        control,
                        deadline,
                        false,
                        "Test User",
                        "Control Operator");
        String body = template.getBody();
        assertTrue(body.contains("Control CTRL-42, Test control deadline is approaching."));
        assertTrue(body.contains("http://example.test/view-control/42"));
        assertTrue(body.contains("The deadline of the control is on 04.02.2026"));
        assertFalse(body.contains("null"));
    }

    @Test
    void render_deadline_usesOfficialText() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.DEADLINE,
                        control,
                        deadline,
                        false,
                        "Test User",
                        "Control Operator");
        String body = template.getBody();
        assertTrue(body.contains("Our records indicate that the control deadline has passed."));
        assertTrue(body.contains("The following control remains incomplete: CTRL-42, Test control"));
        assertTrue(body.contains("http://example.test/view-control/42"));
        assertFalse(body.contains("null"));
    }

    @Test
    void render_completedAll_usesOfficialText() {
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.COMPLETED_ALL,
                        control,
                        deadline,
                        false,
                        "Test User",
                        "Control Operator");
        String body = template.getBody();
        assertTrue(body.contains("Dear Test User,"));
        assertTrue(body.contains("Please note that the control has been successfully completed in the system."));
        assertFalse(body.contains("null"));
    }

    @Test
    void render_reminder1_withoutControlName_usesControlIdOnly() {
        control.setControlDescription(null);
        LocalDate deadline = LocalDate.of(2026, 2, 4);
        NotificationTemplateService.NotificationTemplate template =
                service.render(NotificationTemplateService.TemplateType.REMINDER_1,
                        control,
                        deadline,
                        false,
                        "Test User",
                        "Control Operator");
        String body = template.getBody();
        assertTrue(body.contains("control CTRL-42."));
        assertFalse(body.contains("CTRL-42, "));
        assertFalse(body.contains("CTRL-42 /"));
        assertFalse(body.contains("null"));
    }

}

