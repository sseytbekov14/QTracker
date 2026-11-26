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
 * Every inline script of the templates parses (2026-11-26: a lost backslash in an apostrophe stopped the whole Admin
 * Panel script, and the server tests could not see it). The files of static/js are checked the same way.
 */
class TemplateScriptSyntaxTest {

    @Test
    void everyInlineScriptOfTheTemplates_parses() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/template-scripts.mjs",
                "src/main/resources/templates").redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).as(output).doesNotContain("BROKEN").containsPattern("OK [1-9]");
    }

    @Test
    void everyScriptFileOfThePages_parses() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        List<Path> files;
        try (var stream = Files.list(Path.of("src/main/resources/static/js"))) {
            files = stream.filter(file -> file.toString().endsWith(".js")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            Process process = new ProcessBuilder(node, "--check", file.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(file + "\n" + output).isZero();
        }
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
