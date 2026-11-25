package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Pictures in the rich-text fields (2026-11-23): uploaded by whoever may change the control, shown to whoever may read
 * it (KDN and Read Only too); only PNG, JPEG and GIF up to 5 MB; kept in a saved text; copied by Create Control;
 * gone with a deactivated control.
 */
class RichImagesIT extends ReleaseScenarioSupport {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    JdbcTemplate jdbc;

    private MvcResult upload(MockHttpSession session, Control control, String name, byte[] bytes) throws Exception {
        return call(session, multipart("/api/controls/{id}/rich-images", control.getId())
                .file(new MockMultipartFile("image", name, "image/png", bytes)));
    }

    @Test
    void whoMayUploadAndSee_onlyPictures_andTheTextKeepsThem() throws Exception {
        User soqm = soqm("ri-soqm");
        User fac = myEdit("ri-fac");
        User reader = myRead("ri-ro");
        User outsider = myEdit("ri-out");
        User kdnUser = kdn("ri-kdn");
        Control control = control("KDN-RI", "IN_PROGRESS", people().facilitator(fac).operator(myEdit("ri-op")).soqm(soqm)
                .owner(myEdit("ri-po")).shared(reader));
        MockHttpSession facS = login(fac);

        MvcResult up = upload(facS, control, "shot.png", PNG);
        assertThat(up.getResponse().getStatus()).as(body(up)).isEqualTo(200);
        String url = json(up).path("url").asText();
        assertThat(url).matches("/api/controls/" + control.getId() + "/rich-images/[a-f0-9]{32}\\.png");

        // Seen by whoever reads the control
        for (User user : List.of(soqm, fac, reader, kdnUser)) {
            MvcResult shown = call(login(user), get(url));
            assertThat(shown.getResponse().getStatus()).as(user.getMail()).isEqualTo(200);
            assertThat(shown.getResponse().getContentType()).isEqualTo("image/png");
            assertThat(shown.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        }
        assertThat(status(login(outsider), get(url))).isIn(403, 404);
        // Uploaded only by whoever may change it
        for (User user : List.of(reader, kdnUser, outsider)) {
            assertThat(upload(login(user), control, "x.png", PNG).getResponse().getStatus()).as(user.getMail()).isEqualTo(403);
        }
        // Only pictures, by their bytes; at most 5 MB; no other name is served
        assertThat(upload(facS, control, "evil.png", "MZ\u0090 not a picture".getBytes()).getResponse().getStatus()).isEqualTo(400);
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(upload(facS, control, "big.png", big).getResponse().getStatus()).isEqualTo(400);
        for (String name : List.of("..%2F..%2Fapplication.yml", "abc.png", "0123456789abcdef0123456789abcdef.exe")) {
            assertThat(status(facS, get("/api/controls/" + control.getId() + "/rich-images/" + name))).as(name).isIn(400, 404);
        }

        // The text keeps the picture, drops a script and an outside picture
        MvcResult saved = callJson(facS, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"controlStepsPerformed\":\"<p><strong>Done</strong> <img src=\\\"" + url + "\\\">"
                + "<img src=\\\"https://evil.test/x.png\\\"><script>alert(1)</script></p>\"}");
        assertThat(saved.getResponse().getStatus()).as(body(saved)).isEqualTo(200);
        String stored = jdbc.queryForObject("SELECT control_steps_performed FROM controls WHERE id = ?", String.class,
                control.getId());
        assertThat(stored).isEqualTo("<p><strong>Done</strong> <img src=\"" + url + "\"></p>");
    }

    @Test
    void createControl_copiesThePictures_deactivationRemovesThem() throws Exception {
        User soqm = soqm("ri2-soqm");
        MockHttpSession session = login(soqm);
        Control done = control("RI2", "COMPLETED", people().facilitator(myEdit("ri2-fac")).operator(myEdit("ri2-op"))
                .soqm(soqm).owner(myEdit("ri2-po")));
        String url = json(upload(session, done, "s.png", PNG)).path("url").asText();
        String name = url.substring(url.lastIndexOf('/') + 1);
        jdbc.update("UPDATE controls SET control_description = ?, references_to_control = ? WHERE id = ?",
                "<p>See <img src=\"" + url + "\"></p>", "<p>Ref <img src=\"" + url + "\"></p>", done.getId());

        MvcResult next = callJson(session, post("/api/controls/{id}/next-cycle", done.getId()),
                "{\"controlId\":\"" + done.getControlId() + "-NEXT\"}");
        assertThat(next.getResponse().getStatus()).as(body(next)).isEqualTo(200);
        long nextId = json(next).path("id").asLong();
        String copied = jdbc.queryForObject("SELECT control_description FROM controls WHERE id = ?", String.class, nextId);
        String nextUrl = "/api/controls/" + nextId + "/rich-images/" + name;
        assertThat(copied).isEqualTo("<p>See <img src=\"" + nextUrl + "\"></p>");
        // References to Control too (2026-11-25: it kept the source's address)
        assertThat(jdbc.queryForObject("SELECT references_to_control FROM controls WHERE id = ?", String.class, nextId))
                .isEqualTo("<p>Ref <img src=\"" + nextUrl + "\"></p>");
        assertThat(call(session, get(nextUrl)).getResponse().getContentAsByteArray()).isEqualTo(PNG);

        // Deactivating the new control takes its pictures; the source keeps its own
        assertThat(status(session, post("/api/controls/{id}/deactivate", nextId).contentType("application/json")
                .content("{\"confirmControlId\":\"" + done.getControlId() + "-NEXT\"}"))).isEqualTo(200);
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("target", "it-uploads-release",
                "rich-images_" + nextId, name))).isFalse();
        assertThat(call(session, get(url)).getResponse().getStatus()).isEqualTo(200);
    }
}
