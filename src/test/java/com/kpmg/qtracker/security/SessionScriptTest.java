package com.kpmg.qtracker.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * js/session.js in Node with a clock the test moves (src/test/resources/js/session-keepalive.mjs): the banner on a
 * 401 of /api/**, no reload and no cleared text, the keepalive at most once in 5 minutes of typing.
 */
class SessionScriptTest {

    @Test
    void theBannerAndTheKeepalive_behaveAsDecided() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/session-keepalive.mjs",
                "src/main/resources/static/js/session.js").redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("\"keepalives\":2").contains("\"failures\":[]");
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
