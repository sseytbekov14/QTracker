package com.kpmg.qtracker.service;

import org.springframework.stereotype.Component;

/**
 * RichText for the templates (${@richText.html(...)}, ${@richText.plain(...)}): the cleaned HTML of a rich-text field
 * for th:utext - never the stored value itself - and its plain text where only text fits.
 */
@Component("richText")
public class RichTextView {

    public String html(String value) {
        return RichText.toHtml(value);
    }

    public String plain(String value) {
        return RichText.plain(value);
    }
}
