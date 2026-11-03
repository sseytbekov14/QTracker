package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.HtmlUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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
    private static final String DETAILS = "Control: HR-1/FY27\nDescription: Payroll <check> & review\n"
            + "Your role: Facilitator and Control Operator\nDeadline: 19 Nov 2026";

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
        values.put("OCCURRENCE_DATE", "5 Nov 2026");
        values.put("DEADLINE", "19 Nov 2026");
        values.put("LINK", LINK);
        values.put("RETURNED_BY", "Control Operator");
        return values;
    }

    /** The in-app text of each template, word for word. */
    static String inApp(Template template) {
        String control = "Control HR-1/FY27";
        String body = switch (template) {
            case T1 -> control + " has been created.\nYour role: Facilitator and Control Operator\n"
                    + "Occurrence date: 5 Nov 2026\nDeadline: 19 Nov 2026\n"
                    + "Please complete and submit the control before the deadline.";
            case T2 -> control + " occurs today (5 Nov 2026).\nYour role: Facilitator and Control Operator\n"
                    + "Deadline: 19 Nov 2026\nPlease complete and submit the control before the deadline.";
            case T3 -> control + " is awaiting your action.\nDeadline: 19 Nov 2026";
            case T4 -> "The deadline for control HR-1/FY27 is today (19 Nov 2026).\n"
                    + "Please complete it by the end of the day.";
            case T5 -> "The deadline for control HR-1/FY27 has passed (19 Nov 2026). "
                    + "The control is not completed.\nPlease complete it as soon as possible.";
            case T6 -> control + " has been sent for your review.\nPlease review it and submit it to the SoQM Admin Team.\n"
                    + "Deadline: 19 Nov 2026";
            case T7 -> control + " has been returned to you by the Control Operator for rework.\n"
                    + "Please review the comments and resubmit the control.\nDeadline: 19 Nov 2026";
            case T8 -> control + " has been sent for your review.\nPlease review it and submit it to the Process Owner.";
            case T9 -> control + " has been returned to you by the SoQM Admin Team for rework.\n"
                    + "Please review the comments and resubmit the control.\nDeadline: 19 Nov 2026";
            case T10 -> control + " has been reviewed by the SoQM Admin Team and is ready for your review.\n"
                    + "Please review and complete the control.\nDeadline: 19 Nov 2026";
            case T11 -> control + " has been returned to you by the Process Owner for rework.\n"
                    + "Please review the comments and resubmit the control.\nDeadline: 19 Nov 2026";
            case T12 -> control + " has been completed.";
        };
        return "Dear Anna Smith,\n\n" + body + "\n\nKind regards,\nSoQM Team";
    }

    @ParameterizedTest
    @EnumSource(Template.class)
    void eachTemplate_dearName_theText_goToControlInTheEmail_noLinkInApp(Template template) {
        TemplateRenderer.Rendered rendered = renderer.render(template, values()).rendered();

        assertThat(rendered.title()).isEqualTo(template.defaultSubject());
        assertThat(rendered.text()).isEqualTo(inApp(template))
                .doesNotContain("http").doesNotContainIgnoringCase("link").doesNotEndWith("\n");
        String firstLine = inApp(template).split("\n")[2];
        assertThat(rendered.html()).startsWith("<!DOCTYPE html")
                .contains("<title>" + template.defaultSubject() + "</title>")
                .contains("mso-hide:all;\">" + HtmlUtils.htmlEscape(firstLine) + "&#8199;")
                .containsPattern("<td class=\"hdr\" [^>]*><img src=\"cid:qtracker-logo\" width=\"164\" height=\"36\""
                        + " alt=\"KPMG QTracker\" [^>]*></td>")
                .containsPattern("<h1 [^>]*>" + Pattern.quote(template.defaultSubject()) + "</h1>")
                .contains("Dear Anna Smith,<br>")
                .containsPattern("<!--\\[if mso]><a href=\"" + Pattern.quote(LINK) + "\" target=\"_blank\">"
                        + "<img src=\"cid:qtracker-button\"")
                .containsPattern("<!--\\[if !mso]><!-- --><a href=\"" + Pattern.quote(LINK) + "\"[^>]*>Go to Control</a>")
                .contains("Kind regards,<br>\nSoQM Team</td>")
                .contains(EmailLayout.FOOTER);
        // The address: only in the hrefs of the button, never as text
        assertThat(rendered.html().replace("href=\"" + LINK + "\"", ""))
                .doesNotContain("qtracker.example.test");
        // In the e-mail the role and the deadline stand in the details, once
        assertThat(rendered.mailText()).isEqualTo(template.defaultSubject() + "\n\n"
                + inApp(template).replaceAll("(?m)^(Your role|Deadline): .*\n", "")
                .replaceAll("\n+Kind regards,", "\n\n" + DETAILS + "\n\nGo to Control: " + LINK + "\n\nKind regards,")
                + "\n\n--\n" + EmailLayout.footerText());
    }

    /**
     * Classic Outlook (the [if mso] part) gets the header and the button as pictures, which its dark theme does not
     * repaint; every other client gets the coloured header cell and the link styled as a button.
     */
    @Test
    void classicOutlook_getsTheHeaderAndTheButtonAsPictures_theOthersTheHtml() {
        String html = renderer.render(Template.T8, values()).rendered().html();

        assertThat(html).contains("<!--[if mso]><tr><td style=\"padding:0;font-size:0;line-height:0;\">"
                        + "<img src=\"cid:qtracker-header\" width=\"600\" height=\"64\" alt=\"KPMG QTracker\"")
                .contains("<!--[if mso]><a href=\"" + LINK + "\" target=\"_blank\"><img src=\"cid:qtracker-button\""
                        + " width=\"200\" height=\"42\" alt=\"Go to Control\"")
                .doesNotContain("v:roundrect");
        String others = html.replaceAll("(?s)<!--\\[if mso]>.*?<!\\[endif]-->", "");
        assertThat(others).doesNotContain("qtracker-header", "qtracker-button")
                .contains("<td class=\"hdr\"", "cid:qtracker-logo")
                .containsPattern("<a href=\"" + Pattern.quote(LINK) + "\" class=\"btn\" style=\"[^\"]*color:#ffffff;[^\"]*\">"
                        + "Go to Control</a>");
        // Outlook does not read the [if !mso] part: the coloured header is not shown to it twice
        assertThat(html).containsPattern("<!--\\[if !mso]><!-- --><tr><td class=\"hdr\"[^<]*<img[^>]*></td></tr><!--<!\\[endif]-->");
        // Every picture the HTML names is one of the layout's
        assertThat(EmailLayout.INLINE_IMAGES).containsOnlyKeys("qtracker-logo", "qtracker-header", "qtracker-button");
    }

    /** Under every e-mail: the automated-message line, Privacy | Legal, INTERNAL USE ONLY and the copyright of this year. */
    @Test
    void theFooter_hasTheLegalLines_ofThisYear_inBothParts() {
        TemplateRenderer.Rendered rendered = renderer.render(Template.T12, values()).rendered();
        String year = String.valueOf(java.time.Year.now(java.time.ZoneId.of("Asia/Almaty")));

        assertThat(rendered.html()).containsSubsequence(EmailLayout.FOOTER, "Privacy | Legal<br>\n",
                "INTERNAL USE ONLY<br>\n", "© " + year + " KPMG Audit LLC, KPMG Tax and Advisory LLC and KPMG Valuation LLC",
                "a private English company limited by guarantee. All rights reserved.</span></td></tr>");
        assertThat(rendered.mailText()).endsWith("--\n" + EmailLayout.FOOTER + "\n\nPrivacy | Legal\nINTERNAL USE ONLY\n© "
                + year + " " + EmailLayout.COPYRIGHT_HOLDERS);
    }

    /** The subject is the name of the step or event alone: no "[QTracker]", no prefix, no Control ID (2026-11-03). */
    @ParameterizedTest
    @EnumSource(Template.class)
    void theSubject_isTheNameAlone(Template template) {
        TemplateRenderer.Rendered rendered = renderer.render(template, values()).rendered();

        assertThat(rendered.subject()).isEqualTo(template.defaultSubject()).isEqualTo(rendered.title())
                .doesNotContain("[QTracker]", "HR-1/FY27", "Action required").doesNotContainIgnoringCase("urgent");
        assertThat(rendered.html()).contains("<title>" + template.defaultSubject() + "</title>");
    }

    @Test
    void theSubject_takesANameChangedInTheTable() {
        stored.get("T8").setSubject("Our own name");

        assertThat(renderer.render(Template.T8, values()).rendered().subject()).isEqualTo("Our own name");
    }

    /**
     * The sentence names the control by its ID alone, the description being free text that can name another control;
     * it stands only in its own line of the e-mail's details, under its own label.
     */
    @ParameterizedTest
    @EnumSource(Template.class)
    void noSentence_namesTheDescription(Template template) {
        assertThat(template.defaultSubject() + template.defaultBody()).doesNotContain("{CONTROL_NAME}");

        TemplateRenderer.Rendered rendered = renderer.render(template, values()).rendered();

        assertThat(rendered.text()).contains("HR-1/FY27").doesNotContain("Payroll");
        assertThat(rendered.mailText()).contains("HR-1/FY27").containsOnlyOnce("Payroll")
                .contains("\nDescription: Payroll <check> & review\n");
        assertThat(rendered.html()).containsOnlyOnce("Payroll")
                .contains(">Payroll &lt;check&gt; &amp; review</td>");
    }

    private static String row(String label, String value) {
        return ">" + label + "</td><td class=\"dtl-v\" valign=\"top\" style=\"padding:6px 12px 6px 0;"
                + "font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:19px;color:#222222;"
                + "word-break:break-word;\">" + value + "</td></tr>";
    }

    @Test
    void theDetails_everyLine_betweenTheTextAndTheButton() {
        Map<String, String> values = values();
        values.put(NotificationValues.FROM, "Control Operator");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T8, values).rendered();

        String html = rendered.html();
        assertThat(html).contains(row("Control", "HR-1/FY27"), row("Description", "Payroll &lt;check&gt; &amp; review"),
                row("Your role", "Facilitator and Control Operator"), row("Deadline", "19 Nov 2026"),
                row("From", "Control Operator"));
        assertThat(html).containsSubsequence("Please review it and submit it to the Process Owner.", ">Control</td>",
                ">Description</td>", ">Your role</td>", ">Deadline</td>", ">From</td>", "cid:qtracker-button", "Kind regards,");
        assertThat(rendered.mailText()).contains("Process Owner.\n\nControl: HR-1/FY27\nDescription: Payroll <check> & review\n"
                + "Your role: Facilitator and Control Operator\nDeadline: 19 Nov 2026\nFrom: Control Operator\n\n"
                + "Go to Control: " + LINK + "\n\nKind regards,");
    }

    @Test
    void theDetails_aLineWithoutAValue_isLeftOut() {
        Map<String, String> values = values();
        values.put("CONTROL_NAME", "  ");
        values.remove("ROLES");
        values.remove("DEADLINE");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T8, values).rendered();

        assertThat(rendered.html()).contains(row("Control", "HR-1/FY27"))
                .doesNotContain(">Description</td>", ">Your role</td>", ">Deadline</td>", ">From</td>");
        assertThat(rendered.mailText()).contains("\n\nControl: HR-1/FY27\n\nGo to Control: ")
                .doesNotContain("Description:", "Your role:", "Deadline:", "From:");
    }

    @Test
    void theDetails_nothingWithoutAnyValue() {
        assertThat(EmailLayout.details(List.of(new EmailLayout.Row("Control", null), new EmailLayout.Row("From", " "))))
                .isEmpty();
        assertThat(EmailLayout.plainDetails(List.of(new EmailLayout.Row("Control", null)))).isEmpty();
    }

    @Test
    void theDetails_areEscaped_theLineBreaksOfADescriptionStay() {
        Map<String, String> values = values();
        values.put("CONTROL_ID", "HR-1 <i>");
        values.put("CONTROL_NAME", " <script>alert(1)</script> & co\r\nline \"two\" ");
        values.put(NotificationValues.FROM, "<b>Someone</b>");
        values.put("ROLES", "<u>Role</u>");

        String html = renderer.render(Template.T8, values).rendered().html();

        assertThat(html).contains(row("Control", "HR-1 &lt;i&gt;"),
                row("Description", "&lt;script&gt;alert(1)&lt;/script&gt; &amp; co<br>\nline &quot;two&quot;"),
                row("From", "&lt;b&gt;Someone&lt;/b&gt;"), row("Your role", "&lt;u&gt;Role&lt;/u&gt;"))
                .doesNotContain("<script>", "<i>", "<b>Someone", "<u>");
    }

    @Test
    void theRoleAndTheDeadline_standOnceInTheEmail_andStayInApp() {
        TemplateRenderer.Rendered rendered = renderer.render(Template.T1, values()).rendered();

        assertThat(rendered.html()).doesNotContain("Your role:", "Deadline:").contains("Occurrence date: 5 Nov 2026<br>");
        assertThat(rendered.mailText()).containsOnlyOnce("Your role: ").containsOnlyOnce("Deadline: ");
        assertThat(rendered.text()).contains("Your role: Facilitator and Control Operator\n", "Deadline: 19 Nov 2026\n")
                .doesNotContain("Control: HR-1/FY27");
    }

    @Test
    void aTextWithoutALink_hasItsDetailsAtTheEnd_once() {
        stored.get("T8").setBody("Dear {NAME},\nSee the control.");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T8, values()).rendered();

        assertThat(rendered.html()).containsSubsequence("See the control.", ">Control</td>").containsOnlyOnce(">Control</td>");
        assertThat(rendered.mailText()).contains("See the control.\n\nControl: HR-1/FY27\n");
    }

    @Test
    void twoLinesOfLink_oneDetailsBlock() {
        stored.get("T8").setBody("Dear {NAME},\n{LINK}\nThen again:\n{LINK}");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T8, values()).rendered();

        assertThat(rendered.html()).containsOnlyOnce(">Control</td>");
        assertThat(rendered.mailText()).containsOnlyOnce("Control: HR-1/FY27");
    }

    /** The colour of a label is never its only sign: each has its word. */
    @ParameterizedTest
    @CsvSource({"T3, Reminder, bdg-n, #E5E7EB", "T4, Due today, bdg-o, #B45309", "T5, Overdue, bdg-r, #B91C1C"})
    void aReminder_hasItsLabelOverTheHeading(Template template, String word, String colourClass, String colour) {
        String html = renderer.render(template, values()).rendered().html();

        assertThat(html).containsPattern("<td class=\"" + colourClass + "\" bgcolor=\"" + colour + "\"[^>]*>" + word + "</td>");
        assertThat(html).containsSubsequence(">" + word + "</td>", "<h1 ");
    }

    @ParameterizedTest
    @EnumSource(value = Template.class, mode = EnumSource.Mode.EXCLUDE, names = {"T3", "T4", "T5"})
    void noOtherEmail_hasALabel(Template template) {
        assertThat(renderer.render(template, values()).rendered().html())
                .doesNotContain("class=\"bdg-n\"", "class=\"bdg-o\"", "class=\"bdg-r\"");
    }

    @Test
    void theDue_takesThePlaceOfTheDeadline() {
        Map<String, String> values = values();
        values.put(NotificationValues.DUE, "19 Nov 2026 (in 4 working days)");

        TemplateRenderer.Rendered rendered = renderer.render(Template.T3, values).rendered();

        assertThat(rendered.html()).contains(row("Due", "19 Nov 2026 (in 4 working days)")).doesNotContain(">Deadline</td>");
        assertThat(rendered.mailText()).contains("\nDue: 19 Nov 2026 (in 4 working days)\n").doesNotContain("Deadline:");
        assertThat(rendered.text()).contains("Deadline: 19 Nov 2026").doesNotContain("Due:");
    }

    private static Map<String, String> returned(String comment) {
        Map<String, String> values = values();
        values.put(NotificationValues.RETURNED_BY, "Process Owner");
        values.put(NotificationValues.FROM, "SoQM Admin Team (on behalf of Process Owner)");
        values.put(NotificationValues.COMMENT, comment);
        return values;
    }

    @ParameterizedTest
    @EnumSource(value = Template.class, names = {"T7", "T9", "T11"})
    void theCommentOfAReturn_aQuoteBetweenTheDetailsAndTheButton(Template template) {
        TemplateRenderer.Rendered rendered = renderer.render(template, returned("Fix the steps.")).rendered();

        assertThat(rendered.html()).containsSubsequence(">From</td>", "border-left:4px solid #00338D",
                ">Comment from SoQM Admin Team (on behalf of Process Owner)</div>", ">Fix the steps.</div>", "cid:qtracker-button");
        assertThat(rendered.mailText()).contains("\n\nComment from SoQM Admin Team (on behalf of Process Owner):\n"
                + "Fix the steps.\n\nGo to Control: ");
    }

    @Test
    void theComment_isEscaped_itsLineBreaksStay_andIsNoTemplate() {
        String comment = " <script>alert(1)</script> & co\r\n\r\n<b>line</b> {LINK} {UNKNOWN} ";

        TemplateRenderer.Result result = renderer.render(Template.T7, returned(comment));

        assertThat(result.ok()).isTrue();
        assertThat(result.rendered().html())
                .contains(">&lt;script&gt;alert(1)&lt;/script&gt; &amp; co<br>\n<br>\n&lt;b&gt;line&lt;/b&gt; {LINK} {UNKNOWN}</div>")
                .doesNotContain("<script>", "<b>line");
        assertThat(result.rendered().mailText()).contains(":\n<script>alert(1)</script> & co\n\n<b>line</b> {LINK} {UNKNOWN}\n\nGo to");
        assertThat(result.rendered().text()).doesNotContain("script");
    }

    @Test
    void theWhoInTheCommentLabel_isEscaped() {
        Map<String, String> values = returned("Fix.");
        values.put(NotificationValues.FROM, "<b>Who</b>");

        assertThat(renderer.render(Template.T7, values).rendered().html()).contains(">Comment from &lt;b&gt;Who&lt;/b&gt;</div>");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n"})
    void noComment_noQuote(String comment) {
        TemplateRenderer.Rendered rendered = renderer.render(Template.T7, returned(comment)).rendered();

        assertThat(rendered.html()).doesNotContain("Comment from", "border-left");
        assertThat(rendered.mailText()).doesNotContain("Comment from");
    }

    @Test
    void aComment_ofTheLongestLength_staysWhole() {
        String comment = "x".repeat(EmailLayout.COMMENT_MAX);

        TemplateRenderer.Rendered rendered = renderer.render(Template.T7, returned(comment)).rendered();

        assertThat(rendered.html()).contains(">" + comment + "</div>").doesNotContain(EmailLayout.COMMENT_SHORTENED);
        assertThat(rendered.mailText()).contains(comment).doesNotContain(EmailLayout.COMMENT_SHORTENED);
    }

    @Test
    void aLongComment_isCut_saysSo_andTheInAppTextIsNotConcerned() {
        String comment = "x".repeat(EmailLayout.COMMENT_MAX) + "y";

        TemplateRenderer.Rendered rendered = renderer.render(Template.T7, returned(comment)).rendered();

        String cut = "x".repeat(EmailLayout.COMMENT_MAX - 1) + "…";
        assertThat(rendered.html()).contains(">" + cut + "<br>\n", EmailLayout.COMMENT_SHORTENED).doesNotContain("y</div>");
        assertThat(rendered.mailText()).contains(":\n" + cut + "\n" + EmailLayout.COMMENT_SHORTENED + "\n\nGo to Control: ");
    }

    @Test
    void aLongComment_isCutBetweenCharacters_neverWithinOne() {
        // Each of these is two chars of Java: 1000 characters, not 500
        String comment = "😀".repeat(EmailLayout.COMMENT_MAX + 1);

        String text = renderer.render(Template.T7, returned(comment)).rendered().mailText();

        assertThat(text).contains(":\n" + "😀".repeat(EmailLayout.COMMENT_MAX - 1) + "…\n");
    }

    @Test
    void aDarkTheme_forEveryPartWithAColour_theInlineStylesStayTheLightOne() {
        String html = renderer.render(Template.T7, returned("Fix.")).rendered().html();

        for (String part : List.of("bg", "hdr", "card", "btn", "ttl", "txt", "dtl", "dtl-k", "dtl-v", "ftr", "qt", "qt-k", "qt-v")) {
            assertThat(html).as(part).contains("class=\"" + part + "\"").containsPattern("\\." + part + "\\{");
        }
        assertThat(html).contains(".bdg-n{", ".bdg-o{", ".bdg-r{");
        assertThat(html).contains("<meta name=\"color-scheme\" content=\"light dark\">",
                "@media (prefers-color-scheme: dark)", "[data-ogsc] .dtl-k{", "[data-ogsb] .card{")
                .contains("background-color:#ffffff;").contains("color:#222222;");
    }

    /** A text written by hand in the table may still use the description, and must not fail for it. */
    @Test
    void aTextChangedByHand_mayStillUseTheDescription() {
        stored.get("T8").setBody("Dear {NAME},\n{CONTROL_NAME}\n{LINK}");

        assertThat(renderer.render(Template.T8, values()).rendered().text())
                .isEqualTo("Dear Anna Smith,\nPayroll <check> & review");
    }

    /** One layout for all: only the title, the preheader and the text differ. */
    @Test
    void everyTemplate_theSameLayout_onlyTheTitleAndTheTextDiffer() {
        String layout = layout(renderer.render(Template.T1, values()).rendered().html());
        for (Template template : Template.values()) {
            assertThat(layout(renderer.render(template, values()).rendered().html())).as(template.name())
                    .isEqualTo(layout);
        }
        assertThat(layout).contains("<title></title>", "<h1", "</h1>", "#00338D", EmailLayout.FOOTER)
                .doesNotContain("Dear", "Go to Control");
    }

    private static String layout(String html) {
        return html.replaceAll("<title>.*?</title>", "<title></title>")
                .replaceAll("(<h1 [^>]*>).*?(</h1>)", "$1$2")
                .replaceAll("(mso-hide:all;\">).*?(&#8199;)", "$1$2")
                .replaceAll("<tr><td style=\"padding:0 0 8px 0;\">.*</tr>\n", "")
                .replaceAll("(?s)(color:#222222;\">).*?(</td></tr>\n</table>\n</td></tr>\n<tr><td class=\"ftr\")", "$1$2");
    }

    @Test
    void theTitle_andThePreheader_areEscaped() {
        stored.get("T8").setSubject("SoQM review: {CONTROL_ID} <b>&");
        Map<String, String> values = values();
        values.put("CONTROL_ID", "HR-1 \"x\"");

        String html = renderer.render(Template.T8, values).rendered().html();

        assertThat(html).contains("<title>SoQM review: HR-1 &quot;x&quot; &lt;b&gt;&amp;</title>")
                .containsPattern("<h1 [^>]*>SoQM review: HR-1 &quot;x&quot; &lt;b&gt;&amp;</h1>")
                .contains("mso-hide:all;\">Control HR-1 &quot;x&quot; has been sent")
                .doesNotContain("<b>");
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

        // The button's table keeps its own space: the empty line after it is left out
        assertThat(rendered.html()).contains("Open <a href=\"" + LINK + "\" "
                + "style=\"color:#00338D;font-weight:bold\">Go to Control</a> now.<br>\n<table role=\"presentation\"")
                .contains("</table>\nKind regards,<br>\nSoQM Team");
        assertThat(rendered.text()).isEqualTo("Dear Anna Smith,\nOpen  now.\n\nKind regards,\nSoQM Team");
        assertThat(rendered.mailText()).isEqualTo("Awaiting your action\n\nDear Anna Smith,\nOpen Go to Control (" + LINK
                + ") now.\n\n" + DETAILS + "\n\nGo to Control: " + LINK + "\n\nKind regards,\nSoQM Team\n\n--\n"
                + EmailLayout.footerText());
    }

    @Test
    void aLongFirstLine_isCutInThePreheader() {
        Map<String, String> values = values();
        values.put("CONTROL_ID", "x".repeat(300));

        String html = renderer.render(Template.T12, values).rendered().html();

        assertThat(html).containsPattern("mso-hide:all;\">Control x{131}…&#8199;");
    }

    @Test
    void typedText_andTheName_areEscapedInTheEmail_asTheyAreInApp() {
        Map<String, String> values = values();
        values.put("NAME", "O'Neil <Anna> & co");
        values.put("CONTROL_ID", "HR-1 <check> & review");

        TemplateRenderer.Rendered t1 = renderer.render(Template.T1, values).rendered();

        assertThat(t1.html()).contains("Dear O&#39;Neil &lt;Anna&gt; &amp; co,", "HR-1 &lt;check&gt; &amp; review");
        assertThat(t1.text()).startsWith("Dear O'Neil <Anna> & co,").contains("HR-1 <check> & review");
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
        assertThat(renderer.render(Template.T8, values()).rendered().title()).isEqualTo("SoQM review: HR-1/FY27");

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
