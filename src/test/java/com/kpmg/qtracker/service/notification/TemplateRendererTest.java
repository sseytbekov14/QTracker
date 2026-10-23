package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The texts of 2026-10-20 with their values (TP-1..3): read from the table, every placeholder filled or nothing made;
 * "Dear {NAME},"; the link "Go to Control" in the e-mail, nothing of it in-app.
 */
class TemplateRendererTest {

    private static final String LINK = "https://qtracker.example.test/view-control/7";

    private final NotificationTemplateTextRepository repository = mock(NotificationTemplateTextRepository.class);
    private final TemplateRenderer renderer = new TemplateRenderer(repository);
    private final Map<String, NotificationTemplateText> stored = new HashMap<>();

    @BeforeEach
    void templates() {
        for (Template template : Template.values()) {
            NotificationTemplateText text = new NotificationTemplateText();
            text.setTemplateKey(template.name());
            text.setSubject(template.defaultSubject());
            text.setBody(template.defaultBody());
            stored.put(template.name(), text);
        }
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get((String) call.getArgument(0))));
    }

    static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put("NAME", "Anna Smith");
        values.put("ROLES", "Facilitator and Control Operator");
        values.put("CONTROL_ID", "HR-1/FY27");
        values.put("CONTROL_NAME", "Payroll <check> & review");
        values.put("OCCURRENCE_DATE", "05-11-2026");
        values.put("DEADLINE", "19-11-2026");
        values.put("LINK", LINK);
        values.put("RETURNED_BY", "Control Operator");
        return values;
    }

    /** The in-app text of each template, word for word. */
    static String inApp(Template template) {
        String control = "Control HR-1/FY27 Payroll <check> & review";
        String body = switch (template) {
            case T1 -> control + " has been created.\nYour role: Facilitator and Control Operator\n"
                    + "Occurrence date: 05-11-2026\nDeadline: 19-11-2026\n"
                    + "Please complete and submit the control before the deadline.";
            case T2 -> control + " occurs today (05-11-2026).\nYour role: Facilitator and Control Operator\n"
                    + "Deadline: 19-11-2026\nPlease complete and submit the control before the deadline.";
            case T3 -> control + " is awaiting your action.\nDeadline: 19-11-2026";
            case T4 -> "The deadline for control HR-1/FY27 Payroll <check> & review is today (19-11-2026).\n"
                    + "Please complete it by the end of the day.";
            case T5 -> "The deadline for control HR-1/FY27 Payroll <check> & review has passed (19-11-2026). "
                    + "The control is not completed.\nPlease complete it as soon as possible.";
            case T6 -> control + " has been sent for your review.\nPlease review it and submit it to the SoQM Admin Team.\n"
                    + "Deadline: 19-11-2026";
            case T7 -> control + " has been returned to you by the Control Operator for rework.\n"
                    + "Please review the comments and resubmit the control.\nDeadline: 19-11-2026";
            case T8 -> control + " has been sent for your review.\nPlease review it and submit it to the Process Owner.";
            case T9 -> control + " has been returned to you by the SoQM Admin Team for rework.\n"
                    + "Please review the comments and resubmit the control.\nDeadline: 19-11-2026";
            case T10 -> control + " has been reviewed by the SoQM Admin Team and is ready for your review.\n"
                    + "Please review and complete the control.\nDeadline: 19-11-2026";
            case T11 -> control + " has been returned to you by the Process Owner for rework.\n"
                    + "Please review the comments and resubmit the control.\nDeadline: 19-11-2026";
            case T12 -> control + " has been completed.";
        };
        return "Dear Anna Smith,\n\n" + body + "\n\nKind regards,\nSoQM Team";
    }

    @ParameterizedTest
    @EnumSource(Template.class)
    void eachTemplate_dearName_theText_goToControlInTheEmail_noLinkInApp(Template template) {
        TemplateRenderer.Rendered rendered = renderer.render(template, values()).rendered();

        assertThat(rendered.subject()).isEqualTo(template.defaultSubject());
        assertThat(rendered.text()).isEqualTo(inApp(template))
                .doesNotContain("http").doesNotContainIgnoringCase("link").doesNotEndWith("\n");
        assertThat(rendered.html()).startsWith("<div").contains("Dear Anna Smith,<br>")
                .containsPattern("<a href=\"https://qtracker\\.example\\.test/view-control/7\"[^>]*>Go to Control</a>")
                .contains("Kind regards,<br>\nSoQM Team</div>");
        // The address only in the href, never as text
        assertThat(rendered.html().replace("href=\"" + LINK + "\"", "")).doesNotContain("http");
        assertThat(LogMailTransport.text(rendered.html())).contains("\nGo to Control: " + LINK + "\n");
    }

    @Test
    void theRoles_onlyInT1AndT2() {
        for (Template template : Template.values()) {
            assertThat(template.defaultBody().contains("Your role: {ROLES}")).as(template.name())
                    .isEqualTo(template == Template.T1 || template == Template.T2);
            assertThat(template.defaultBody()).as(template.name()).startsWith("Dear {NAME},\n")
                    .contains("\n{LINK}\n").endsWith("Kind regards,\nSoQM Team")
                    .doesNotContain("{ROLE}", "using the link below", "using the following link");
        }
    }

    @Test
    void aLineOfLink_isAButton_aLinkWithinALine_aBoldLink_inAppWithoutBoth() {
        stored.get("T3").setBody("Dear {NAME},\nOpen {LINK} now.\n{LINK}\n\nKind regards,\nSoQM Team");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T3, values()).rendered();

        assertThat(rendered.html()).contains("Open <a href=\"" + LINK + "\" "
                + "style=\"color:#00338D;font-weight:bold\">Go to Control</a> now.<br>\n<table role=\"presentation\"")
                .contains("</table>\n<br>\nKind regards,<br>\nSoQM Team");
        assertThat(rendered.text()).isEqualTo("Dear Anna Smith,\nOpen  now.\n\nKind regards,\nSoQM Team");
    }

    @Test
    void typedText_andTheName_areEscapedInTheEmail_asTheyAreInApp() {
        Map<String, String> values = values();
        values.put("NAME", "O'Neil <Anna> & co");

        TemplateRenderer.Rendered t1 = renderer.render(Template.T1, values).rendered();

        assertThat(t1.html()).contains("Dear O&#39;Neil &lt;Anna&gt; &amp; co,", "HR-1/FY27 Payroll &lt;check&gt; &amp; review");
        assertThat(t1.text()).startsWith("Dear O'Neil <Anna> & co,").contains("HR-1/FY27 Payroll <check> & review");
    }

    @Test
    void aPlaceholderWithoutValue_makesNothing() {
        Map<String, String> values = values();
        values.remove("DEADLINE");

        TemplateRenderer.Result result = renderer.render(Template.T6, values);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("{DEADLINE}").contains("T6");
        // T8 has no deadline, so it is made
        assertThat(renderer.render(Template.T8, values).ok()).isTrue();
        values.remove("ROLES");
        assertThat(renderer.render(Template.T2, values).error()).contains("{ROLES}");
    }

    @Test
    void aTextChangedInTheTable_isUsedAtOnce_andAnUnknownPlaceholderFails() {
        stored.get("T8").setSubject("SoQM review: {CONTROL_ID}");
        assertThat(renderer.render(Template.T8, values()).rendered().subject()).isEqualTo("SoQM review: HR-1/FY27");

        stored.get("T8").setBody("Dear team, {UNKNOWN}");
        assertThat(renderer.render(Template.T8, values()).error()).contains("{UNKNOWN}");

        stored.get("T3").setBody("Dear {ROLE},");
        assertThat(renderer.render(Template.T3, values()).error()).contains("{ROLE}");

        stored.remove("T9");
        assertThat(renderer.render(Template.T9, values()).error()).contains("no text T9");
    }

    @Test
    void theTexts_nameNoFrequencyAndNoOnBehalf_andOnlyT4AndT5AreHigh() {
        for (Template template : Template.values()) {
            String all = (template.defaultSubject() + " " + template.defaultBody()).toLowerCase(Locale.ROOT);
            assertThat(all).as(template.name()).doesNotContain("monthly", "quarterly", "annual", "ad-hoc", "recurring",
                    "on behalf", "frequency");
        }
        assertThat(Template.values()).filteredOn(t -> t.importance() == Template.Importance.HIGH)
                .containsExactly(Template.T4, Template.T5);
    }
}
