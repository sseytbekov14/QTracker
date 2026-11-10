package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The Control Description in the header of View Control and Performance cycle (2026-11-10): the text with its line
 * breaks, trimmed and escaped, in a block the script folds to two lines with "Show more" (control-description.js);
 * the button is hidden until the script finds the text longer than two lines. No description: no block.
 */
class ControlDescriptionHeaderIT extends ReleaseScenarioSupport {

    private static final String LONG = "  The Firm reviews the HR records every month.\n"
            + "1. New joiners confirmed independence.\n"
            + "2. Leavers <b>removed</b> from the systems & lists.\n"
            + "3. Exceptions are reported to the SoQM Admin Team.\n\n";

    @Test
    void bothPages_showTheDescription_withItsLineBreaks_inTheFoldingBlock() throws Exception {
        User soqm = soqm("cdh-soqm");
        MockHttpSession session = login(soqm);
        Control draft = control("HR-CDH-D", "DRAFT", people().soqm(soqm), c -> c.setControlDescription(LONG));
        Control running = control("HR-CDH-R", "IN_PROGRESS", people().facilitator(myEdit("cdh-fac"))
                .operator(myEdit("cdh-op")).soqm(soqm).owner(myEdit("cdh-po")), c -> c.setControlDescription(LONG));

        for (String page : new String[] {"/view-control/" + draft.getId(), "/performance-cycle/" + running.getId()}) {
            String html = body(call(session, get(page)));
            assertThat(html).as(page)
                    .contains("data-control-description", "class=\"ctl-desc-text\" id=\"controlDescriptionText\"",
                            "aria-controls=\"controlDescriptionText\"", "aria-expanded=\"false\" hidden>Show more</button>",
                            "/js/control-description.js?v=1", "/css/style.css?v=25")
                    // trimmed, the line breaks kept for white-space: pre-line, the markup in it shown as text
                    .contains(">The Firm reviews the HR records every month.\n1. New joiners confirmed independence.\n"
                            + "2. Leavers &lt;b&gt;removed&lt;/b&gt; from the systems &amp; lists.\n"
                            + "3. Exceptions are reported to the SoQM Admin Team.</p>")
                    .doesNotContain("<b>removed</b>");
        }
    }

    @Test
    void noDescription_orOnlySpaces_noBlock() throws Exception {
        User soqm = soqm("cdh2-soqm");
        MockHttpSession session = login(soqm);
        Control none = control("HR-CDH-N", "DRAFT", people().soqm(soqm), c -> c.setControlDescription(null));
        Control blank = control("HR-CDH-B", "IN_PROGRESS", people().facilitator(myEdit("cdh2-fac"))
                .operator(myEdit("cdh2-op")).soqm(soqm).owner(myEdit("cdh2-po")), c -> c.setControlDescription("  \n "));

        assertThat(body(call(session, get("/view-control/" + none.getId())))).doesNotContain("data-control-description");
        assertThat(body(call(session, get("/performance-cycle/" + blank.getId())))).doesNotContain("data-control-description");
    }
}
