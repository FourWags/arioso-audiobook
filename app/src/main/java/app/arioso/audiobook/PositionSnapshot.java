package app.arioso.audiobook;

final class PositionSnapshot {
    private PositionSnapshot() { }

    static double progress(int positionMs, int durationMs, boolean finished) {
        if (durationMs <= 0) return Double.NaN;
        if (finished) return 1.0;
        return Math.max(0.0, Math.min(1.0, (double) positionMs / durationMs));
    }
}
