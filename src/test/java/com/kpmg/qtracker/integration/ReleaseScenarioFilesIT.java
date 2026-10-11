package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Release test pass, stage F: attachments - size and count limits, allowed and refused types, names of every kind,
 * uploads at the same time, the download answer, paths that try to leave the folder, delete and hide by who and
 * when, a file listed but gone from the disk.
 */
class ReleaseScenarioFilesIT extends ReleaseScenarioSupport {

    private static final long MB = 1024L * 1024L;
    private static final List<String> ALLOWED = List.of("pdf", "docx", "doc", "xlsx", "xls", "csv", "png", "jpg", "jpeg");

    // ---- limits and types ----

    @Test
    void size_tenMegabytesIsTaken_oneByteMoreIsRefused_withTheFileName() throws Exception {
        Setup s = setup("IN_PROGRESS");
        MvcResult ok = upload(s.facSession, s.control, file("exact.pdf", (int) (10 * MB)));
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        MvcResult tooBig = upload(s.facSession, s.control, file("bigger.pdf", (int) (10 * MB + 1)));
        assertThat(tooBig.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooBig)).contains("bigger.pdf").contains("10 MB");
        assertThat(listed(s.control)).contains("exact.pdf").doesNotContain("bigger.pdf");
    }

    @Test
    void count_fiftyFilesPerTabAreTaken_theFiftyFirstIsRefused() throws Exception {
        Setup s = setup("IN_PROGRESS");
        // The control has one file already
        MockMultipartHttpServletRequestBuilder request = multipart("/api/attachments/upload/{id}", s.control.getId());
        for (int i = 1; i <= 49; i++) {
            request.file(file("part-" + i + ".pdf", 10));
        }
        MvcResult fifty = call(s.facSession, request);
        assertThat(fifty.getResponse().getStatus()).as(body(fifty)).isEqualTo(200);
        assertThat(listed(s.control)).hasSize(50);
        MvcResult fiftyFirst = upload(s.facSession, s.control, file("part-51.pdf", 10));
        assertThat(fiftyFirst.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(fiftyFirst)).contains("Maximum 50 files");
        // The Documents tab counts on its own
        assertThat(call(s.facSession, multipart("/api/attachments/upload/{id}", s.control.getId())
                .file(new MockMultipartFile("attachmentDocuments", "doc-tab.pdf", "application/pdf", bytes(10))))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void types_nineExtensionsInAnyCase_areTaken_othersRefused_contentIsNotChecked() throws Exception {
        Setup s = setup("IN_PROGRESS");
        for (String extension : ALLOWED) {
            for (String name : List.of("lower." + extension, "UPPER." + extension.toUpperCase())) {
                assertThat(upload(s.facSession, s.control, file(name, 20)).getResponse().getStatus()).as(name).isEqualTo(200);
            }
        }
        for (String name : List.of("run.exe", "page.html", "image.svg", "script.js", "notes.txt", "pack.zip",
                "report.pdf.exe", "noextension", "trailingdot.pdf.", "macro.xlsm")) {
            MvcResult refused = upload(s.facSession, s.control, file(name, 20));
            assertThat(refused.getResponse().getStatus()).as(name).isEqualTo(400);
        }
        // A double extension ending in an allowed one, and HTML sent as a PDF: taken as files of that type
        assertThat(upload(s.facSession, s.control, file("invoice.exe.pdf", 20)).getResponse().getStatus()).isEqualTo(200);
        MockMultipartFile html = new MockMultipartFile("attachmentDetails", "looks-like.pdf", "text/html",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertThat(upload(s.facSession, s.control, html).getResponse().getStatus()).isEqualTo(200);
        MvcResult download = call(s.facSession, get("/api/attachments/download/{f}", "looks-like.pdf")
                .param("controlId", id(s.control)));
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void anEmptyFile_isNotAttached_andTheAnswerSaysWhat() throws Exception {
        Setup s = setup("IN_PROGRESS");
        MvcResult result = upload(s.facSession, s.control, new MockMultipartFile("attachmentDetails", "empty.pdf",
                "application/pdf", new byte[0]));
        assertThat(listed(s.control)).doesNotContain("empty.pdf");
        // Recorded for the report: the status and message of an upload that attached nothing
        assertThat(result.getResponse().getStatus()).isIn(200, 400);
        System.out.println("RELEASE-SCENARIO empty file: " + result.getResponse().getStatus() + " " + body(result));
    }

    // ---- names ----

    @Test
    void names_cyrillicSpacesEmojiLongAndRepeated() throws Exception {
        Setup s = setup("IN_PROGRESS");
        assertThat(upload(s.facSession, s.control, file("Отчёт за сентябрь.pdf", 20)).getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(s.facSession, s.control, file("plan 😀 v2.xlsx", 20)).getResponse().getStatus()).isEqualTo(200);
        List<String> names = listed(s.control);
        assertThat(names).contains("Отчёт_за_сентябрь.pdf");
        String emojiName = names.stream().filter(n -> n.startsWith("plan_")).findFirst().orElseThrow();
        assertThat(emojiName).endsWith(".xlsx").doesNotContain("😀");
        // Downloads under the stored name, the original name in the header
        MvcResult cyrillic = call(s.facSession, get("/api/attachments/download/{f}", "Отчёт_за_сентябрь.pdf")
                .param("controlId", id(s.control)));
        assertThat(cyrillic.getResponse().getStatus()).isEqualTo(200);
        assertThat(cyrillic.getResponse().getHeader("Content-Disposition")).contains("filename*=UTF-8''");

        // The same name again: refused, in one request or later, any case
        assertThat(upload(s.facSession, s.control, file("Отчёт за сентябрь.pdf", 20)).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(s.facSession, s.control, file("ОТЧЁТ ЗА СЕНТЯБРЬ.PDF", 20)).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(s.facSession, multipart("/api/attachments/upload/{id}", s.control.getId())
                .file(file("twice.pdf", 10)).file(file("twice.pdf", 10))).getResponse().getStatus()).isEqualTo(400);

        // A very long name: the disk refuses it and the answer is a 400 naming the server path (S3-19 in the report);
        // nothing is attached
        String longName = "a".repeat(300) + ".pdf";
        MvcResult longResult = upload(s.facSession, s.control, file(longName, 20));
        assertThat(longResult.getResponse().getStatus()).isIn(200, 400);
        if (longResult.getResponse().getStatus() == 400) {
            assertThat(listed(s.control)).noneMatch(n -> n.startsWith("aaaa"));
        }
        System.out.println("RELEASE-SCENARIO long name: " + longResult.getResponse().getStatus());
        String cyrillicLong = "отчёт".repeat(60) + ".pdf";
        MvcResult cyrillicLongResult = upload(s.facSession, s.control, file(cyrillicLong, 20));
        assertThat(cyrillicLongResult.getResponse().getStatus()).isIn(200, 400);
        System.out.println("RELEASE-SCENARIO long Cyrillic name: " + cyrillicLongResult.getResponse().getStatus());
    }

    @Test
    void twoUploadsAtTheSameTime_bothFilesAreListed() throws Exception {
        Setup s = setup("IN_PROGRESS");
        for (int round = 0; round < 5; round++) {
            String a = "parallel-a-" + round + ".pdf";
            String b = "parallel-b-" + round + ".pdf";
            List<Integer> answers = together(
                    () -> upload(s.facSession, s.control, file(a, 2000)).getResponse().getStatus(),
                    () -> upload(s.soqmSession, s.control, file(b, 2000)).getResponse().getStatus());
            assertThat(answers).containsOnly(200);
            assertThat(listed(s.control)).as("round %d", round).contains(a, b);
        }
    }

    // ---- download and paths ----

    @Test
    void pathsThatTryToLeaveTheFolder_neverAnswerAFile() throws Exception {
        Setup s = setup("IN_PROGRESS");
        Path secret = Path.of("target", "it-uploads-release", "secret-" + RUN + ".pdf");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "%PDF-1.4 secret");
        for (String name : List.of("../secret-" + RUN + ".pdf", "..%2Fsecret-" + RUN + ".pdf", "%2e%2e%2fsecret-" + RUN + ".pdf",
                "..\\secret-" + RUN + ".pdf", "....//secret-" + RUN + ".pdf", "C:\\Windows\\win.ini", "/etc/passwd",
                "..", ".", "%00evidence.pdf", "evidence.pdf%00.txt")) {
            MvcResult result = call(s.facSession, get("/api/attachments/download/" + name).param("controlId", id(s.control)));
            assertThat(result.getResponse().getStatus()).as(name).isIn(400, 404);
            assertThat(body(result)).as(name).doesNotContain("secret").doesNotContain("[fonts]");
            MvcResult removed = call(s.facSession, delete("/api/attachments/delete/{id}", s.control.getId())
                    .param("filename", name).param("type", "details"));
            assertThat(removed.getResponse().getStatus()).as("delete " + name).isIn(400, 403, 404);
        }
        assertThat(Files.exists(secret)).isTrue();
        assertThat(listed(s.control)).contains(s.control.getAttachmentDetailsPath());
    }

    @Test
    void aFileListedButGoneFromTheDisk_answers404_withoutDetails() throws Exception {
        Setup s = setup("IN_PROGRESS");
        MvcResult result = call(s.facSession, multipart("/api/attachments/upload/{id}", s.control.getId())
                .file(file("will-vanish.pdf", 50)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        try (var files = Files.walk(Path.of("target", "it-uploads-release"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().equals("will-vanish.pdf")).toList()) {
                Files.delete(path);
            }
        }
        MvcResult gone = call(s.facSession, get("/api/attachments/download/{f}", "will-vanish.pdf").param("controlId", id(s.control)));
        assertThat(gone.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(gone)).contains("FILE_NOT_FOUND").doesNotContain("target").doesNotContain("Exception");
    }

    // ---- delete and hide ----

    @Test
    void delete_byTheAuthorInTheSameStage_soqmAlways_othersNever_completedHidesForSoqm() throws Exception {
        Setup s = setup("IN_PROGRESS");
        assertThat(upload(s.facSession, s.control, file("by-fac.pdf", 20)).getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(s.facSession, s.control, file("by-fac-2.pdf", 20)).getResponse().getStatus()).isEqualTo(200);
        // Another participant, the Shared With reader: no
        assertThat(deleteFile(s.opSession, s.control, "by-fac.pdf")).isEqualTo(403);
        assertThat(deleteFile(s.readerSession, s.control, "by-fac.pdf")).isEqualTo(403);
        // The author in the same stage: yes
        assertThat(deleteFile(s.facSession, s.control, "by-fac.pdf")).isEqualTo(200);
        assertThat(listed(s.control)).doesNotContain("by-fac.pdf");
        // After the stage moved on: the author no longer, SoQM Team yes
        Control moved = reload(s.control);
        moved.setPerformanceStatus("REVIEW");
        controlRepository.save(moved);
        assertThat(deleteFile(s.facSession, s.control, "by-fac-2.pdf")).isEqualTo(403);
        assertThat(deleteFile(s.soqmSession, s.control, "by-fac-2.pdf")).isEqualTo(200);

        // Completed: SoQM Team hides with a reason (the file stays on the disk, no longer listed or downloadable)
        Setup done = setup("COMPLETED");
        String file = done.control.getAttachmentDetailsPath();
        assertThat(deleteFile(done.poSession, done.control, file)).isEqualTo(403);
        assertThat(call(done.soqmSession, delete("/api/attachments/delete/{id}", done.control.getId())
                .param("filename", file).param("type", "details")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(done.soqmSession, delete("/api/attachments/delete/{id}", done.control.getId())
                .param("filename", file).param("type", "details").param("editReason", "Wrong file"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(listed(done.control)).doesNotContain(file);
        assertThat(call(done.soqmSession, get("/api/attachments/download/{f}", file).param("controlId", id(done.control)))
                .getResponse().getStatus()).isEqualTo(404);
        try (var files = Files.walk(Path.of("target", "it-uploads-release"))) {
            assertThat(files.anyMatch(p -> p.toString().contains(done.control.getControlId().replace("/", "_"))
                    && p.getFileName().toString().equals(file))).as("kept on the disk").isTrue();
        }
    }

    // ---- helpers ----

    private record Setup(Control control, MockHttpSession soqmSession, MockHttpSession facSession,
                         MockHttpSession opSession, MockHttpSession poSession, MockHttpSession readerSession) {
    }

    private Setup setup(String status) throws Exception {
        User soqm = soqm("fl-soqm");
        User fac = myEdit("fl-fac");
        User op = myEdit("fl-op");
        User po = myEdit("fl-po");
        User reader = myRead("fl-reader");
        Control control = control("RS-FL", status, people().facilitator(fac).operator(op).soqm(soqm).owner(po).shared(reader));
        return new Setup(control, login(soqm), login(fac), login(op), login(po), login(reader));
    }

    private MvcResult upload(MockHttpSession session, Control control, MockMultipartFile file) throws Exception {
        return call(session, multipart("/api/attachments/upload/{id}", control.getId()).file(file));
    }

    private int deleteFile(MockHttpSession session, Control control, String name) throws Exception {
        return call(session, delete("/api/attachments/delete/{id}", control.getId())
                .param("filename", name).param("type", "details")).getResponse().getStatus();
    }

    private List<String> listed(Control control) {
        String list = reload(control).getAttachmentDetailsPath();
        return list == null ? List.of() : Arrays.stream(list.split(";")).map(String::trim).filter(n -> !n.isEmpty()).toList();
    }

    private static MockMultipartFile file(String name, int size) {
        return new MockMultipartFile("attachmentDetails", name, "application/octet-stream", bytes(size));
    }

    private static byte[] bytes(int size) {
        byte[] data = new byte[size];
        byte[] head = "%PDF-1.4 ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, data, 0, Math.min(head.length, size));
        return data;
    }

    private static String id(Control control) {
        return String.valueOf(control.getId());
    }

    private static List<Integer> together(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> answers = new ArrayList<>();
            for (Future<Integer> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
