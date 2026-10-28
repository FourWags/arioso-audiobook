package app.arioso.audiobook;

/** Small, platform-independent bounds used by listening controls. */
final class ListeningTools {
    static final int MAX_BOOKMARKS = 100;

    static int boundedPosition(long requested, int duration) {
        return (int) Math.max(0L, Math.min(requested, Math.max(0, duration)));
    }

    static int progressPercent(long position, long duration) {
        if (position <= 0 || duration <= 0) return 0;
        return (int) Math.min(99, (double) position * 100.0 / duration);
    }

    /** Seconds, mm:ss, or hh:mm:ss; returns -1 for invalid or overflowing input. */
    static long parseTimeMillis(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,3}(:[0-9]{1,2}){0,2}")) return -1;
        String[] parts = raw.split(":");
        long seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            int value = Integer.parseInt(parts[i]);
            if (i > 0 && value > 59) return -1;
            seconds = seconds * 60 + value;
        }
        long millis = seconds * 1000;
        return millis <= Integer.MAX_VALUE ? millis : -1;
    }

    static boolean validRepeatWindow(int start, int end, int duration) {
        return start >= 0 && end >= start + 2000 && duration > 0
                && end <= duration - 2000;
    }

    static long deadline(long nowElapsedMs, int minutes) {
        if (minutes < 1 || minutes > 120) throw new IllegalArgumentException("Invalid timer length");
        return nowElapsedMs + minutes * 60_000L;
    }

    static boolean expired(long nowElapsedMs, long deadlineElapsedMs) {
        return deadlineElapsedMs > 0 && nowElapsedMs >= deadlineElapsedMs;
    }

    private ListeningTools() { }
}
