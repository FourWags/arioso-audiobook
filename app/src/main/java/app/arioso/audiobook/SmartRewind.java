package app.arioso.audiobook;

/** Conservative, bounded context rewind after an uninterrupted pause. */
final class SmartRewind {
    private SmartRewind() { }

    static int position(int current, int pausedPosition, long pausedAt, long now) {
        if (current < 0 || current != pausedPosition || pausedAt <= 0 || now <= pausedAt)
            return current;
        long gap = now - pausedAt;
        if (gap < 30000L) return current;
        int back = gap < 300000L ? 3000 : gap < 1800000L ? 5000 : 10000;
        return Math.max(0, current - back);
    }
}
