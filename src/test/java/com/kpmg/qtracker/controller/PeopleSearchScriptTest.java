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
 * The people pickers of the Assignment tab in Node (src/test/resources/js/people-search.mjs): View Control's
 * userMatchesQuery finds a person by any part of the name or the e-mail, as Control Shared With does.
 */
class PeopleSearchScriptTest {

    @Test
    void anyPartOfTheNameOrTheEmail_findsThePerson() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/people-search.mjs",
                "src/main/resources/static/js/view-control.js").redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("\"failures\":[]");
    }

    @Test
    void theFourPickers_useIt_noPickerSearchesFromTheStartOnly() throws Exception {
        String source = Files.readString(Path.of("src/main/resources/static/js/view-control.js"), StandardCharsets.UTF_8);
        for (String list : List.of("facilitatorUsers", "controlOperatorUsers", "soqmLeadUsers", "processOwnerUsers")) {
            assertThat(source).as(list).contains("const filteredUsers = " + list + ".filter(user => userMatchesQuery(user, query));");
        }
        assertThat(source).doesNotContain("startsWith(query)");
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
