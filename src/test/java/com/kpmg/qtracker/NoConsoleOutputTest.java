package com.kpmg.qtracker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checked on the sources (2026-11-05): the application writes through its logger, never to the console directly - a
 * direct print bypasses the log levels, and the old ones wrote the e-mail addresses of a control's people at every
 * save. Only the one-off commands (config/*Command.java) print: their report is their output.
 */
class NoConsoleOutputTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    @Test
    void onlyTheCommands_printToTheConsole() throws IOException {
        List<String> printing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("System.out.print") || source.contains("System.err.print")
                        || source.contains(".printStackTrace(")) {
                    printing.add(file.getFileName().toString());
                }
            }
        }
        assertThat(printing).allMatch(name -> name.endsWith("Command.java"), "only the one-off commands");
    }
}
