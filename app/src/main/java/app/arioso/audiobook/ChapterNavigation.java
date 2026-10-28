package app.arioso.audiobook;

import java.util.List;

final class ChapterNavigation {
    private ChapterNavigation() { }

    static int currentIndex(List<Mp4Chapters.Chapter> chapters, int positionMs) {
        int current = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).positionMs > positionMs) break;
            current = i;
        }
        return current;
    }

    static int previous(List<Mp4Chapters.Chapter> chapters, int positionMs) {
        int current = currentIndex(chapters, positionMs);
        if (current < 0) return 0;
        if (positionMs > chapters.get(current).positionMs + 5000) return chapters.get(current).positionMs;
        return current == 0 ? 0 : chapters.get(current - 1).positionMs;
    }

    static int next(List<Mp4Chapters.Chapter> chapters, int positionMs, int durationMs) {
        for (Mp4Chapters.Chapter chapter : chapters)
            if (chapter.positionMs > positionMs + 1000) return chapter.positionMs;
        return Math.max(0, durationMs);
    }

    static int afterCount(List<Mp4Chapters.Chapter> chapters, int positionMs,
                          int durationMs, int count) {
        int target = positionMs;
        for (int i = 0; i < count; i++) {
            int next = next(chapters, target, durationMs);
            if (next <= target) break;
            target = next;
        }
        return target;
    }
}
