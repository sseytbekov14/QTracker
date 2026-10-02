package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * View Control previews the Control Operation Deadline and the Next Control Operation Date in the
 * browser; the server stores its own calculation. The two must agree, or the page shows dates that
 * change after Save. The JavaScript runs in Node, cut out of view-control.js as shipped; without
 * Node on the machine the test is skipped.
 */
class SchedulePreviewParityTest {

    private static final Path VIEW_CONTROL_JS = Path.of("src/main/resources/static/js/view-control.js");
    private static final Path PREVIEW_SCRIPT = Path.of("src/test/resources/js/schedule-preview.mjs");
    private static final ObjectMapper JSON = new ObjectMapper();

    // The Control tab select sends these values
    private static final List<String> FREQUENCIES =
            List.of("Monthly", "Quarterly", "Semi Annual", "Annual", "Recurring", "Ad-hoc");

    private static final List<LocalDate> DATES = List.of(
            LocalDate.of(2026, 2, 6),    // the example in view-control.js
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 1, 28),
            LocalDate.of(2026, 1, 29),
            LocalDate.of(2026, 1, 30),
            LocalDate.of(2026, 1, 31),   // + 1 month into a 28-day February
            LocalDate.of(2026, 3, 31),   // + 1 month into April (30 days)
            LocalDate.of(2026, 5, 31),
            LocalDate.of(2026, 8, 29),
            LocalDate.of(2026, 8, 31),   // + 6 months into February
            LocalDate.of(2026, 10, 31),
            LocalDate.of(2026, 11, 30),  // + 3 months into February
            LocalDate.of(2026, 12, 25),  // + 7 / + 14 days into the next year
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2027, 8, 31),   // + 6 months into a leap February
            LocalDate.of(2027, 11, 30),  // + 3 months into a leap February
            LocalDate.of(2028, 1, 29),
            LocalDate.of(2028, 1, 30),
            LocalDate.of(2028, 1, 31),   // + 1 month into a leap February
            LocalDate.of(2028, 2, 28),
            LocalDate.of(2028, 2, 29),   // leap day: + 12 months has no 29 February
            LocalDate.of(2028, 3, 31));

    private static String node;

    @BeforeAll
    static void findNode() {
        node = findOnPath("node");
    }

    @Test
    void preview_matchesTheServer_forEveryFrequency() throws Exception {
        List<Case> cases = new ArrayList<>();
        for (String frequency : FREQUENCIES) {
            for (LocalDate date : DATES) {
                cases.add(new Case(date, frequency, frequency));
            }
        }

        assertThat(mismatches(cases)).as("JS preview vs ControlScheduleCalculator").isEmpty();
    }

    /**
     * Older spellings still possible in controls.control_frequency. The select shows the option
     * th:selected in view-control.html maps them to, or the stored value itself as its own option
     * ("As-required/at least annually"), and the preview reads that; the server reads the stored value.
     */
    @Test
    void preview_matchesTheServer_forOlderStoredSpellings() throws Exception {
        Map<String, String> shownForStored = new LinkedHashMap<>();
        shownForStored.put("Annually", "Annual");
        shownForStored.put("Semi-annually", "Semi Annual");
        shownForStored.put("Recurring/other", "Recurring");
        shownForStored.put("As-required/at least annually", "As-required/at least annually");

        List<Case> cases = new ArrayList<>();
        shownForStored.forEach((stored, shown) -> {
            for (LocalDate date : DATES) {
                cases.add(new Case(date, stored, shown));
            }
        });

        assertThat(mismatches(cases)).as("JS preview (value shown) vs server (stored value)").isEmpty();
    }

    /** normalizeControlFrequency reads any spelling as ControlFrequency does, unknown ones included. */
    @Test
    void frequencyReading_matchesControlFrequency() throws Exception {
        List<String> spellings = List.of("Monthly", " monthly ", "QUARTERLY", "Recurring", "Recurring/other",
                "Recurring monthly", "Ad-hoc", "Ad hoc", "adhoc", "Semi Annual", "Semi-annually", "semiannual",
                "Semi", "Annual", "Annually", "As-required/at least annually", "Bi-weekly", "Weekly", "", "   ");
        List<Case> cases = spellings.stream()
                .map(spelling -> new Case(LocalDate.of(2026, 2, 6), spelling, spelling))
                .toList();
        List<Map<String, String>> preview = runPreview(cases);

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < spellings.size(); i++) {
            String server = ControlFrequency.tryFromValue(spellings.get(i)).map(Enum::name).orElse(null);
            String js = preview.get(i).get("frequency");
            String jsAsEnum = js == null ? null : js.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            if (!Objects.equals(server, jsAsEnum)) {
                mismatches.add(String.format("'%s'  server %s  js %s", spellings.get(i), server, js));
            }
        }
        assertThat(mismatches).as("normalizeControlFrequency vs ControlFrequency.tryFromValue").isEmpty();
    }

    private List<String> mismatches(List<Case> cases) throws Exception {
        List<Map<String, String>> preview = runPreview(cases);
        ControlScheduleCalculator calculator = new ControlScheduleCalculator();

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            ControlFrequency frequency = ControlFrequency.fromValue(c.storedFrequency());
            String serverDeadline = Objects.toString(calculator.calculateDeadline(frequency, c.date()), null);
            String serverNext = Objects.toString(calculator.calculateNextDate(frequency, c.date()), null);
            String jsDeadline = preview.get(i).get("deadline");
            String jsNext = preview.get(i).get("next");
            if (!Objects.equals(serverDeadline, jsDeadline)) {
                mismatches.add(String.format("%-30s %s  deadline  server %s  js %s",
                        c.storedFrequency(), c.date(), serverDeadline, jsDeadline));
            }
            if (!Objects.equals(serverNext, jsNext)) {
                mismatches.add(String.format("%-30s %s  next      server %s  js %s",
                        c.storedFrequency(), c.date(), serverNext, jsNext));
            }
        }
        return mismatches;
    }

    private List<Map<String, String>> runPreview(List<Case> cases) throws Exception {
        assumeTrue(node != null, "Node.js is not installed; the JS preview cannot run");
        List<Map<String, String>> input = cases.stream()
                .map(c -> Map.of("date", c.date().toString(), "frequency", c.shownFrequency()))
                .toList();
        Process process = new ProcessBuilder(node, PREVIEW_SCRIPT.toString(), VIEW_CONTROL_JS.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        process.getOutputStream().write(JSON.writeValueAsBytes(input));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("node exit code").isZero();
        return JSON.readValue(output, new TypeReference<>() {
        });
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

    /** One operation date; the frequency the server reads and the one the preview gets. */
    private record Case(LocalDate date, String storedFrequency, String shownFrequency) {
    }
}
