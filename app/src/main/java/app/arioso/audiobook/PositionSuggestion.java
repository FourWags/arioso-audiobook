package app.arioso.audiobook;

final class PositionSuggestion {
    private PositionSuggestion() { }

    static int target(double progress, long duration) {
        if (!Double.isFinite(progress) || progress < 0 || progress > 1
                || duration <= 0 || duration > Integer.MAX_VALUE)
            return -1;
        return (int) Math.round(progress * duration);
    }

    static boolean unchanged(long currentPosition, long currentDuration,
                             long expectedPosition, long expectedDuration) {
        return expectedPosition >= 0 && expectedDuration > 0
                && expectedPosition <= expectedDuration
                && currentPosition == expectedPosition && currentDuration == expectedDuration;
    }

    static boolean fresh(long now, long createdAt) {
        return createdAt > 0 && now >= createdAt && now - createdAt <= 300_000;
    }
}
