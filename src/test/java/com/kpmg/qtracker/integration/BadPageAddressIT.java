package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A page address with a value of the wrong kind (2026-11-10): the page opens again without it - no "Whitelabel Error
 * Page" on the Admin Panel, no error page with an ERROR in the log on the lists - and a wrong id in the path is a page
 * that does not exist.
 */
class BadPageAddressIT extends ReleaseScenarioSupport {

    @Test
    void aWrongValue_opensThePageWithoutIt_theOtherFiltersKept() throws Exception {
        User soqm = soqm("bpa-soqm");
        MockHttpSession session = login(soqm);

        String[][] cases = {
                {"/admin/audit?page=abc&group=USERS", "/admin/audit?group=USERS"},
                {"/admin/audit?from=bad-date&q=x", "/admin/audit?q=x"},
                {"/admin/emails?from=31-12&to=2026-11-10&status=SENT", "/admin/emails?to=2026-11-10&status=SENT"},
                {"/admin/emails?page=abc", "/admin/emails"},
                {"/component/ALL?q=HR&page=abc", "/component/ALL?q=HR"},
                {"/component/ALL?size=x&status=REVIEW", "/component/ALL?status=REVIEW"},
                {"/kdn?page=abc", "/kdn"},
        };
        for (String[] c : cases) {
            MvcResult result = call(session, get(c[0]));
            assertThat(result.getResponse().getStatus()).as(c[0]).isEqualTo(302);
            assertThat(result.getResponse().getRedirectedUrl()).as(c[0]).isEqualTo(c[1]);
            // and the page it leads to opens
            MvcResult then = call(session, get(c[1]));
            assertThat(then.getResponse().getStatus()).as(c[1]).isIn(200, 403);
        }
        assertThat(body(call(session, get("/admin/audit?group=USERS")))).contains(">Audit trail");
    }

    @Test
    void aWrongIdInThePath_isAPageThatDoesNotExist() throws Exception {
        MockHttpSession session = login(soqm("bpa2-soqm"));
        for (String page : new String[] {"/view-control/abc", "/performance-cycle/abc", "/initiate/1x", "/edit-control/x"}) {
            MvcResult result = call(session, get(page));
            assertThat(result.getResponse().getStatus()).as(page).isEqualTo(404);
            assertThat(body(result)).as(page).contains("The address is not complete or not correct.")
                    .doesNotContain("Whitelabel");
        }
    }
}
