package app.dart.audiobook;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class ChapterNavigationTest {
    private final List<Mp4Chapters.Chapter> chapters = Arrays.asList(
            new Mp4Chapters.Chapter(0, "Start"), new Mp4Chapters.Chapter(60000, "Middle"),
            new Mp4Chapters.Chapter(120000, "End"));

    @Test public void previousRestartsCurrentOrMovesBack() {
        assertEquals(60000, ChapterNavigation.previous(chapters, 70000));
        assertEquals(0, ChapterNavigation.previous(chapters, 62000));
        assertEquals(0, ChapterNavigation.previous(chapters, 0));
    }

    @Test public void nextUsesChapterOrBookEnd() {
        assertEquals(60000, ChapterNavigation.next(chapters, 0, 180000));
        assertEquals(180000, ChapterNavigation.next(chapters, 150000, 180000));
    }

    @Test public void chapterCountStopsAtFinalBookEndAfterSeeking() {
        assertEquals(120000, ChapterNavigation.afterCount(chapters, 50000, 180000, 2));
        assertEquals(180000, ChapterNavigation.afterCount(chapters, 119000, 180000, 3));
        assertEquals(180000, ChapterNavigation.afterCount(chapters, 180000, 180000, 2));
    }

    @Test public void currentChapterTracksBoundaries() {
        assertEquals(-1, ChapterNavigation.currentIndex(chapters, -1));
        assertEquals(0, ChapterNavigation.currentIndex(chapters, 59999));
        assertEquals(1, ChapterNavigation.currentIndex(chapters, 60000));
        assertEquals(2, ChapterNavigation.currentIndex(chapters, 180000));
    }
}
