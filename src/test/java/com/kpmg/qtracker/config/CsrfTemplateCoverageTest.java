package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * Every request that changes data must carry the CSRF token: a fetch with a method other than GET only works on a
 * page that loads fragments/csrf (meta + js/csrf.js) in its head, and a form posted by the browser needs th:action
 * (Thymeleaf then adds the hidden _csrf field).
 */
class CsrfTemplateCoverageTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path SCRIPTS = Path.of("src/main/resources/static/js");
    private static final String CSRF_FRAGMENT = "~{fragments/csrf :: csrf}";

    private static final Pattern WRITE_FETCH = Pattern.compile("method\\s*:\\s*(?!['\"]GET['\"])\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_TAG = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POST_METHOD = Pattern.compile("\\bmethod\\s*=\\s*['\"]post['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_SCRIPT = Pattern.compile("<script[^>]+src=\"/js/([\\w.-]+\\.js)");
    private static final Pattern BYPASSES_FETCH = Pattern.compile("XMLHttpRequest|\\$\\.ajax|jQuery\\.ajax|sendBeacon");

    @Test
    void pagesThatSendWritesLoadTheCsrfFragmentInTheirHead() throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> writingPages = new ArrayList<>();
        for (Path template : files(TEMPLATES, ".html")) {
            String html = read(template);
            List<String> writers = new ArrayList<>();
            if (sendsWrites(html)) {
                writers.add("inline script");
            }
            Matcher script = LOCAL_SCRIPT.matcher(html);
            while (script.find()) {
                Path js = SCRIPTS.resolve(script.group(1));
                if (Files.exists(js) && sendsWrites(read(js))) {
                    writers.add(script.group(1));
                }
            }
            if (writers.isEmpty()) {
                continue;
            }
            writingPages.add(name(template));
            int fragment = html.indexOf(CSRF_FRAGMENT);
            int headEnd = html.indexOf("</head>");
            if (fragment < 0 || headEnd < 0 || fragment > headEnd) {
                missing.add(name(template) + " (" + String.join(", ", writers) + ")");
            }
        }
        assertThat(missing).as("pages that send POST/PUT/DELETE without fragments/csrf in <head>").isEmpty();
        // Guards against the scan silently finding nothing
        assertThat(writingPages).contains("view-control.html", "admin-users.html", "new-control.html");
    }

    @Test
    void formsPostedByTheBrowserUseThAction() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path template : files(TEMPLATES, ".html")) {
            Matcher form = FORM_TAG.matcher(read(template));
            while (form.find()) {
                String tag = form.group();
                // Still in SecurityConfig's CSRF ignore list
                boolean ignoredByServer = tag.contains("action=\"/notifications/mark-all-read\"");
                if (POST_METHOD.matcher(tag).find() && !tag.contains("th:action") && !ignoredByServer) {
                    missing.add(name(template) + ": " + tag);
                }
            }
        }
        assertThat(missing).as("POST forms without th:action (no hidden _csrf field)").isEmpty();
    }

    @Test
    void scriptsUseOnlyFetchForRequests() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> sources = new ArrayList<>(files(TEMPLATES, ".html"));
        sources.addAll(files(SCRIPTS, ".js"));
        for (Path source : sources) {
            Matcher bypass = BYPASSES_FETCH.matcher(read(source));
            if (bypass.find()) {
                found.add(name(source) + ": " + bypass.group());
            }
        }
        assertThat(found).as("requests that js/csrf.js does not see").isEmpty();
    }

    private static boolean sendsWrites(String source) {
        return source.contains("fetch(") && WRITE_FETCH.matcher(source).find();
    }

    private static List<Path> files(Path root, String extension) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(extension))
                    .filter(path -> !path.startsWith(SCRIPTS.resolve("vendor")))
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String name(Path path) {
        return path.getFileName().toString();
    }
}
