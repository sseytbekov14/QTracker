package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Action Centre's "KDN controls" block (fragments/kdn-controls.html) and where dashboard.html puts it: it only
 * links (View Control, the KDN filter of Controls), has a heading it is named by, and comes first for KDN users,
 * whose page has no action block.
 */
class KdnControlsTemplateTest {

    private static final Path FRAGMENT = Path.of("src/main/resources/templates/fragments/kdn-controls.html");
    private static final Path DASHBOARD = Path.of("src/main/resources/templates/dashboard.html");
    private static final Pattern HREF = Pattern.compile("th:href=\"([^\"]+)\"");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void blockOnlyLinks_toViewControlAndTheKdnFilter_noEditOrWorkflowButton() throws IOException {
        String html = read(FRAGMENT);
        List<String> links = new ArrayList<>();
        Matcher href = HREF.matcher(html);
        while (href.find()) {
            links.add(href.group(1));
        }
        assertThat(links).isNotEmpty().allMatch(link -> link.startsWith("@{/controls(kdn=1") || link.startsWith("@{/view-control/"));
        assertThat(html).doesNotContain("<form", "<input", "/edit", "/initiate", "/api/", "workflow-btn");
        // The one button only shows the rest of the list
        assertThat(html.split("<button", -1)).hasSize(2);
        assertThat(html).contains("<button type=\"button\" class=\"kdn-show-all\"", "aria-controls=\"kdnControlList\"",
                "aria-expanded=\"false\"", "id=\"kdnControlList\"");
    }

    @Test
    void blockIsASectionNamedByItsHeading_withLabelledCounters_andTextBadges() throws IOException {
        String html = read(FRAGMENT);
        assertThat(html).contains("aria-labelledby=\"kdnBlockTitle\"", "<h2 id=\"kdnBlockTitle\"", "KDN controls",
                "aria-label=\"KDN controls by status\"", ">Total<", ">In progress<", ">In review<", ">Completed<",
                ">Overdue<", "No KDN controls yet", "View all KDN controls");
        // Overdue and Reopened say so in words, not only by colour; icons are hidden from screen readers
        assertThat(html).contains("</i>Overdue", "</i>Reopened");
        Matcher icon = Pattern.compile("<i class=\"bi [^\"]+\"([^>]*)>").matcher(html);
        while (icon.find()) {
            assertThat(icon.group(1)).contains("aria-hidden=\"true\"");
        }
    }

    @Test
    void dashboard_kdnUsersGetTheBlockFirst_othersAfterTheComponents_actionQueueNeedsItsItems() throws IOException {
        String html = read(DASHBOARD);
        String pane = html.substring(html.indexOf("id=\"pane-action\""), html.indexOf("id=\"pane-notifications\""));
        int first = pane.indexOf("th:if=\"${kdnUser and kdnOverview != null}\"");
        int last = pane.indexOf("th:if=\"${!kdnUser and kdnOverview != null}\"");
        assertThat(first).isPositive().isLessThan(pane.indexOf("class=\"ac-summary\""));
        assertThat(last).isGreaterThan(pane.indexOf("class=\"ac-legend\""));
        assertThat(pane.split("~\\{fragments/kdn-controls :: block}", -1)).hasSize(3);
        // "Awaiting my action" and the header line about it render only with actionItems, which KDN users never get
        assertThat(html).contains("<section class=\"action-queue\" th:if=\"${actionItems != null}\"",
                "th:if=\"${actionItemsTotal != null and actionItemsTotal > 0}\"",
                "th:if=\"${actionItemsTotal != null and actionItemsTotal == 0}\"");
    }
}
