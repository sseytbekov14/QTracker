package com.kpmg.qtracker.loadtest.client;

import com.kpmg.qtracker.loadtest.client.Recorder.Outcome;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One signed-in person, as a browser: its own cookies (session, XSRF-TOKEN), the CSRF token of the last page in
 * the header of every change, no automatic redirects. Each request is recorded under its label.
 */
final class Session {

    private static final Pattern FORM_CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final Pattern META_CSRF = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");
    private static final Pattern META_HEADER = Pattern.compile("<meta name=\"_csrf_header\" content=\"([^\"]+)\"");

    /** The answer of a request: status, body (null when only counted), bytes read, Location. */
    record Result(int status, String body, long bytes, String location) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Which answers are wanted, which the scenario allows; anything else is an error. */
    static IntFunction<Outcome> ok2xx() {
        return status -> status >= 200 && status < 300 ? Outcome.OK : Outcome.ERROR;
    }

    static IntFunction<Outcome> okOrAllowed(int... allowed) {
        return status -> {
            if (status >= 200 && status < 300) {
                return Outcome.OK;
            }
            for (int code : allowed) {
                if (code == status) {
                    return Outcome.EXPECTED;
                }
            }
            return Outcome.ERROR;
        };
    }

    final Data.Person person;
    private final String base;
    private final HttpClient client;
    private final Recorder recorder;
    private final Duration timeout;
    private volatile String csrf;
    private volatile String csrfHeader = "X-XSRF-TOKEN";

    Session(Data.Person person, String base, Recorder recorder, Duration timeout, ExecutorService executor) {
        this.person = person;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.recorder = recorder;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .executor(executor)
                .build();
    }

    /** Signs in with the password form; true when the start page with its CSRF token was reached. */
    boolean login(String password) {
        Result page = send("login.page", request("/login").GET(), ok2xx(), false);
        Matcher token = page.body() == null ? null : FORM_CSRF.matcher(page.body());
        if (token == null || !token.find()) {
            return false;
        }
        Result signIn = send("login.submit", request("/login")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(Map.of("username", person.mail(), "password", password, "_csrf", token.group(1)))),
                status -> status == 302 ? Outcome.OK : Outcome.ERROR, false);
        if (signIn.status() != 302 || signIn.location() == null || signIn.location().contains("error")) {
            return false;
        }
        Result start = page("login.start", person.isKdn() ? "/kdn" : "/");
        return start.ok() && csrf != null;
    }

    /** A page (HTML): its CSRF token is kept for the changes that follow. */
    Result page(String label, String path) {
        Result result = send(label, request(path).GET().header("Accept", "text/html"), ok2xx(), false);
        if (result.body() != null) {
            Matcher token = META_CSRF.matcher(result.body());
            if (token.find()) {
                csrf = token.group(1);
            }
            Matcher header = META_HEADER.matcher(result.body());
            if (header.find()) {
                csrfHeader = header.group(1);
            }
        }
        return result;
    }

    Result getJson(String label, String path) {
        return send(label, request(path).GET().header("Accept", "application/json"), ok2xx(), false);
    }

    Result get(String label, String path, IntFunction<Outcome> expect, boolean countOnly) {
        return send(label, request(path).GET(), expect, countOnly);
    }

    Result postJson(String label, String path, String json, IntFunction<Outcome> expect) {
        return send(label, change(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), expect, false);
    }

    Result postForm(String label, String path, Map<String, String> fields, IntFunction<Outcome> expect) {
        return send(label, change(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(fields)), expect, false);
    }

    /** An upload as the Documentation tab sends it (multipart, field attachmentDocuments). */
    Result upload(String label, long controlId, String fileName, byte[] content, String editReason,
                  IntFunction<Outcome> expect) {
        String boundary = "----qtrackerLoadTest" + System.nanoTime();
        StringBuilder head = new StringBuilder();
        if (editReason != null) {
            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"editReason\"\r\n\r\n")
                    .append(editReason).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"attachmentDocuments\";"
                + " filename=\"").append(fileName).append("\"\r\nContent-Type: application/pdf\r\n\r\n");
        byte[] start = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return send(label, change("/api/attachments/upload/" + controlId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(start),
                        HttpRequest.BodyPublishers.ofByteArray(content), HttpRequest.BodyPublishers.ofByteArray(end))),
                expect, false);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout);
    }

    private HttpRequest.Builder change(String path) {
        HttpRequest.Builder builder = request(path);
        if (csrf != null) {
            builder.header(csrfHeader, csrf);
        }
        return builder;
    }

    private Result send(String label, HttpRequest.Builder builder, IntFunction<Outcome> expect, boolean countOnly) {
        long started = recorder.now();
        long t0 = System.nanoTime();
        try {
            Result result;
            if (countOnly) {
                HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                long bytes = 0;
                try (InputStream in = response.body()) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        bytes += n;
                    }
                }
                result = new Result(response.statusCode(), null, bytes,
                        response.headers().firstValue("Location").orElse(null));
            } else {
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                result = new Result(response.statusCode(), response.body(), response.body().length(),
                        response.headers().firstValue("Location").orElse(null));
            }
            long millis = (System.nanoTime() - t0) / 1_000_000;
            Outcome outcome = expect.apply(result.status());
            recorder.record(label, started, millis, result.status(), outcome,
                    outcome == Outcome.ERROR ? person.mail() + " " + builder.build().uri().getPath()
                            + (result.location() != null ? " -> " + result.location() : "") + " "
                            + (result.body() == null ? "" : result.body()) : null);
            return result;
        } catch (HttpTimeoutException e) {
            recorder.record(label, started, (System.nanoTime() - t0) / 1_000_000, Recorder.TIMEOUT, Outcome.ERROR,
                    person.mail() + " " + e.getMessage());
            return new Result(Recorder.TIMEOUT, null, 0, null);
        } catch (IOException e) {
            recorder.record(label, started, (System.nanoTime() - t0) / 1_000_000, Recorder.IO_ERROR, Outcome.ERROR,
                    person.mail() + " " + e);
            return new Result(Recorder.IO_ERROR, null, 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted"));
        }
    }

    static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        StringJoiner body = new StringJoiner("&");
        fields.forEach((key, value) -> body.add(encode(key) + "=" + encode(value)));
        return HttpRequest.BodyPublishers.ofString(body.toString());
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
