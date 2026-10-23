package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Action Centre grid (dashboard.html): the components' cards only (fragments/action-centre.html); the KDN controls
 * have their own page (/kdn, decision of 2026-10-13), and the role KDN has no dashboard at all.
 */
class KdnControlsTemplateTest {

    private static final Path CARD = Path.of("src/main/resources/templates/fragments/action-centre.html");
    private static final Path DASHBOARD = Path.of("src/main/resources/templates/dashboard.html");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void theGrid_isTheComponentsCards_noKdnCard() throws IOException {
        String html = read(DASHBOARD);
        String grid = html.substring(html.indexOf("<div class=\"ac-grid\">"), html.indexOf("<div class=\"ac-legend\""));
        assertThat(grid.split(java.util.regex.Pattern.quote("fragments/action-centre :: card("), -1)).hasSize(2);
        assertThat(grid).contains("card(${c}, ${'/component/' + c.code()})")
                .doesNotContain("kdnSummary", "kdnHref", "<div class=\"ac-card-head\">");
    }

    @Test
    void theRoleKdn_getsOnlyTheNotificationsPane() throws IOException {
        String html = read(DASHBOARD);
        assertThat(html).contains("id=\"pane-dashboard\" role=\"tabpanel\" th:unless=\"${notificationsOnly}\"",
                "id=\"pane-action\" role=\"tabpanel\" th:unless=\"${notificationsOnly}\"",
                "document.getElementById('sidebarDashboard')?.addEventListener",
                "document.getElementById('sidebarActionCentre')?.addEventListener");
    }

    @Test
    void card_onlyALink_withItsFiveCountersInWords() throws IOException {
        String card = read(CARD);
        assertThat(card).contains("th:fragment=\"card(c, href)\"", "class=\"ac-card\"", "th:href=\"@{${href}}\"",
                "<dt>Total</dt>", "<dt>In progress</dt>", "<dt>In review</dt>", "<dt>Completed</dt>", "<dt>Overdue</dt>",
                "${c.viewAllLabel()}", "No controls", "role=\"img\"");
        assertThat(card).doesNotContain("<button", "<form", "<input", "<script");
    }

    @Test
    void actionQueueNeedsItsItems_whichKdnUsersNeverGet() throws IOException {
        String html = read(DASHBOARD);
        assertThat(html).contains("<section class=\"action-queue\" th:if=\"${actionItems != null}\"",
                "th:if=\"${actionItemsTotal != null and actionItemsTotal > 0}\"",
                "th:if=\"${actionItemsTotal != null and actionItemsTotal == 0}\"");
    }
}
