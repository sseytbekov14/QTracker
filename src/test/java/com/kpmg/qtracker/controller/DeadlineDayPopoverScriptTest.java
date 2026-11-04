package com.kpmg.qtracker.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "+N more" of the dashboard's Deadline Calendar in Node (src/test/resources/js/deadline-day-popover.mjs):
 * js/deadline-day-popover.js as shipped with dashboard.html's own deadlineDayPopoverContent. A day of N controls shows
 * all N as links with the calendar's colours, in a popover on body placed in the viewport wherever the day is in the
 * grid; it closes on Esc, a click outside, a second click, and when its link is gone or out of view.
 */
class DeadlineDayPopoverScriptTest {

    private static final Path DASHBOARD = Path.of("src/main/resources/templates/dashboard.html");

    @Test
    void aDayOfNControls_showsAllNInAPopoverInTheViewport() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/deadline-day-popover.mjs",
                "src/main/resources/static/js/deadline-day-popover.js", DASHBOARD.toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("\"failures\":[]");
    }

    /** The calendar opens "+N more" in this popover, not in FullCalendar's own (cut off by the calendar box). */
    @Test
    void theCalendar_opensMoreInThePopover() throws Exception {
        String dashboard = Files.readString(DASHBOARD, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(dashboard)
                .contains("<script src=\"/js/deadline-day-popover.js")
                .contains("moreLinkClick: function(info)")
                .contains("DeadlineDayPopover.toggle(link, deadlineDayPopoverContent(info));")
                .contains("datesSet: function() {\n            DeadlineDayPopover.close();");
        assertThat(dashboard.indexOf("/js/deadline-day-popover.js"))
                .as("the popover script is loaded before the dashboard's script that uses it")
                .isLessThan(dashboard.indexOf("function initializeDeadlineCalendar()"));
    }

    private static String findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String candidate : List.of(name, name + ".exe")) {
                Path file = Path.of(dir, candidate);
                if (Files.isRegularFile(file) && Files.isExecutable(file)) {
                    return file.toString();
                }
            }
        }
        return null;
    }
}
