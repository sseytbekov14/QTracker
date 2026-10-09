package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Release test pass, stage E: the date rules over whole years, not single examples - Control Operation Deadline =
 * operation date + 14 calendar days for every frequency, the next date of each frequency (month ends, 29 February,
 * the turn of the year), the business day in Almaty against a server in UTC, Overdue and Closed late at the
 * deadline day, the SoQM Year at 30 September / 1 October, reminders never after the deadline, and the browser's
 * own preview and dd.mm.yyyy reading in three time zones.
 */
class ReleaseScenarioDatesTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final LocalDate LAST = LocalDate.of(2029, 12, 31);
    private static final List<String> FREQUENCIES =
            List.of("Monthly", "Quarterly", "Semi Annual", "Annual", "Recurring", "Ad-hoc");

    private final ControlScheduleCalculator calculator = new ControlScheduleCalculator();

    @Test
    void deadline_isTheOperationDatePlus14CalendarDays_everyDayOfFourYears_everyFrequency() {
        for (LocalDate date = FIRST; !date.isAfter(LAST); date = date.plusDays(1)) {
            for (String frequency : FREQUENCIES) {
                assertThat(calculator.calculateDeadline(ControlFrequency.fromValue(frequency), date))
                        .as("%s %s", frequency, date).isEqualTo(date.plusDays(14));
            }
        }
        // Spot checks written out by hand
        assertThat(calculator.calculateDeadline(ControlFrequency.MONTHLY, LocalDate.of(2026, 12, 25)))
                .isEqualTo(LocalDate.of(2027, 1, 8));
        assertThat(calculator.calculateDeadline(ControlFrequency.ANNUAL, LocalDate.of(2028, 2, 15)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(calculator.calculateDeadline(ControlFrequency.QUARTERLY, LocalDate.of(2027, 2, 15)))
                .isEqualTo(LocalDate.of(2027, 3, 1));
    }

    @Test
    void nextDate_ofEachFrequency_monthEndsLeapDaysAndTheTurnOfTheYear() {
        Map<String, Integer> months = Map.of("Monthly", 1, "Quarterly", 3, "Semi Annual", 6, "Annual", 12,
                "Recurring", 3);
        for (LocalDate date = FIRST; !date.isAfter(LAST); date = date.plusDays(1)) {
            for (Map.Entry<String, Integer> entry : months.entrySet()) {
                LocalDate next = calculator.calculateNextDate(ControlFrequency.fromValue(entry.getKey()), date);
                LocalDate expected = date.plusMonths(entry.getValue());
                assertThat(next).as("%s %s", entry.getKey(), date).isEqualTo(expected);
                // Never later than the same day number, and in the right month
                assertThat(next.getDayOfMonth()).isLessThanOrEqualTo(date.getDayOfMonth());
            }
            assertThat(calculator.calculateNextDate(ControlFrequency.AD_HOC, date)).isNull();
        }
        assertThat(calculator.calculateNextDate(ControlFrequency.MONTHLY, LocalDate.of(2026, 1, 31)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(calculator.calculateNextDate(ControlFrequency.MONTHLY, LocalDate.of(2028, 1, 31)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(calculator.calculateNextDate(ControlFrequency.ANNUAL, LocalDate.of(2028, 2, 29)))
                .isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(calculator.calculateNextDate(ControlFrequency.QUARTERLY, LocalDate.of(2026, 11, 30)))
                .isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(calculator.calculateNextDate(ControlFrequency.SEMI_ANNUAL, LocalDate.of(2026, 12, 31)))
                .isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void theBusinessDay_isAlmatys_alsoWhenTheServerRunsInUtc() {
        // Kazakhstan has been on UTC+5 since 1 March 2024: midnight in Almaty is 19:00 UTC the day before
        assertThat(ZoneId.of("Asia/Almaty").getRules().getOffset(Instant.parse("2026-10-09T12:00:00Z")))
                .isEqualTo(ZoneOffset.ofHours(5));
        assertThat(DeadlineOverdue.today(Instant.parse("2026-10-09T18:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(DeadlineOverdue.today(Instant.parse("2026-10-09T19:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(DeadlineOverdue.today(Instant.parse("2026-12-31T19:30:00Z"))).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(DeadlineOverdue.endOfDay(LocalDate.of(2026, 10, 9)).toString()).isEqualTo("2026-10-09T23:59+05:00");
    }

    @Test
    void overdue_notOnTheDeadlineDay_fromTheNextDay_closedLateOnlyAfterTheDeadlineDay() {
        LocalDate deadline = LocalDate.of(2026, 10, 9);
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", deadline, deadline)).isFalse();
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", deadline, deadline.plusDays(1))).isTrue();
        assertThat(DeadlineOverdue.daysOverdue("IN_PROGRESS", deadline, deadline.plusDays(3))).isEqualTo(3);
        assertThat(DeadlineOverdue.isOverdue("DRAFT", deadline, deadline.plusDays(1))).as("drafts too").isTrue();
        assertThat(DeadlineOverdue.isOverdue("COMPLETED", deadline, deadline.plusDays(30))).isFalse();
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", null, deadline)).isFalse();
        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", deadline, deadline)).isFalse();
        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", deadline, deadline.plusDays(1))).isTrue();
        assertThat(DeadlineOverdue.isClosedLate("REVIEW", deadline, deadline.plusDays(1))).isFalse();
        assertThat(DeadlineOverdue.isDueSoon("IN_PROGRESS", deadline, deadline, 0)).isTrue();
    }

    @Test
    void soqmYear_turnsOnTheFirstOfOctober() {
        assertThat(SoqmYear.current(LocalDate.of(2026, 9, 30))).isEqualTo("1 OCT 2025 - 30 SEP 2026");
        assertThat(SoqmYear.current(LocalDate.of(2026, 10, 1))).isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(SoqmYear.current(LocalDate.of(2027, 1, 1))).isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(SoqmYear.isValid("1 OCT 2026 - 30 SEP 2027")).isTrue();
        for (String wrong : List.of("1 OCT 2026 - 30 SEP 2028", "FY27", "", "2026-2027", "1 oct 2026 - 30 sep 2027x")) {
            assertThat(SoqmYear.isValid(wrong)).as(wrong).isFalse();
        }
    }

    @Test
    void reminders_neverAfterTheDeadline_neverOnTheOperationDay_inOrder_forEveryStartOfTwoYears() {
        WorkingDaysService workingDays = new WorkingDaysService();
        List<int[]> offsets = List.of(new int[]{3, 6}, new int[]{5, 12}, new int[]{5, 25});
        for (LocalDate operation = FIRST; operation.isBefore(FIRST.plusYears(2)); operation = operation.plusDays(1)) {
            LocalDate deadline = calculator.calculateDeadline(ControlFrequency.MONTHLY, operation);
            for (int[] set : offsets) {
                LocalDate[] dates = ReminderDays.dates(workingDays, operation, deadline, set);
                LocalDate previous = operation;
                for (LocalDate date : dates) {
                    if (date == null) {
                        continue;
                    }
                    assertThat(date).as("%s offsets %s", operation, java.util.Arrays.toString(set))
                            .isAfter(previous).isBeforeOrEqualTo(deadline);
                    assertThat(date.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
                    previous = date;
                }
            }
        }
    }

    // ---- the browser's code (Node) ----

    @Test
    void browserPreviewAndTypedDates_agreeWithTheServer_inAlmatyUtcAndNewYork() throws Exception {
        String node = findOnPath("node");
        assumeTrue(node != null, "Node.js is not installed; the browser code cannot run");
        List<Map<String, String>> schedule = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 1, 1); !date.isAfter(LocalDate.of(2028, 12, 31)); date = date.plusDays(1)) {
            for (String frequency : FREQUENCIES) {
                schedule.add(Map.of("date", date.toString(), "frequency", frequency));
            }
        }
        Map<String, String> typed = new LinkedHashMap<>();
        typed.put("09.10.2026", "2026-10-09");
        typed.put("9.10.2026", "2026-10-09");
        typed.put("29.02.2028", "2028-02-29");
        typed.put("29.02.2027", null);
        typed.put("31.02.2026", null);
        typed.put("31.04.2026", null);
        typed.put("00.01.2026", null);
        typed.put("01.13.2026", null);
        typed.put("2026-10-09", null);
        typed.put("09/10/2026", null);
        typed.put("09.10.26", null);
        typed.put(" 09.10.2026 ", "2026-10-09");
        typed.put("", null);
        typed.put("aa.bb.cccc", null);

        for (String zone : List.of("Asia/Almaty", "UTC", "America/New_York")) {
            Map<String, Object> result = run(node, zone, Map.of("schedule", schedule, "typed", new ArrayList<>(typed.keySet())));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> previews = (List<Map<String, String>>) result.get("schedule");
            List<String> mismatches = new ArrayList<>();
            for (int i = 0; i < schedule.size(); i++) {
                LocalDate date = LocalDate.parse(schedule.get(i).get("date"));
                ControlFrequency frequency = ControlFrequency.fromValue(schedule.get(i).get("frequency"));
                String deadline = Objects.toString(calculator.calculateDeadline(frequency, date), null);
                String next = Objects.toString(calculator.calculateNextDate(frequency, date), null);
                if (!Objects.equals(deadline, previews.get(i).get("deadline")) || !Objects.equals(next, previews.get(i).get("next"))) {
                    mismatches.add(zone + " " + frequency + " " + date + " server " + deadline + "/" + next
                            + " js " + previews.get(i));
                }
            }
            assertThat(mismatches).as("browser preview vs server in %s", zone).isEmpty();
            @SuppressWarnings("unchecked")
            List<String> read = (List<String>) result.get("typed");
            List<String> keys = new ArrayList<>(typed.keySet());
            for (int i = 0; i < keys.size(); i++) {
                assertThat(read.get(i)).as("'%s' in %s", keys.get(i), zone).isEqualTo(typed.get(keys.get(i)));
            }
        }
    }

    private static Map<String, Object> run(String node, String zone, Object input) throws Exception {
        ObjectMapper json = new ObjectMapper();
        ProcessBuilder builder = new ProcessBuilder(node, "src/test/resources/js/release-dates.mjs",
                "src/main/resources/static/js/view-control.js", "src/main/resources/static/js/date-format.js")
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("TZ", zone);
        Process process = builder.start();
        process.getOutputStream().write(json.writeValueAsBytes(input));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("node exit code").isZero();
        return json.readValue(output, new TypeReference<>() {
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
}
