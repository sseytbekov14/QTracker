package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * .msg and .zip may be attached (2026-11-20), whatever content type the browser sends, in any case of the extension;
 * a program renamed to .zip or .msg is refused by its first bytes; the old types still work; an archive is only stored
 * and sent back as a download, never opened; the page shows the server's list.
 */
class AttachmentsMsgZipIT extends ReleaseScenarioSupport {

    private static final byte[] MSG = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0};

    @Test
    void msgAndZip_areTaken_aRenamedProgramIsRefused_theOldTypesStillWork() throws Exception {
        User fac = myEdit("mz-fac");
        User soqm = soqm("mz-soqm");
        Control control = control("HR-MZ", "IN_PROGRESS", people().facilitator(fac).operator(myEdit("mz-op")).soqm(soqm)
                .owner(myEdit("mz-po")));
        MockHttpSession session = login(fac);
        byte[] zip = zip();

        for (String[] file : new String[][] {{"pack.zip", "application/zip"}, {"Pack2.ZIP", "application/x-zip-compressed"},
                {"pack3.zip", "application/octet-stream"}}) {
            assertThat(upload(session, control, file[0], file[1], zip).getResponse().getStatus()).as(file[0]).isEqualTo(200);
        }
        for (String[] file : new String[][] {{"mail.msg", "application/vnd.ms-outlook"}, {"Mail2.MSG", "application/octet-stream"}}) {
            assertThat(upload(session, control, file[0], file[1], MSG).getResponse().getStatus()).as(file[0]).isEqualTo(200);
        }
        // A program under an allowed name: refused, nothing attached
        for (String name : List.of("setup.zip", "invoice.msg")) {
            MvcResult refused = upload(session, control, name, "application/zip", EXE);
            assertThat(refused.getResponse().getStatus()).as(name).isEqualTo(400);
            assertThat(body(refused)).contains("is not a valid");
        }
        assertThat(upload(session, control, "run.exe", "application/octet-stream", EXE).getResponse().getStatus()).isEqualTo(400);
        assertThat(body(upload(session, control, "run.exe", "application/octet-stream", EXE)))
                .contains("PDF, DOCX, DOC, XLSX, XLS, CSV, PNG, JPG, JPEG, MSG, ZIP");
        // The old types
        for (String name : List.of("a.pdf", "b.docx", "c.xlsx", "d.xls", "e.csv", "f.png", "g.jpg", "h.jpeg", "i.doc")) {
            assertThat(upload(session, control, name, "application/octet-stream", "%PDF-1.4 x".getBytes()).getResponse()
                    .getStatus()).as(name).isEqualTo(200);
        }
        String listed = reload(control).getAttachmentDetailsPath();
        assertThat(listed).contains("pack.zip", "Pack2.ZIP", "mail.msg", "Mail2.MSG").doesNotContain("setup.zip", "invoice.msg", "run.exe");

        // Sent back only as a download, bytes as uploaded
        for (String[] file : new String[][] {{"pack.zip", "application/zip"}, {"mail.msg", "application/vnd.ms-outlook"}}) {
            MvcResult download = call(login(soqm), get("/api/attachments/download/{name}", file[0])
                    .param("controlId", String.valueOf(control.getId())));
            assertThat(download.getResponse().getStatus()).isEqualTo(200);
            assertThat(download.getResponse().getContentType()).isEqualTo(file[1]);
            assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
            assertThat(download.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }
        assertThat(call(session, get("/api/attachments/download/{name}", "pack.zip").param("controlId",
                String.valueOf(control.getId()))).getResponse().getContentAsByteArray()).isEqualTo(zip);

        // The page offers the server's list
        String page = body(call(session, get("/view-control/{id}", control.getId())));
        assertThat(page).contains("accept=\".pdf,.docx,.doc,.xlsx,.xls,.csv,.png,.jpg,.jpeg,.msg,.zip\"",
                ">PDF, DOCX, DOC, XLSX, XLS, CSV, PNG, JPG, JPEG, MSG, ZIP<");
    }

    private MvcResult upload(MockHttpSession session, Control control, String name, String type, byte[] content)
            throws Exception {
        return call(session, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", name, type, content)));
    }

    private static byte[] zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../../evil.txt"));
            zip.write("never unpacked on the server".getBytes());
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
