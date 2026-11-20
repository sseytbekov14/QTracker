package com.kpmg.qtracker.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The file types an attachment may have, in one place (2026-11-20, .msg and .zip added): the server's check, the page's
 * accept list and hint, the error text and the download's content type all come from here.
 * <p>
 * The extension decides, in any case: browsers send different content types for the same file (.zip as
 * application/zip, application/x-zip-compressed or application/octet-stream; .msg as application/vnd.ms-outlook or
 * application/octet-stream), so the declared type is not read. A .zip or .msg must also start with its own signature
 * (zip "PK", an Outlook message the OLE2 header D0 CF 11 E0 A1 B1 1A E1), so a program renamed to .zip is refused; the
 * other types keep their check by extension as before. Nothing is unpacked or opened on the server: a file is stored
 * and only ever sent back as a download (Content-Disposition: attachment).
 */
public final class AttachmentTypes {

    /** Extension (lower case, no dot) and the content type of its download. */
    private static final Map<String, String> TYPES = new java.util.LinkedHashMap<>();

    static {
        TYPES.put("pdf", "application/pdf");
        TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        TYPES.put("doc", "application/msword");
        TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        TYPES.put("xls", "application/vnd.ms-excel");
        TYPES.put("csv", "text/csv");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("msg", "application/vnd.ms-outlook");
        TYPES.put("zip", "application/zip");
    }

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    /** How many first bytes the signature check needs. */
    public static final int SIGNATURE_BYTES = OLE2.length;

    private AttachmentTypes() {
    }

    /** The allowed extensions, lower case, without the dot, in the order shown. */
    public static List<String> extensions() {
        return List.copyOf(TYPES.keySet());
    }

    /** "PDF, DOCX, ..., MSG, ZIP": the hint under the upload zone and the error text. */
    public static String label() {
        return TYPES.keySet().stream().map(e -> e.toUpperCase(Locale.ROOT)).collect(Collectors.joining(", "));
    }

    /** ".pdf,.docx,...": the file input's accept attribute and the page's own check. */
    public static String accept() {
        return TYPES.keySet().stream().map(e -> "." + e).collect(Collectors.joining(","));
    }

    /** The extension of a name, lower case, without the dot; "" without one. */
    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean allowedName(String name) {
        return TYPES.containsKey(extension(name));
    }

    /**
     * Whether the first bytes fit the type the name claims: a .zip starts with "PK" and a zip record mark (03 04, 05 06
     * or 07 08), a .msg with the OLE2 header; every other allowed type is taken by its extension.
     */
    public static boolean contentMatches(String name, byte[] head) {
        String extension = extension(name);
        byte[] bytes = head == null ? new byte[0] : head;
        return switch (extension) {
            case "zip" -> bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                    && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6) || (bytes[2] == 7 && bytes[3] == 8));
            case "msg" -> bytes.length >= OLE2.length && Arrays.equals(Arrays.copyOf(bytes, OLE2.length), OLE2);
            default -> true;
        };
    }

    /** The content type a download of this name is sent with; application/octet-stream for any other. */
    public static String mimeType(String name) {
        return TYPES.getOrDefault(extension(name), "application/octet-stream");
    }
}
