package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Action Centre grid (dashboard.html): the KDN card is the same card as a component's
 * (fragments/action-centre.html), first for KDN users, whose page has no action block, last for the others.
 */
class KdnControlsTemplateTest {

    private static final Path CARD = Path.of("src/main/resources/templates/fragments/action-centre.html");
    private static final Path DASHBOARD = Path.of("src/main/resources/templates/dashboard.html");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void componentsAndKdn_oneCard_kdnFirstForKdnUsers_lastForOthers() throws IOException {
        String html = read(DASHBOARD);
        String grid = html.substring(html.indexOf("<div class=\"ac-grid\">"), html.indexOf("<div class=\"ac-legend\""));
        String kdnFirst = "<th:block th:if=\"${kdnUser and kdnSummary != null}\">";
        String components = "<th:block th:each=\"c : ${componentSummaries}\">";
        String kdnLast = "<th:block th:if=\"${!kdnUser and kdnSummary != null}\">";
        assertThat(grid.indexOf(kdnFirst)).isNotNegative().isLessThan(grid.indexOf(components));
        assertThat(grid.indexOf(kdnLast)).isGreaterThan(grid.indexOf(components));
        assertThat(grid.split("~\\{fragments/action-centre :: card\\(", -1)).hasSize(4);
        assertThat(grid).contains("card(${kdnSummary}, ${kdnHref})", "card(${c}, ${'/component/' + c.code()})")
                .doesNotContain("<div class=\"ac-card-head\">");
    }

    @Test
    void card_onlyALink_withItsCountsInWords() throws IOException {
        String card = read(CARD);
        assertThat(card).contains("th:fragment=\"card(c, href)\"", "class=\"ac-card\"", "th:href=\"@{${href}}\"",
                "' overdue'", "' active'", "' done'", "No controls", "role=\"img\"");
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
