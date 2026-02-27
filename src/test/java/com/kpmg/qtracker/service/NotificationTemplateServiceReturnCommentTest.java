package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationTemplateServiceReturnCommentTest {

    private NotificationTemplateService service;
    private Control control;

    @BeforeEach
    void setUp() {
        service = new NotificationTemplateService();
        ReflectionTestUtils.setField(service, "baseUrl", "http://example.test");

        control = new Control();
        control.setId(42L);
        control.setControlId("CTRL-42");
    }

    @Test
    void renderReturnNotification_withComment_includesReturnCommentSection() {
        NotificationTemplateService.NotificationTemplate template =
                service.renderReturnNotification(
                        control,
                        "Operator User",
                        "Facilitator",
                        "Please update the evidence and resubmit.",
                        "RETURN_TO_FACILITATOR"
                );

        assertTrue(template.getSubject().contains("Control Returned to Facilitator"));
        assertTrue(template.getBody().contains("Control ID: CTRL-42"));
        assertTrue(template.getBody().contains("Returned by: Operator User"));
        assertTrue(template.getBody().contains("Return Comment:"));
        assertTrue(template.getBody().contains("Please update the evidence and resubmit."));
    }

    @Test
    void renderReturnNotification_blankComment_omitsReturnCommentSection() {
        NotificationTemplateService.NotificationTemplate template =
                service.renderReturnNotification(
                        control,
                        "Operator User",
                        "Facilitator",
                        "   ",
                        "RETURN_TO_FACILITATOR"
                );

        assertTrue(template.getSubject().contains("Control Returned to Facilitator"));
        assertTrue(template.getBody().contains("Control ID: CTRL-42"));
        assertTrue(template.getBody().contains("Returned by: Operator User"));
        assertFalse(template.getBody().contains("Return Comment:"));
    }
}
