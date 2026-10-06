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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Admin Panel template (admin-users.html): the users table only shows values, and the one user dialog has a
 * name for every field and its token for the requests it sends.
 */
class AdminUsersTemplateTest {

    private static final Path TEMPLATE = Path.of("src/main/resources/templates/admin-users.html");
    private static final Pattern FIELD = Pattern.compile("<(input|select|textarea)\\b[^>]*>");
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern LABEL_FOR = Pattern.compile("<label\\b[^>]*\\bfor=\"([^\"]+)\"");

    @Test
    void everyDialogFieldHasALabel() throws IOException {
        String html = read();
        String dialog = html.substring(html.indexOf("id=\"userModal\""), html.indexOf("<script src=\"/js/vendor/bootstrap"));
        List<String> labelled = new ArrayList<>();
        Matcher label = LABEL_FOR.matcher(html);
        while (label.find()) {
            labelled.add(label.group(1));
        }

        List<String> unnamed = new ArrayList<>();
        Matcher field = FIELD.matcher(dialog);
        while (field.find()) {
            String tag = field.group();
            Matcher id = ID.matcher(tag);
            boolean named = tag.contains("aria-label=") || tag.contains("aria-labelledby=")
                    || (id.find() && labelled.contains(id.group(1)));
            if (!named) {
                unnamed.add(tag);
            }
        }
        assertThat(unnamed).as("dialog fields without a label").isEmpty();
        assertThat(labelled).contains("userName", "userEmail", "userLevel", "userScope", "userStatus");
        assertThat(dialog).contains("aria-labelledby=\"userModalTitle\"", "<h2 class=\"modal-title\" id=\"userModalTitle\"");
    }

    @Test
    void labelsPointAtExistingFields() throws IOException {
        String html = read();
        List<String> missing = new ArrayList<>();
        Matcher label = LABEL_FOR.matcher(html);
        while (label.find()) {
            if (!html.contains("id=\"" + label.group(1) + "\"")) {
                missing.add(label.group(1));
            }
        }
        assertThat(missing).as("<label for> without a field").isEmpty();
    }

    @Test
    void tableHasNoInputs_andTheDialogLoadsTheCsrfTokenAndTheConfirmDialog() throws IOException {
        String html = read();
        String table = html.substring(html.indexOf("<table class=\"table users-table\">"), html.indexOf("</template>"));
        assertThat(table).doesNotContain("<input", "<select");

        String head = html.substring(0, html.indexOf("</head>"));
        assertThat(head).contains("~{fragments/csrf :: csrf}");
        assertThat(html).contains("<script src=\"/js/app-modal.js?v=", "showConfirmModal(", "hide.bs.modal");
    }

    private static String read() throws IOException {
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8);
    }
}
