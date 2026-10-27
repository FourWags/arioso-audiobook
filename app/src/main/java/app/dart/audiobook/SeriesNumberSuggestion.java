package app.dart.audiobook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative series hints from explicitly numbered filenames. */
final class SeriesNumberSuggestion {
    private static final Pattern DASHED = Pattern.compile(
            "^(.{1,100}?)\\s+-\\s+(-?[0-9]{1,4}(?:\\.[0-9]{1,2})?)\\s+-\\s+(.{1,150})$");
    private static final Pattern HASHED = Pattern.compile(
            "^(.{1,100}?)\\s+#(-?[0-9]{1,4}(?:\\.[0-9]{1,2})?)\\s+-\\s+(.{1,150})$");

    private SeriesNumberSuggestion() { }

    static Suggestion fromFilename(String filename) {
        if (filename == null || filename.length() > 300) return null;
        String stem = filename.replaceFirst("(?i)\\.(m4b|m4a|mp4|mp3|ogg|opus|flac|wav)$", "").trim();
        Matcher match = DASHED.matcher(stem);
        if (!match.matches()) match = HASHED.matcher(stem);
        if (!match.matches() || LibraryOrdering.parseOrder(match.group(2)) == null) return null;
        String series = match.group(1).trim();
        String title = match.group(3).trim();
        if (series.isEmpty() || title.isEmpty()) return null;
        return new Suggestion(series, match.group(2), title);
    }

    static final class Suggestion {
        final String series;
        final String number;
        final String title;

        Suggestion(String series, String number, String title) {
            this.series = series;
            this.number = number;
            this.title = title;
        }
    }
}
