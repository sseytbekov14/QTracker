package com.kpmg.qtracker.service.userimport;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The login of a person from the display name "Last, First": the first letter of the first given name and
 * the surname, lower-case Latin ("Smith, Mary Ann" → msmith). Spaces, hyphens, apostrophes and dots leave
 * the surname, diacritics are dropped and Cyrillic is transliterated. People with the same login get the
 * suffix 2, 3, … in the order of their display names (then their row), so a reordered file keeps them.
 */
public final class LoginNames {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    // Spaces, hyphens and dashes, apostrophes and dots
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-‐‑‒–—'`´‘’ʼ.]+");
    private static final Pattern LATIN = Pattern.compile("[a-z]+");
    private static final Map<Character, String> CYRILLIC = cyrillic();
    private static final Map<Character, String> LATIN_LETTERS = Map.of(
            'ß', "ss", 'æ', "ae", 'œ', "oe", 'ø', "o", 'ł', "l", 'đ', "d", 'ð', "d", 'þ', "th", 'ı', "i");

    private LoginNames() {
    }

    /** The login without a suffix, or why there is none. */
    public record Base(String login, String error) {

        static Base of(String login) {
            return new Base(login, null);
        }

        static Base refused(String error) {
            return new Base(null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** One person waiting for a login: the row of the file, the display name and its base login. */
    public record Candidate(int row, String displayName, String base) {
    }

    public static Base base(String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        int comma = name.indexOf(',');
        if (comma < 0) {
            return Base.refused("no comma, expected \"Last, First\"");
        }
        String surname = name.substring(0, comma).strip();
        if (surname.equals(".")) {
            return Base.refused("the surname is \".\"");
        }
        String firstNames = name.substring(comma + 1).strip();
        String firstName = firstNames.isEmpty() ? "" : firstNames.split("\\s+")[0];

        String latinSurname = latin(surname);
        if (latinSurname.isEmpty()) {
            return Base.refused("empty surname");
        }
        String latinFirstName = latin(firstName);
        if (latinFirstName.isEmpty()) {
            return Base.refused("empty first name");
        }
        String login = latinFirstName.charAt(0) + latinSurname;
        if (!LATIN.matcher(login).matches()) {
            return Base.refused("characters that have no Latin spelling: " + codePoints(login));
        }
        return Base.of(login);
    }

    /**
     * The logins of the people by row: the first of each base login (by display name, then row) keeps it,
     * the next ones get 2, 3, …
     */
    public static Map<Integer, String> assign(List<Candidate> candidates) {
        Map<String, List<Candidate>> byBase = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byBase.computeIfAbsent(candidate.base(), base -> new ArrayList<>()).add(candidate);
        }
        Map<Integer, String> logins = new LinkedHashMap<>();
        byBase.forEach((base, people) -> {
            people.sort(Comparator
                    .comparing((Candidate candidate) -> sortKey(candidate.displayName()))
                    .thenComparingInt(Candidate::row));
            for (int i = 0; i < people.size(); i++) {
                logins.put(people.get(i).row(), i == 0 ? base : base + (i + 1));
            }
        });
        return logins;
    }

    /** Lower case, Cyrillic transliterated, without diacritics and separators; anything else stays. */
    static String latin(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder spelled = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            String replacement = CYRILLIC.getOrDefault(c, LATIN_LETTERS.get(c));
            spelled.append(replacement != null ? replacement : String.valueOf(c));
        }
        String withoutMarks = MARKS.matcher(Normalizer.normalize(spelled, Normalizer.Form.NFD)).replaceAll("");
        return SEPARATORS.matcher(withoutMarks).replaceAll("");
    }

    static String sortKey(String displayName) {
        return displayName == null ? "" : displayName.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String codePoints(String login) {
        StringBuilder found = new StringBuilder();
        login.codePoints()
                .filter(cp -> cp < 'a' || cp > 'z')
                .distinct()
                .forEach(cp -> found.append(found.isEmpty() ? "" : " ").append(String.format("U+%04X", cp)));
        return found.toString();
    }

    private static Map<Character, String> cyrillic() {
        Map<Character, String> map = new LinkedHashMap<>();
        String[][] letters = {
                {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "e"}, {"ж", "zh"},
                {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"},
                {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "kh"}, {"ц", "ts"},
                {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"}, {"ь", ""}, {"э", "e"}, {"ю", "yu"},
                {"я", "ya"},
                // Kazakh
                {"ә", "a"}, {"ғ", "g"}, {"қ", "k"}, {"ң", "n"}, {"ө", "o"}, {"ұ", "u"}, {"ү", "u"}, {"һ", "h"},
                {"і", "i"},
                // Ukrainian
                {"є", "ye"}, {"ї", "yi"}, {"ґ", "g"}
        };
        for (String[] letter : letters) {
            map.put(letter[0].charAt(0), letter[1]);
        }
        return Map.copyOf(map);
    }
}
