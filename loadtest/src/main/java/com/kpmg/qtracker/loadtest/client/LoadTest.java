package com.kpmg.qtracker.loadtest.client;

import com.kpmg.qtracker.loadtest.client.Data.Person;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The QTracker load test client. Modes:
 * <ul>
 *   <li>mixed (default): N virtual users by role (SoQM, User Edit, Read Only, KDN), started over the ramp-up, each
 *   signing in once and then working with a think time; the warm-up is not counted, then the measured duration.
 *   Output: RPS, p50/p95/p99/max and errors per request label (console and CSV), the app's metrics over time.</li>
 *   <li>races: the same change from two sessions at once (Create Control, same Control ID, same move, same tab).</li>
 *   <li>sqlcount: each page once per role, then the SQL statements per request from the loadtest metric.</li>
 *   <li>smoke: each action once per role, every answer printed (a check of the data and the scenarios).</li>
 * </ul>
 * Arguments (--name=value): url, mode, users, duration, warmup, rampup, think (min-max ms), timeout (s), data,
 * out, name, password, metrics-every (s), rounds.
 */
public final class LoadTest {

    private static final Map<String, Double> ROLE_SHARE = new LinkedHashMap<>();

    static {
        // Share of the virtual users per kind of account; KDN as in the user list (46 of 150)
        ROLE_SHARE.put("soqm", 0.10);
        ROLE_SHARE.put("edit", 0.48);
        ROLE_SHARE.put("editall", 0.04);
        ROLE_SHARE.put("ro", 0.05);
        ROLE_SHARE.put("roall", 0.03);
        ROLE_SHARE.put("kdn", 0.30);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        String url = options.getOrDefault("url", "http://localhost:8092");
        String mode = options.getOrDefault("mode", "mixed");
        int users = Integer.parseInt(options.getOrDefault("users", "10"));
        int duration = Integer.parseInt(options.getOrDefault("duration", "300"));
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "60"));
        int rampup = Integer.parseInt(options.getOrDefault("rampup", String.valueOf(Math.min(warmup, 30))));
        String[] think = options.getOrDefault("think", "1000-3000").split("-");
        int thinkMin = Integer.parseInt(think[0]);
        int thinkMax = Integer.parseInt(think[think.length - 1]);
        Duration timeout = Duration.ofSeconds(Integer.parseInt(options.getOrDefault("timeout", "30")));
        Path out = Path.of(options.getOrDefault("out", "target-loadtest/results"));
        String name = options.getOrDefault("name", mode + "-" + users + "u-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm")));
        String password = options.getOrDefault("password", "LoadTest-2026");
        Files.createDirectories(out);

        Data data = Data.load(Path.of(options.getOrDefault("data", "target-loadtest/data")));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Recorder recorder = new Recorder();
        Recorder quiet = new Recorder();
        System.out.printf("QTracker load test: %s, mode %s, %d controls, %d accounts%n", url, mode,
                data.controls.size(), data.people.size());

        switch (mode) {
            case "races" -> {
                Races races = new Races(data, executor);
                List<String> lines = races.run(person -> signedIn(person, url, recorder, timeout, executor, password),
                        Integer.parseInt(options.getOrDefault("rounds", "5")));
                lines.forEach(System.out::println);
                Files.write(out.resolve(name + "-races.txt"), lines, StandardCharsets.UTF_8);
                recorder.startMeasuring();
                recorder.stopMeasuring();
            }
            case "sqlcount" -> sqlCount(data, url, quiet, timeout, executor, password, out.resolve(name + "-sql.csv"));
            case "smoke" -> smoke(data, url, recorder, timeout, executor, password);
            default -> mixed(data, url, recorder, quiet, timeout, executor, password, users, duration, warmup, rampup,
                    thinkMin, thinkMax, out, name, Integer.parseInt(options.getOrDefault("metrics-every", "5")));
        }
        if (!"sqlcount".equals(mode)) {
            if ("races".equals(mode) || "smoke".equals(mode)) {
                recorder.startMeasuringAt(0);
            }
            List<String> summary = recorder.summary(out.resolve(name + "-summary.csv"));
            recorder.writeErrors(out.resolve(name + "-errors.txt"));
            System.out.println();
            System.out.print(Recorder.table(summary));
            System.out.println("Results: " + out.toAbsolutePath().resolve(name + "-summary.csv"));
        }
        executor.shutdownNow();
        System.exit(0);
    }

    private static void mixed(Data data, String url, Recorder recorder, Recorder quiet, Duration timeout,
                              ExecutorService executor, String password, int users, int duration, int warmup, int rampup,
                              int thinkMin, int thinkMax, Path out, String name, int metricsEvery) throws Exception {
        Session metricsSession = signedIn(data.of("soqm").get(data.of("soqm").size() - 1), url, quiet, timeout, executor,
                password);
        MetricsSampler sampler = new MetricsSampler(metricsSession, recorder, metricsEvery * 1000L);
        Thread samplerThread = Thread.ofVirtual().start(sampler);

        List<Person> assigned = assign(data, users);
        Scenarios scenarios = new Scenarios(data, uploadContent());
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger signedIn = new AtomicInteger();
        AtomicInteger loginFailed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int n = 0; n < users; n++) {
            Person person = assigned.get(n);
            long delay = users <= 1 ? 0 : (long) rampup * 1000L * n / users;
            long seed = 1000L + n;
            threads.add(Thread.ofVirtual().start(() -> {
                Random random = new Random(seed);
                try {
                    Thread.sleep(delay);
                    Session session = new Session(person, url, recorder, timeout, executor);
                    if (!session.login(password)) {
                        loginFailed.incrementAndGet();
                        return;
                    }
                    signedIn.incrementAndGet();
                    while (running.get()) {
                        scenarios.run(scenarios.pick(person, random), session, random);
                        Thread.sleep(thinkMin + random.nextInt(Math.max(1, thinkMax - thinkMin + 1)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    System.out.println("virtual user " + person.mail() + " stopped: " + e);
                }
            }));
        }
        System.out.printf("%d virtual users (%s), ramp-up %ds, warm-up %ds, measured %ds, think %d-%dms%n", users,
                describe(assigned), rampup, warmup, duration, thinkMin, thinkMax);

        long end = (warmup + duration) * 1000L;
        long started = recorder.now();
        long nextProgress = 15_000;
        boolean measuring = false;
        while (recorder.now() - started < end) {
            Thread.sleep(1000);
            long elapsed = recorder.now() - started;
            if (!measuring && elapsed >= warmup * 1000L) {
                recorder.startMeasuring();
                measuring = true;
                System.out.println("-- warm-up over, measuring");
            }
            if (elapsed >= nextProgress) {
                nextProgress += 15_000;
                System.out.printf("[%4ds] users=%d (login failed %d) last 15s: %s | %s%n", elapsed / 1000,
                        signedIn.get(), loginFailed.get(), recorder.progress(recorder.now() - 15_000), sampler.last());
            }
        }
        recorder.stopMeasuring();
        running.set(false);
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
        }
        sampler.sample();
        sampler.stop();
        samplerThread.interrupt();
        sampler.write(out.resolve(name + "-metrics.csv"));
        List<String> metrics = sampler.summary(recorder.measureFrom(), recorder.now());
        Files.write(out.resolve(name + "-metrics-summary.csv"), metrics, StandardCharsets.UTF_8);
        System.out.println();
        System.out.print(Recorder.table(metrics));
        System.out.printf("signed in %d of %d (failed %d)%n", signedIn.get(), users, loginFailed.get());
    }

    /** The virtual users' accounts: the role shares, each kind's accounts in turn (one account may serve two). */
    static List<Person> assign(Data data, int users) {
        Map<String, Integer> given = new HashMap<>();
        Map<String, Integer> turn = new HashMap<>();
        List<Person> assigned = new ArrayList<>();
        for (int n = 1; n <= users; n++) {
            String kind = null;
            double worst = Double.MAX_VALUE;
            for (Map.Entry<String, Double> share : ROLE_SHARE.entrySet()) {
                // The kind furthest below its share so far
                double have = given.getOrDefault(share.getKey(), 0) - share.getValue() * n;
                if (have < worst) {
                    worst = have;
                    kind = share.getKey();
                }
            }
            given.merge(kind, 1, Integer::sum);
            List<Person> accounts = data.of(kind);
            int index = turn.merge(kind, 1, Integer::sum) - 1;
            assigned.add(accounts.get(index % accounts.size()));
        }
        return assigned;
    }

    private static String describe(List<Person> assigned) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        assigned.forEach(person -> counts.merge(person.kind(), 1, Integer::sum));
        return counts.toString();
    }

    static Session signedIn(Person person, String url, Recorder recorder, Duration timeout, ExecutorService executor,
                            String password) {
        Session session = new Session(person, url, recorder, timeout, executor);
        if (!session.login(password)) {
            throw new IllegalStateException("sign-in failed: " + person.mail());
        }
        return session;
    }

    private static byte[] uploadContent() {
        byte[] content = new byte[4 * 1024 * 1024];
        new Random(42).nextBytes(content);
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, content, 0, header.length);
        return content;
    }

    /** Each action once per role, every answer printed. */
    private static void smoke(Data data, String url, Recorder recorder, Duration timeout, ExecutorService executor,
                              String password) {
        Scenarios scenarios = new Scenarios(data, uploadContent());
        for (String kind : List.of("soqm", "edit", "editall", "ro", "roall", "kdn")) {
            Person person = data.of(kind).get(0);
            Session session = signedIn(person, url, recorder, timeout, executor, password);
            Random random = new Random(kind.hashCode());
            for (Object[] weight : Scenarios.WEIGHTS.get(Scenarios.roleOf(person))) {
                for (int n = 0; n < 3; n++) {
                    scenarios.run((Scenarios.Action) weight[0], session, random);
                }
            }
            String list = person.isKdn() ? "/kdn" : "/component/ALL";
            Session.Result page = session.page("smoke.list", list);
            System.out.printf("smoke %-8s %s: %d rows on page 1 (%s)%n", kind, list,
                    page.body() == null ? 0 : page.body().split("class=\"cc-row", -1).length - 1, person.mail());
        }
        recorder.stopMeasuring();
    }

    /** The SQL statements per request of each page and tab, per role (metric loadtest.sql.statements). */
    private static void sqlCount(Data data, String url, Recorder recorder, Duration timeout, ExecutorService executor,
                                 String password, Path csv) throws Exception {
        Session reader = signedIn(data.of("soqm").get(1), url, recorder, timeout, executor, password);
        List<String> lines = new ArrayList<>(List.of("role,request,uri,requests,sql_total,sql_per_request,sql_max"));
        Random random = new Random(3);
        for (String kind : List.of("soqm", "edit", "kdn")) {
            Person person = data.of(kind).get(0);
            Session session = signedIn(person, url, recorder, timeout, executor, password);
            Data.Control control = data.visible(person, random);
            Map<String, String[]> requests = new LinkedHashMap<>();
            if (!person.isKdn()) {
                requests.put("dashboard", new String[]{"/", "/"});
                requests.put("list ALL page 1", new String[]{"/component/ALL", "/component/{componentName}"});
                requests.put("list ALL page 5 sorted", new String[]{"/component/ALL?sort=owner&dir=desc&page=5",
                        "/component/{componentName}"});
                requests.put("list HR search", new String[]{"/component/HR?q=Central", "/component/{componentName}"});
            }
            if (!"edit".equals(kind)) {
                requests.put("list KDN", new String[]{"/kdn", "/kdn"});
            }
            requests.put("card page", new String[]{"/view-control/" + control.id, "/view-control/{id}"});
            requests.put("card assignment", new String[]{"/api/control-assignment?controlId=" + control.id,
                    "/api/control-assignment"});
            requests.put("card details", new String[]{"/api/control-details?controlId=" + control.id,
                    "/api/control-details"});
            requests.put("card attachments", new String[]{"/api/attachments/info/" + control.id,
                    "/api/attachments/info/{controlId}"});
            requests.put("card changelog", new String[]{"/api/controls/" + control.id + "/changelog",
                    "/api/controls/{id}/changelog"});
            requests.put("card permissions", new String[]{"/api/permissions/" + control.id,
                    "/api/permissions/{controlId}"});
            for (Map.Entry<String, String[]> request : requests.entrySet()) {
                String uri = request.getValue()[1];
                double[] before = sqlMetric(reader, uri);
                session.get("sql " + request.getKey(), request.getValue()[0], Session.ok2xx(), false);
                Thread.sleep(200);
                double[] after = sqlMetric(reader, uri);
                double count = after[0] - before[0];
                double total = after[1] - before[1];
                lines.add(String.format(Locale.ROOT, "%s,%s,%s,%.0f,%.0f,%.1f,%.0f", kind, request.getKey(), uri, count,
                        total, count == 0 ? 0 : total / count, after[2]));
            }
        }
        Files.write(csv, lines, StandardCharsets.UTF_8);
        System.out.print(Recorder.table(lines));
        System.out.println("Results: " + csv.toAbsolutePath());
    }

    private static double[] sqlMetric(Session reader, String uri) {
        Session.Result result = reader.getJson("metrics", "/actuator/metrics/loadtest.sql.statements?tag=method:GET&tag=uri:"
                + Session.encode(uri));
        double[] values = new double[3];
        if (result.ok() && result.body() != null) {
            String[] stats = {"COUNT", "TOTAL", "MAX"};
            for (int n = 0; n < 3; n++) {
                Matcher matcher = Pattern.compile("\"statistic\":\"" + stats[n] + "\",\"value\":([-0-9.Ee]+)")
                        .matcher(result.body());
                values[n] = matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
            }
        }
        return values;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                options.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
            } else {
                throw new IllegalArgumentException("Arguments are --name=value: " + arg);
            }
        }
        return options;
    }
}
