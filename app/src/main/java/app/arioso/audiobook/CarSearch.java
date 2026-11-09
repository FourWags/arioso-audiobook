package app.arioso.audiobook;

import java.util.Locale;

/** Small, deterministic ranking for local audiobook voice and car-display search. */
final class CarSearch {
    private CarSearch() { }

    static int score(String query, String title, String author, String series) {
        String needle = normalize(query);
        if (needle.startsWith("play ")) needle = needle.substring(5).trim();
        if (needle.isEmpty()) return 0;
        int titleScore = field(needle, title, 100, 90, 80);
        int seriesScore = field(needle, series, 70, 60, 50);
        int authorScore = field(needle, author, 65, 55, 45);
        return Math.max(titleScore, Math.max(seriesScore, authorScore));
    }

    private static int field(String needle, String value, int exact, int prefix, int partial) {
        String haystack = normalize(value);
        if (haystack.isEmpty()) return 0;
        if (haystack.equals(needle)) return exact;
        if (haystack.startsWith(needle)) return prefix;
        return haystack.contains(needle) ? partial : 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
