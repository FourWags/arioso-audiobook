package app.arioso.audiobook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines file chapters and saved manual markers without changing the audio file. */
final class ManualChapters {
    static final int MAX_MARKERS = 200;
    private ManualChapters() { }

    static List<Mp4Chapters.Chapter> merge(List<Mp4Chapters.Chapter> embedded,
                                            List<Mp4Chapters.Chapter> manual, int durationMs) {
        Map<Integer, Mp4Chapters.Chapter> byPosition = new LinkedHashMap<>();
        if (embedded != null) for (Mp4Chapters.Chapter chapter : embedded)
            if (chapter != null && chapter.positionMs >= 0
                    && (durationMs <= 0 || chapter.positionMs < durationMs)
                    && chapter.title != null && !chapter.title.trim().isEmpty())
                byPosition.put(chapter.positionMs, chapter);
        if (manual != null) for (int i = 0; i < Math.min(MAX_MARKERS, manual.size()); i++) {
            Mp4Chapters.Chapter chapter = manual.get(i);
            if (!valid(chapter, durationMs)) continue;
            byPosition.put(chapter.positionMs, chapter);
        }
        List<Mp4Chapters.Chapter> all = new ArrayList<>(byPosition.values());
        all.sort(Comparator.comparingInt(chapter -> chapter.positionMs));
        return all;
    }

    static boolean valid(Mp4Chapters.Chapter chapter, int durationMs) {
        return chapter != null && chapter.positionMs >= 0
                && (durationMs <= 0 || chapter.positionMs < durationMs)
                && chapter.title != null && !chapter.title.trim().isEmpty()
                && chapter.title.length() <= 80;
    }
}
