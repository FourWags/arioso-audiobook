package app.arioso.audiobook;

/** New exports use the Arioso identifier; previous exports remain importable. */
final class BackupFormat {
    static final String CURRENT = "arioso-library-v1";
    private static final String PREVIOUS = new String(new char[]{
            100, 97, 114, 116, 45, 108, 105, 98, 114, 97, 114, 121,
            45, 118, 49});

    private BackupFormat() { }

    static boolean supports(String value) {
        return CURRENT.equals(value) || PREVIOUS.equals(value);
    }

    static boolean isPrevious(String value) {
        return PREVIOUS.equals(value);
    }
}
