package com.kpmg.qtracker.service.userimport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The people file as Excel or SharePoint save it: UTF-8 (with or without BOM) or UTF-16 with BOM, separated
 * by commas, semicolons or tabs (the one that gives the expected header wins), quotes as in RFC 4180. Each
 * record keeps the line it starts on, for the report. Another encoding is refused rather than guessed.
 */
public final class UsersCsv {

    public static final String NAME = "Name";
    public static final String USER = "User";
    public static final String ROLE = "Role";
    public static final String ACCESS = "Access";
    public static final String STATUS = "Status";
    /** Name is optional (only to recognise service accounts); the others are needed. */
    public static final List<String> REQUIRED = List.of(USER, ROLE, ACCESS, STATUS);

    private static final char[] DELIMITERS = {',', ';', '\t'};

    private UsersCsv() {
    }

    /** One record: the line it starts on and its values by column (missing ones are absent). */
    public record Record(int line, Map<String, String> values, int fields, String error) {

        public String get(String column) {
            String value = values.get(column);
            return value == null ? "" : value.strip();
        }
    }

    /** The file read: encoding and separator found, the records, or why it cannot be read. */
    public record Table(String encoding, String delimiter, List<String> header, List<Record> records, String error) {

        static Table refused(String encoding, String error) {
            return new Table(encoding, null, List.of(), List.of(), error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    public static Table read(byte[] bytes) {
        String encoding;
        String text;
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            encoding = "UTF-8 with BOM";
            text = decode(bytes, 3, StandardCharsets.UTF_8);
        } else if (startsWith(bytes, 0xFF, 0xFE)) {
            encoding = "UTF-16LE with BOM";
            text = decode(bytes, 2, StandardCharsets.UTF_16LE);
        } else if (startsWith(bytes, 0xFE, 0xFF)) {
            encoding = "UTF-16BE with BOM";
            text = decode(bytes, 2, StandardCharsets.UTF_16BE);
        } else {
            encoding = "UTF-8";
            text = decode(bytes, 0, StandardCharsets.UTF_8);
        }
        if (text == null) {
            return Table.refused(encoding, "the file is not " + encoding
                    + " text; save it as \"CSV UTF-8\" (Excel) and run again");
        }

        for (char delimiter : DELIMITERS) {
            List<Parsed> parsed = parse(text, delimiter);
            if (parsed.isEmpty()) {
                return Table.refused(encoding, "the file is empty");
            }
            List<String> header = parsed.get(0).fields().stream().map(String::strip).toList();
            Map<String, Integer> columns = columns(header);
            if (!columns.keySet().containsAll(REQUIRED)) {
                continue;
            }
            List<Record> records = new ArrayList<>();
            for (Parsed row : parsed.subList(1, parsed.size())) {
                if (row.fields().stream().allMatch(String::isBlank)) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                columns.forEach((column, index) -> {
                    if (index < row.fields().size()) {
                        values.put(column, row.fields().get(index));
                    }
                });
                String error = row.fields().size() == header.size() ? row.error()
                        : row.error() != null ? row.error()
                        : row.fields().size() + " fields where the header has " + header.size();
                records.add(new Record(row.line(), values, row.fields().size(), error));
            }
            return new Table(encoding, name(delimiter), header, records, null);
        }
        return Table.refused(encoding, "no header with the columns " + String.join(", ", REQUIRED)
                + " (separated by commas, semicolons or tabs)");
    }

    // Column name (as in REQUIRED, any case) -> index; Name is kept when present
    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        List<String> known = new ArrayList<>(REQUIRED);
        known.add(NAME);
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i).toLowerCase(Locale.ROOT);
            for (String column : known) {
                if (column.toLowerCase(Locale.ROOT).equals(cell)) {
                    columns.putIfAbsent(column, i);
                }
            }
        }
        return columns;
    }

    private record Parsed(int line, List<String> fields, String error) {
    }

    // RFC 4180: quoted fields may hold separators, doubled quotes and line breaks
    private static List<Parsed> parse(String text, char delimiter) {
        List<Parsed> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        String error = null;
        int line = 1;
        int startLine = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                i++;
                continue;
            }
            if (c == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r' || c == '\n') {
                fields.add(field.toString());
                rows.add(new Parsed(startLine, List.copyOf(fields), error));
                fields.clear();
                field.setLength(0);
                fieldStarted = false;
                error = null;
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                line++;
                startLine = line;
            } else {
                if (c == '"') {
                    error = "a quote inside an unquoted field";
                }
                field.append(c);
                fieldStarted = true;
            }
            i++;
        }
        if (quoted) {
            error = "a quoted field is not closed";
        }
        if (fieldStarted || !fields.isEmpty() || field.length() > 0) {
            fields.add(field.toString());
            rows.add(new Parsed(startLine, List.copyOf(fields), error));
        }
        return rows;
    }

    private static String decode(byte[] bytes, int offset, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String name(char delimiter) {
        return switch (delimiter) {
            case ',' -> "comma";
            case ';' -> "semicolon";
            default -> "tab";
        };
    }
}
