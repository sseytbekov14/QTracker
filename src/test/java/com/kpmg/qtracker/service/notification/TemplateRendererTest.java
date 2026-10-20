package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The texts of v5 with their values (TP-1..3): read from the table, every placeholder filled or nothing made. */
class TemplateRendererTest {

    private final NotificationTemplateTextRepository repository = mock(NotificationTemplateTextRepository.class);
    private final TemplateRenderer renderer = new TemplateRenderer(repository);
    private final Map<String, NotificationTemplateText> stored = new HashMap<>();

    @BeforeEach
    void templatesOfV5() {
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
        values.put("CONTROL_ID", "HR-1/FY27");
        values.put("CONTROL_NAME", "Payroll <check> & review");
        values.put("OCCURRENCE_DATE", "05-11-2026");
        values.put("DEADLINE", "19-11-2026");
        values.put("LINK", "https://qtracker.example.test/view-control/7");
        values.put("ROLE", "Control Operator");
        values.put("RETURNED_BY", "SoQM Admin Team");
        return values;
    }

    /** The rule of the link: "Go to Control" in the e-mail with the address hidden; nothing of it in-app. */
    @Test
    void everyTemplate_linksGoToControlInTheEmail_andHasNoLinkInApp() {
        for (Template template : Template.values()) {
            TemplateRenderer.Rendered rendered = renderer.render(template, values()).rendered();

            assertThat(rendered.html()).as(template.name())
                    .containsPattern("<a href=\"https://qtracker\\.example\\.test/view-control/7\"[^>]*>Go to Control</a>");
            assertThat(rendered.html().replaceAll("href=\"[^\"]*\"", "")).as(template.name()).doesNotContain("http");
            assertThat(rendered.text()).as(template.name()).doesNotContain("http").doesNotContain("{LINK}")
                    .doesNotEndWith("\n");
        }
    }

    @Test
    void aLineOfLink_isAButton_aLinkWithinALine_aBoldLink_inAppWithoutBoth() {
        stored.get("T3").setBody("Dear {ROLE},\nOpen {LINK} now.\n{LINK}\n\nKind regards,\nSoQM Team");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T3, values()).rendered();

        assertThat(rendered.html()).contains("Open <a href=\"https://qtracker.example.test/view-control/7\" "
                + "style=\"color:#00338D;font-weight:bold\">Go to Control</a> now.<br>\n<table role=\"presentation\"")
                .contains("</table>\n<br>\nKind regards,<br>\nSoQM Team");
        assertThat(rendered.text()).isEqualTo("Dear Control Operator,\nOpen  now.\n\nKind regards,\nSoQM Team");
    }

    @Test
    void typedText_isEscapedInTheEmail() {
        TemplateRenderer.Rendered t1 = renderer.render(Template.T1, values()).rendered();

        assertThat(t1.subject()).isEqualTo("The creation of the SoQM control");
        assertThat(t1.html()).contains("HR-1/FY27 Payroll &lt;check&gt; &amp; review");
        assertThat(t1.text()).contains("HR-1/FY27 Payroll <check> & review");
    }

    @Test
    void roleAndReturnedBy_comeFromTheValues() {
        assertThat(renderer.render(Template.T3, values()).rendered().text()).startsWith("Dear Control Operator,");
        assertThat(renderer.render(Template.T5, values()).rendered().text()).startsWith("Dear Control Operator,")
                .contains("deadline (19-11-2026) has passed");
        assertThat(renderer.render(Template.T7, values()).rendered().text())
                .contains("has been reviewed by the SoQM Admin Team and requires some adjustments.");
        assertThat(renderer.render(Template.T4, values()).rendered().text())
                .contains("the deadline for the following control is today, 19-11-2026:");
    }

    @Test
    void t12_withoutLink_getsTheButtonBeforeItsClosingWords() {
        TemplateRenderer.Rendered t12 = renderer.render(Template.T12, values()).rendered();

        assertThat(t12.subject()).isEqualTo("SoQM control completed");
        assertThat(t12.text()).doesNotContain("https://");
        assertThat(t12.html()).contains(TemplateRenderer.BUTTON_LABEL, "href=\"https://qtracker.example.test/view-control/7\"");
        assertThat(t12.html().indexOf(TemplateRenderer.BUTTON_LABEL)).isLessThan(t12.html().indexOf("Kind regards"));
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
    }

    @Test
    void aTextChangedInTheTable_isUsedAtOnce_andAnUnknownPlaceholderFails() {
        stored.get("T8").setSubject("SoQM review: {CONTROL_ID}");
        assertThat(renderer.render(Template.T8, values()).rendered().subject()).isEqualTo("SoQM review: HR-1/FY27");

        stored.get("T8").setBody("Dear team, {UNKNOWN}");
        assertThat(renderer.render(Template.T8, values()).error()).contains("{UNKNOWN}");

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
