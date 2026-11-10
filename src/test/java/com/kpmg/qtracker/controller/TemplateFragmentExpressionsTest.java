package com.kpmg.qtracker.controller;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every th:replace / th:insert / th:include names its fragment as ~{...} (2026-11-10): Thymeleaf 3.1 wrote a WARN
 * "Deprecated unwrapped fragment expression" into the server log on every View Control, New Control and Initiate
 * page opened with the bare form "fragments/app-modal :: appModal".
 */
class TemplateFragmentExpressionsTest {

    private static final Pattern UNWRAPPED = Pattern.compile("th:(?:replace|insert|include)=\"(?!~\\{)(?!\\$\\{)([^\"]*)\"");

    @Test
    void everyFragmentExpression_isWrapped() throws Exception {
        List<String> unwrapped = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    Matcher matcher = UNWRAPPED.matcher(lines[i]);
                    while (matcher.find()) {
                        unwrapped.add(file.getFileName() + ":" + (i + 1) + " " + matcher.group(1));
                    }
                }
            }
        }
        assertThat(unwrapped).as("write them as ~{...}").isEmpty();
    }
}
