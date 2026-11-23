package com.kpmg.qtracker.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rich text of the ten long fields of a control (2026-11-23): bold, italic, underline, strike-through, lists,
 * headings, quotes, links and pictures, written in the page's editor (Quill) and stored as HTML in the same TEXT
 * columns. Every value is cleaned here before it is stored and again before it is shown: only the tags below are
 * kept, links only to http, https and mailto, pictures only those of the control itself
 * (/api/controls/{id}/rich-images/{name}, RichImageController) - no script, style, event attribute or outside
 * address survives. A text stored before (plain, with line breaks) is left as it is and shown as paragraphs.
 */
public final class RichText {

    /** A picture of a control, as the editor writes it after the upload. */
    public static final Pattern IMAGE_SRC =
            Pattern.compile("^/api/controls/(\\d+)/rich-images/([a-f0-9]{32}\\.(?:png|jpg|gif))$");

    private static final Pattern ALLOWED_CLASS = Pattern.compile("ql-indent-[1-8]|ql-align-(?:center|right|justify)");

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "b", "em", "i", "u", "s", "strike", "ol", "ul", "li", "a", "h1", "h2", "h3",
                    "blockquote", "pre", "code", "span", "img")
            .addAttributes("a", "href")
            .addAttributes("img", "src", "alt")
            .addAttributes("li", "data-list", "class")
            .addAttributes("p", "class")
            .addAttributes("h1", "class")
            .addAttributes("h2", "class")
            .addAttributes("h3", "class")
            .addAttributes("span", "class")
            // links only to these; a picture's address has no protocol list here and is checked by IMAGE_SRC
            .addProtocols("a", "href", "http", "https", "mailto");

    private RichText() {
    }

    /**
     * The editor's HTML always starts with one of its blocks (a paragraph, heading, list, quote or code block); any
     * other value - text stored before 2026-11-23, or typed through the API, even one starting with "<b>" - is plain
     * text and is kept and shown as typed.
     */
    private static final Pattern EDITOR_HTML = Pattern.compile("^\s*<(?:p|h[1-3]|ol|ul|blockquote|pre)[\s>]",
            Pattern.CASE_INSENSITIVE);

    /** Whether a stored value is the editor's HTML rather than plain text. */
    public static boolean isHtml(String value) {
        return value != null && EDITOR_HTML.matcher(value).find();
    }

    /**
     * The value as it may be stored: HTML cleaned (see the class), an editor's empty text ("&lt;p&gt;&lt;br&gt;&lt;/p&gt;")
     * as "", plain text as it is; null stays null.
     */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        if (!isHtml(value)) {
            return value;
        }
        Document dirty = Jsoup.parseBodyFragment(value);
        // Pictures first, while their address is still there: only the control's own, the rest goes
        for (Element img : dirty.select("img")) {
            if (!IMAGE_SRC.matcher(img.attr("src").trim()).matches()) {
                img.remove();
            }
        }
        Document cleaned = new Cleaner(SAFELIST).clean(dirty);
        for (Element img : cleaned.select("img")) {
            if (!IMAGE_SRC.matcher(img.attr("src").trim()).matches()) {
                img.remove();
            }
        }
        for (Element element : cleaned.select("[class]")) {
            List<String> kept = new ArrayList<>();
            for (String name : element.classNames()) {
                if (ALLOWED_CLASS.matcher(name).matches()) {
                    kept.add(name);
                }
            }
            if (kept.isEmpty()) {
                element.removeAttr("class");
            } else {
                element.attr("class", String.join(" ", kept));
            }
        }
        // The editor's own markers (empty spans) carry nothing: their text, if any, stays
        for (Element span : cleaned.select("span:not([class])")) {
            span.unwrap();
        }
        for (Element link : cleaned.select("a[href]")) {
            link.attr("target", "_blank").attr("rel", "noopener noreferrer");
        }
        cleaned.outputSettings().prettyPrint(false);
        if (cleaned.body().text().isBlank() && cleaned.select("img").isEmpty()) {
            return "";
        }
        return cleaned.body().html();
    }

    /** The value as HTML to show: HTML cleaned again; plain text escaped, a paragraph per line; null or blank: "". */
    public static String toHtml(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (isHtml(value)) {
            return Objects.requireNonNullElse(clean(value), "");
        }
        StringBuilder html = new StringBuilder();
        for (String line : value.replace("\r\n", "\n").split("\n", -1)) {
            html.append("<p>").append(line.isEmpty() ? "<br>" : org.jsoup.nodes.Entities.escape(line)).append("</p>");
        }
        return html.toString();
    }

    /**
     * The value as plain text, for the places that show text only - Excel, e-mails, the Changelog, lists: one line per
     * paragraph or list item ("• " before a bullet), "[image]" for a picture; plain text as it is.
     */
    public static String plain(String value) {
        if (value == null || !isHtml(value)) {
            return value;
        }
        Document document = Jsoup.parseBodyFragment(value);
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    text.append(textNode.text());
                } else if (node instanceof Element element) {
                    switch (element.tagName()) {
                        case "p", "h1", "h2", "h3", "blockquote", "pre", "div" -> newLine(text);
                        case "li" -> {
                            newLine(text);
                            text.append("ordered".equals(element.attr("data-list")) ? "- " : "• ");
                        }
                        case "br" -> text.append('\n');
                        case "img" -> text.append("[image]");
                        default -> {
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
            }
        }, document.body());
        return text.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void newLine(StringBuilder text) {
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
    }

    /**
     * Whether two values say the same: the same HTML after cleaning, a plain text and its paragraphs counting as one
     * (an old plain text opened in the editor and saved untouched is no change).
     */
    public static boolean sameContent(String a, String b) {
        return normalized(a).equals(normalized(b));
    }

    private static String normalized(String value) {
        String html = toHtml(value);
        return html.replace("<p><br></p>", "").replaceAll("\\s+", " ").trim();
    }

    /** The names of the pictures of this control a value shows. */
    public static Set<String> imageNames(String value, long controlId) {
        Set<String> names = new LinkedHashSet<>();
        if (!isHtml(value)) {
            return names;
        }
        for (Element img : Jsoup.parseBodyFragment(value).select("img[src]")) {
            Matcher matcher = IMAGE_SRC.matcher(img.attr("src").trim());
            if (matcher.matches() && Long.parseLong(matcher.group(1)) == controlId) {
                names.add(matcher.group(2));
            }
        }
        return names;
    }

    /** The value with the pictures of one control pointed at another's (Create Control copies them). */
    public static String movePictures(String value, long fromControlId, long toControlId) {
        if (!isHtml(value)) {
            return value;
        }
        return value.replace("/api/controls/" + fromControlId + "/rich-images/", "/api/controls/" + toControlId + "/rich-images/");
    }
}
