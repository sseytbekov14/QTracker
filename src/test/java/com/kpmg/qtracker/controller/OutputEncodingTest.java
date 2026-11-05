package com.kpmg.qtracker.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Output encoding in the browser code (2026-11-05): text from the server, the user or a file name goes into the page
 * as text (textContent) or through the one shared escapeHtml (static/js/html-escape.js), never as raw HTML.
 * Server-side Thymeleaf escapes by itself (th:text, no th:utext); these checks are about the pages' own JavaScript.
 */
class OutputEncodingTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final String SHARED = "html-escape.js";

    /** ${...} in an innerHTML template literal that are not text: the toast's own icon and its message (see below). */
    private static final Set<String> NOT_TEXT = Set.of("icons[type] || ''", "message");

    /** ${...} in toast messages that are numbers or the page's own text. */
    private static final Set<String> TOAST_SAFE = Set.of("existingNames[label].size", "formatSize(totalBytes)",
            "MAX_REQUEST_SIZE_MB", "MAX_FILE_SIZE_MB");

    @Test
    void escapeHtmlAndThePeoplePicker_inNode() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");

        Process process = new ProcessBuilder(node, "src/test/resources/js/html-encoding.mjs",
                JS.resolve(SHARED).toString(), JS.resolve("view-control.js").toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("\"failures\":[]");
    }

    @Test
    void thereIsOneEscapeHtml_andEveryPageUsingItLoadsIt() throws IOException {
        for (Path file : pageCode()) {
            String source = read(file);
            if (!file.getFileName().toString().equals(SHARED)) {
                assertThat(source).as(file + " has its own escapeHtml").doesNotContain("function escapeHtml(");
            }
            if (file.startsWith(TEMPLATES) && source.contains("escapeHtml(")) {
                assertThat(source).as(file + " uses escapeHtml").contains("<script src=\"/js/" + SHARED);
            }
        }
    }

    @Test
    void innerHtmlTemplates_putValuesInOnlyThroughEscapeHtml() throws IOException {
        Pattern literal = Pattern.compile("\\.innerHTML\\s*=\\s*`([^`]*)`");
        List<String> raw = new ArrayList<>();
        for (Path file : pageCode()) {
            Matcher m = literal.matcher(read(file));
            while (m.find()) {
                for (String value : interpolations(m.group(1))) {
                    if (!isEscaped(value) && !NOT_TEXT.contains(value)) {
                        raw.add(file.getFileName() + ": ${" + value + "}");
                    }
                }
            }
        }
        assertThat(raw).as("values put into innerHTML without escapeHtml").isEmpty();
    }

    @Test
    void viewControlToasts_escapeEveryTextThatIsNotThePagesOwn() throws IOException {
        List<String> raw = new ArrayList<>();
        for (String line : read(TEMPLATES.resolve("view-control.html")).split("\n")) {
            int at = line.indexOf("showToast(");
            if (at < 0 || line.contains("function showToast(")) {
                continue;
            }
            String call = line.substring(at);
            for (String value : interpolations(call)) {
                if (!isEscaped(value) && !TOAST_SAFE.contains(value)) {
                    raw.add(line.trim());
                }
            }
            if (withoutEscapeCalls(call).matches(".*\\b(message|error|err|data)\\.\\w+.*")) {
                raw.add(line.trim());
            }
        }
        assertThat(raw).as("toasts showing a text without escapeHtml").isEmpty();
    }

    private static boolean isEscaped(String value) {
        return (value.startsWith("escapeHtml(") || value.startsWith("escapeAttribute(")) && value.endsWith(")");
    }

    /** Drops every escapeHtml(...) call, brackets matched, so what is left is the unescaped part. */
    private static String withoutEscapeCalls(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("escapeHtml(", i)) {
                int depth = 0;
                int j = i + "escapeHtml".length();
                for (; j < text.length(); j++) {
                    if (text.charAt(j) == '(') depth++;
                    if (text.charAt(j) == ')' && --depth == 0) break;
                }
                i = j + 1;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    /** The expressions inside ${...}, brackets matched. */
    private static List<String> interpolations(String template) {
        List<String> values = new ArrayList<>();
        int start = template.indexOf("${");
        while (start >= 0) {
            int depth = 0;
            int j = start + 1;
            for (; j < template.length(); j++) {
                if (template.charAt(j) == '{') depth++;
                if (template.charAt(j) == '}' && --depth == 0) break;
            }
            values.add(template.substring(start + 2, Math.min(j, template.length())).trim());
            start = template.indexOf("${", j);
        }
        return values;
    }

    private static List<Path> pageCode() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> js = Files.list(JS); Stream<Path> html = Files.walk(TEMPLATES)) {
            js.filter(p -> p.toString().endsWith(".js")).forEach(files::add);
            html.filter(p -> p.toString().endsWith(".html")).forEach(files::add);
        }
        return files;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
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
