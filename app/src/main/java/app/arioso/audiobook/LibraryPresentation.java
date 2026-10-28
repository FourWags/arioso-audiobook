package app.arioso.audiobook;

import java.util.Locale;

/** Private presentation preferences and stable section labels; book ordering remains in LibraryOrdering. */
final class LibraryPresentation {
    static final String SPLIT = "split";
    static final String GRID = "grid";
    static final String COMPACT = "compact";

    static String layout(String saved, boolean hasLegacyChoice, boolean legacyCompact) {
        if (SPLIT.equals(saved) || GRID.equals(saved) || COMPACT.equals(saved)) return saved;
        if (hasLegacyChoice) return legacyCompact ? COMPACT : GRID;
        return SPLIT;
    }

    static String authorInitial(String author) {
        if (author == null || author.trim().isEmpty()) return "#";
        String trimmed = author.trim();
        int first = trimmed.codePointAt(0);
        if (!Character.isLetter(first)) return "#";
        return new String(Character.toChars(Character.toUpperCase(first))).toUpperCase(Locale.ROOT);
    }

    static String bookSection(String author, String series) {
        return (author == null ? "" : author.toLowerCase(Locale.ROOT)) + "\u0000"
                + (series == null ? "" : series.toLowerCase(Locale.ROOT));
    }

    private LibraryPresentation() { }
}
