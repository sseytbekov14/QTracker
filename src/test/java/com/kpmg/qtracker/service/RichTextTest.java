package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rich text of the long fields: what is kept, what never survives, and the plain text of it. */
class RichTextTest {

    private static final String PICTURE = "/api/controls/7/rich-images/0123456789abcdef0123456789abcdef.png";

    @Test
    void formatting_isKept() {
        String html = "<p><strong>Bold</strong> <em>italic</em> <u>under</u> <s>strike</s></p>"
                + "<h2>Title</h2><ol><li data-list=\"bullet\">one</li><li data-list=\"ordered\">two</li></ol>"
                + "<blockquote>quote</blockquote><p class=\"ql-align-center ql-indent-2\">centred</p>";
        assertThat(RichText.clean(html)).isEqualTo(html);
        assertThat(RichText.clean("<p><a href=\"https://kpmg.com/x\">link</a></p>"))
                .isEqualTo("<p><a href=\"https://kpmg.com/x\" target=\"_blank\" rel=\"noopener noreferrer\">link</a></p>");
        assertThat(RichText.clean("<p>shot <img src=\"" + PICTURE + "\" alt=\"x\"></p>")).contains("<img src=\"" + PICTURE + "\"");
    }

    @Test
    void scripts_events_styles_andOutsidePictures_neverSurvive() {
        List<String> attacks = List.of(
                "<p>a<script>alert(1)</script></p>",
                "<p onclick=\"alert(1)\">a</p>",
                "<p><img src=\"x\" onerror=\"alert(1)\">a</p>",
                "<p><img src=\"https://evil.example/a.png\">a</p>",
                "<p><img src=\"data:image/png;base64,AAAA\">a</p>",
                "<p><img src=\"/api/controls/7/rich-images/../../../etc/passwd\">a</p>",
                "<p><a href=\"javascript:alert(1)\">a</a></p>",
                "<p style=\"background:url(javascript:alert(1))\">a</p>",
                "<p><iframe src=\"https://evil.example\"></iframe>a</p>",
                "<svg onload=alert(1)><p>a</p></svg>",
                "<p class=\"evil ql-indent-1\">a</p>",
                "<p><object data=\"x\"></object><embed src=\"x\">a</p>");
        for (String attack : attacks) {
            // What reaches a page (toHtml): editor HTML cleaned, any other text escaped - in both cases no element
            // that runs anything
            org.jsoup.nodes.Document shown = org.jsoup.Jsoup.parseBodyFragment(RichText.toHtml(attack));
            assertThat(shown.select("script, iframe, svg, object, embed, style")).as(attack).isEmpty();
            for (org.jsoup.nodes.Element element : shown.getAllElements()) {
                for (org.jsoup.nodes.Attribute attribute : element.attributes()) {
                    assertThat(attribute.getKey()).as(attack).doesNotStartWithIgnoringCase("on").isNotEqualToIgnoringCase("style");
                    assertThat(attribute.getValue()).as(attack).doesNotContainIgnoringCase("javascript")
                            .doesNotContain("evil").doesNotContain("data:").doesNotContain("passwd");
                }
            }
            assertThat(shown.text()).as(attack).contains("a");
        }
        // Editor HTML is cleaned when stored
        assertThat(RichText.clean("<p>a<script>alert(1)</script></p>")).isEqualTo("<p>a</p>");
        assertThat(RichText.clean("<p onclick=\"alert(1)\">a</p>")).isEqualTo("<p>a</p>");
        // Text that is not the editor's stays as typed (and is escaped when shown)
        assertThat(RichText.clean("<svg onload=alert(1)><p>a</p></svg>")).isEqualTo("<svg onload=alert(1)><p>a</p></svg>");
        assertThat(RichText.clean("<p class=\"evil ql-indent-1\">a</p>")).isEqualTo("<p class=\"ql-indent-1\">a</p>");
    }

    @Test
    void anEmptyEditor_isEmpty_plainTextStaysAsItIs() {
        assertThat(RichText.clean("<p><br></p>")).isEmpty();
        assertThat(RichText.clean("<p> </p><p><br></p>")).isEmpty();
        assertThat(RichText.clean(null)).isNull();
        assertThat(RichText.clean("Old plain\ntext <b>as typed</b>")).isEqualTo("Old plain\ntext <b>as typed</b>");
        assertThat(RichText.clean("<p><img src=\"" + PICTURE + "\"></p>")).contains("img");
    }

    @Test
    void oldPlainText_isShownAsParagraphs_escaped() {
        assertThat(RichText.toHtml("Line 1\n\n<b>x</b> & y")).isEqualTo("<p>Line 1</p><p><br></p><p>&lt;b&gt;x&lt;/b&gt; &amp; y</p>");
        assertThat(RichText.toHtml(null)).isEmpty();
        assertThat(RichText.toHtml("<p>a<script>x</script></p>")).isEqualTo("<p>a</p>");
    }

    @Test
    void plainText_forExcelEmailsAndTheChangelog() {
        assertThat(RichText.plain("<p><strong>Bold</strong> text</p><ol><li data-list=\"bullet\">one</li>"
                + "<li data-list=\"ordered\">two</li></ol><p>shot <img src=\"" + PICTURE + "\"></p><p>a<br>b</p>"))
                .isEqualTo("Bold text\n• one\n- two\nshot [image]\na\nb");
        assertThat(RichText.plain("Old\nplain")).isEqualTo("Old\nplain");
        assertThat(RichText.plain(null)).isNull();
    }

    @Test
    void oldPlainText_andItsParagraphs_areTheSame() {
        assertThat(RichText.sameContent("Line 1\nLine 2", "<p>Line 1</p><p>Line 2</p>")).isTrue();
        assertThat(RichText.sameContent(null, "<p><br></p>")).isTrue();
        assertThat(RichText.sameContent("", null)).isTrue();
        assertThat(RichText.sameContent("Line 1", "<p><strong>Line 1</strong></p>")).isFalse();
        assertThat(RichText.sameContent("a", "b")).isFalse();
    }

    @Test
    void picturesOfOneControl_areFound_andMovedToAnother() {
        String html = "<p><img src=\"" + PICTURE + "\"><img src=\"/api/controls/8/rich-images/"
                + "ffffffffffffffffffffffffffffffff.jpg\"></p>";
        assertThat(RichText.imageNames(html, 7)).containsExactly("0123456789abcdef0123456789abcdef.png");
        assertThat(RichText.movePictures(html, 7, 9)).contains("/api/controls/9/rich-images/0123456789abcdef")
                .contains("/api/controls/8/rich-images/ffff");
    }
}
