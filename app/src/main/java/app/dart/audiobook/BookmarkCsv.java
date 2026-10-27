package app.dart.audiobook;

/** RFC 4180 style field quoting for local bookmark exports. */
final class BookmarkCsv {
    private BookmarkCsv() { }

    static String row(String... cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            String value = cells[i] == null ? "" : cells[i];
            int first = 0;
            while (first < value.length() && Character.isWhitespace(value.charAt(first))) first++;
            if (first < value.length() && "=+-@".indexOf(value.charAt(first)) >= 0)
                value = "'" + value;
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return out.append("\r\n").toString();
    }
}
