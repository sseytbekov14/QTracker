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
 * The Control Description in the header of View Control and Performance cycle, in Node
 * (src/test/resources/js/control-description.mjs): a long text folded to two lines with "Show more", opened and folded
 * again with aria-expanded; a text that fits gets no button.
 */
class ControlDescriptionScriptTest {

    @Test
    void aLongDescription_showsTwoLinesAndShowMore_aShortOneNoButton() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/control-description.mjs",
                "src/main/resources/static/js/control-description.js").redirectErrorStream(true).start();
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
