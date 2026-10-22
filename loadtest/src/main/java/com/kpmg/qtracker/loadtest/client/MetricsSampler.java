package com.kpmg.qtracker.loadtest.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the application's metrics (/actuator/metrics, loadtest profile only) every few seconds with its own
 * signed-in session, not counted in the results: the pool (Hikari active, pending, acquire time), Tomcat's busy
 * threads, the heap (used, live data after GC), GC pauses, CPU.
 */
final class MetricsSampler implements Runnable {

    /** column -> metric path and statistic */
    private static final Map<String, String[]> METRICS = new LinkedHashMap<>();

    static {
        METRICS.put("hikari_active", new String[]{"hikaricp.connections.active", "VALUE"});
        METRICS.put("hikari_pending", new String[]{"hikaricp.connections.pending", "VALUE"});
        METRICS.put("hikari_max", new String[]{"hikaricp.connections.max", "VALUE"});
        METRICS.put("hikari_acquire_count", new String[]{"hikaricp.connections.acquire", "COUNT"});
        METRICS.put("hikari_acquire_total_s", new String[]{"hikaricp.connections.acquire", "TOTAL_TIME"});
        METRICS.put("hikari_acquire_max_s", new String[]{"hikaricp.connections.acquire", "MAX"});
        METRICS.put("hikari_timeouts", new String[]{"hikaricp.connections.timeout", "COUNT"});
        METRICS.put("tomcat_busy", new String[]{"tomcat.threads.busy", "VALUE"});
        METRICS.put("tomcat_current", new String[]{"tomcat.threads.current", "VALUE"});
        METRICS.put("heap_used_mb", new String[]{"jvm.memory.used?tag=area:heap", "VALUE"});
        METRICS.put("heap_max_mb", new String[]{"jvm.memory.max?tag=area:heap", "VALUE"});
        METRICS.put("live_after_gc_mb", new String[]{"jvm.gc.live.data.size", "VALUE"});
        METRICS.put("gc_pause_count", new String[]{"jvm.gc.pause", "COUNT"});
        METRICS.put("gc_pause_total_s", new String[]{"jvm.gc.pause", "TOTAL_TIME"});
        METRICS.put("gc_pause_max_s", new String[]{"jvm.gc.pause", "MAX"});
        METRICS.put("process_cpu", new String[]{"process.cpu.usage", "VALUE"});
        METRICS.put("threads_live", new String[]{"jvm.threads.live", "VALUE"});
    }

    private final Session session;
    private final Recorder clock;
    private final long everyMs;
    private final List<double[]> rows = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private volatile boolean stop;

    MetricsSampler(Session session, Recorder clock, long everyMs) {
        this.session = session;
        this.clock = clock;
        this.everyMs = everyMs;
    }

    @Override
    public void run() {
        while (!stop) {
            sample();
            try {
                Thread.sleep(everyMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    void stop() {
        stop = true;
    }

    synchronized double[] sample() {
        double[] row = new double[METRICS.size()];
        int n = 0;
        for (Map.Entry<String, String[]> metric : METRICS.entrySet()) {
            double value = read(metric.getValue()[0], metric.getValue()[1]);
            row[n++] = metric.getKey().endsWith("_mb") ? value / 1024 / 1024 : value;
        }
        rows.add(row);
        times.add(clock.now());
        return row;
    }

    private double read(String path, String statistic) {
        Session.Result result = session.getJson("metrics", "/actuator/metrics/" + path);
        if (!result.ok() || result.body() == null) {
            return Double.NaN;
        }
        Matcher matcher = Pattern.compile("\"statistic\":\"" + statistic + "\",\"value\":([-0-9.Ee]+)")
                .matcher(result.body());
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : Double.NaN;
    }

    String last() {
        if (rows.isEmpty()) {
            return "";
        }
        double[] row = rows.get(rows.size() - 1);
        return String.format(Locale.ROOT, "hikari active=%.0f pending=%.0f tomcat busy=%.0f heap=%.0fMB live=%.0fMB cpu=%.0f%%",
                row[0], row[1], row[7], row[9], row[11], row[15] * 100);
    }

    synchronized void write(Path csv) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("t_ms," + String.join(",", METRICS.keySet()));
        for (int n = 0; n < rows.size(); n++) {
            StringBuilder line = new StringBuilder().append(times.get(n));
            for (double value : rows.get(n)) {
                line.append(',').append(String.format(Locale.ROOT, "%.4f", value));
            }
            lines.add(line.toString());
        }
        Files.write(csv, lines, StandardCharsets.UTF_8);
    }

    /** Max of each metric over the samples from fromMs on, and the first and last values of the counters. */
    synchronized List<String> summary(long fromMs, long toMs) {
        List<String> keys = new ArrayList<>(METRICS.keySet());
        double[] max = new double[keys.size()];
        double[] first = null;
        double[] lastRow = null;
        java.util.Arrays.fill(max, Double.NaN);
        for (int n = 0; n < rows.size(); n++) {
            if (times.get(n) < fromMs || times.get(n) > toMs + everyMs) {
                continue;
            }
            double[] row = rows.get(n);
            if (first == null) {
                first = row;
            }
            lastRow = row;
            for (int k = 0; k < row.length; k++) {
                if (!Double.isNaN(row[k]) && (Double.isNaN(max[k]) || row[k] > max[k])) {
                    max[k] = row[k];
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("metric,max,first,last");
        for (int k = 0; k < keys.size(); k++) {
            out.add(String.format(Locale.ROOT, "%s,%.3f,%.3f,%.3f", keys.get(k), max[k],
                    first == null ? Double.NaN : first[k], lastRow == null ? Double.NaN : lastRow[k]));
        }
        return out;
    }
}
