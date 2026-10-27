package app.dart.audiobook;

final class BookFilters {
    private BookFilters() { }

    static boolean hasTag(String tags, String wanted) {
        if (tags == null || wanted == null || wanted.trim().isEmpty()) return false;
        for (String part : tags.split("[,;]")) {
            if (wanted.trim().equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }
}
