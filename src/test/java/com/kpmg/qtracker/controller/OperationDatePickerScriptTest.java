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
 * The Control Operation Date calendar in Node (src/test/resources/js/date-picker-today.mjs): js/date-picker.js as
 * shipped, then View Control's own readOperationDate. Today, a picked day and a typed date are what Save sends; Clear
 * and text that is no date are not sent as a date.
 */
class OperationDatePickerScriptTest {

    @Test
    void todayAPickedDayAndATypedDate_areWhatSaveSends() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/date-picker-today.mjs",
                "src/main/resources/static/js/date-format.js",
                "src/main/resources/static/js/date-picker.js",
                "src/main/resources/static/js/view-control.js").redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("\"failures\":[]");
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
