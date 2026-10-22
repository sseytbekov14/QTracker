package com.kpmg.qtracker.loadtest.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every request's answer: what it was (label), when it started, how long it took, its status. Samples of the
 * warm-up are kept apart. At the end: per label RPS, p50, p95, p99, max and the errors (5xx, 4xx, timeouts,
 * failed connections), on the console and as CSV.
 */
final class Recorder {

    /** status: HTTP status; TIMEOUT or IO_ERROR when there was no answer. */
    record Sample(String label, long startMs, long millis, int status, Outcome outcome) {
    }

    /** A wanted answer, an answer the scenario allows (a 409 of a race, a move someone else made first), or an error. */
    enum Outcome { OK, EXPECTED, ERROR }

    static final int TIMEOUT = -1;
    static final int IO_ERROR = -2;

    private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
    private final AtomicInteger errorLines = new AtomicInteger();
    private final long zero = System.currentTimeMillis();
    private volatile long measureFrom = Long.MAX_VALUE;
    private volatile long measureTo = Long.MAX_VALUE;

    long now() {
        return System.currentTimeMillis() - zero;
    }

    void startMeasuring() {
        measureFrom = now();
    }

    void startMeasuringAt(long fromMs) {
        measureFrom = fromMs;
    }

    long measureFrom() {
        return measureFrom;
    }

    void stopMeasuring() {
        measureTo = now();
    }

    void record(String label, long startMs, long millis, int status, Outcome outcome, String detail) {
        samples.add(new Sample(label, startMs, millis, status, outcome));
        if (outcome == Outcome.ERROR && errorLines.incrementAndGet() <= 300) {
            errors.add(startMs + "ms " + label + " " + statusText(status) + " " + millis + "ms " + (detail == null ? ""
                    : detail.replaceAll("\\s+", " ").substring(0, Math.min(300, detail.replaceAll("\\s+", " ").length()))));
        }
    }

    static String statusText(int status) {
        return status == TIMEOUT ? "TIMEOUT" : status == IO_ERROR ? "IO_ERROR" : String.valueOf(status);
    }

    /** Requests and errors in the last window, for the progress line. */
    String progress(long fromMs) {
        int count = 0;
        int failed = 0;
        List<Long> times = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.startMs() >= fromMs) {
                count++;
                times.add(sample.millis());
                if (sample.outcome() == Outcome.ERROR) {
                    failed++;
                }
            }
        }
        long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(Locale.ROOT, "req=%d err=%d p50=%dms p95=%dms", count, failed, percentile(sorted, 50),
                percentile(sorted, 95));
    }

    /** The table of the measured part: one line per label and TOTAL, written to the console and CSV. */
    List<String> summary(Path csv) throws IOException {
        Map<String, List<Sample>> byLabel = new TreeMap<>();
        List<Sample> all = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.startMs() >= measureFrom && sample.startMs() < measureTo) {
                byLabel.computeIfAbsent(sample.label(), key -> new ArrayList<>()).add(sample);
                all.add(sample);
            }
        }
        double seconds = Math.max(1, (Math.min(measureTo, now()) - measureFrom) / 1000.0);
        List<String> lines = new ArrayList<>();
        lines.add("label,count,rps,p50_ms,p95_ms,p99_ms,max_ms,err_5xx,err_4xx,err_3xx,timeouts,io_errors,expected_refusals,err_pct");
        for (Map.Entry<String, List<Sample>> entry : byLabel.entrySet()) {
            lines.add(line(entry.getKey(), entry.getValue(), seconds));
        }
        lines.add(line("TOTAL", all, seconds));
        Files.write(csv, lines, StandardCharsets.UTF_8);
        return lines;
    }

    private static String line(String label, List<Sample> samples, double seconds) {
        long[] times = samples.stream().mapToLong(Sample::millis).sorted().toArray();
        int e5 = 0;
        int e4 = 0;
        int e3 = 0;
        int timeouts = 0;
        int io = 0;
        int expected = 0;
        for (Sample sample : samples) {
            int status = sample.status();
            if (sample.outcome() == Outcome.EXPECTED) {
                expected++;
            } else if (sample.outcome() == Outcome.OK) {
                continue;
            } else if (status >= 500) {
                e5++;
            } else if (status >= 400) {
                e4++;
            } else if (status == TIMEOUT) {
                timeouts++;
            } else if (status == IO_ERROR) {
                io++;
            } else {
                e3++;
            }
        }
        double errPct = samples.isEmpty() ? 0 : 100.0 * (e5 + e4 + e3 + timeouts + io) / samples.size();
        return String.format(Locale.ROOT, "%s,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f", label, samples.size(),
                samples.size() / seconds, percentile(times, 50), percentile(times, 95), percentile(times, 99),
                times.length == 0 ? 0 : times[times.length - 1], e5, e4, e3, timeouts, io, expected, errPct);
    }

    static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    void writeErrors(Path file) throws IOException {
        Files.write(file, new ArrayList<>(errors), StandardCharsets.UTF_8);
    }

    /** The console form of the CSV lines: aligned columns. */
    static String table(List<String> csv) {
        List<String[]> rows = csv.stream().map(line -> line.split(",")).toList();
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int n = 0; n < row.length; n++) {
                widths[n] = Math.max(widths[n], row[n].length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (String[] row : rows) {
            for (int n = 0; n < row.length; n++) {
                out.append(n == 0 ? String.format("%-" + widths[n] + "s", row[n])
                        : String.format("%" + (widths[n] + 2) + "s", row[n]));
            }
            out.append(System.lineSeparator());
        }
        return out.toString();
    }

    static String[] split(String csvLine) {
        return Arrays.stream(csvLine.split(",", -1)).map(String::trim).toArray(String[]::new);
    }
}
