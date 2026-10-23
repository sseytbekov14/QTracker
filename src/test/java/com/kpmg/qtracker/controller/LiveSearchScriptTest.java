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
 * The live search of the controls lists in Node (src/test/resources/js/live-search.mjs): js/component-controls.js as
 * shipped. The search applies 300 ms after the last key, the KDN box at once, on page 1 with the sorting and the page
 * size kept; an older answer never replaces a newer one; the search field stays; the URL follows.
 */
class LiveSearchScriptTest {

    @Test
    void theSearchAndTheKdnBox_applyAsOneTypesOrClicks() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/live-search.mjs",
                "src/main/resources/static/js/component-controls.js").redirectErrorStream(true).start();
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
