package app.arioso.audiobook;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ManualChaptersTest {
    @Test public void manualMarkerOverridesSamePositionAndSortsWithEmbedded() {
        List<Mp4Chapters.Chapter> merged = ManualChapters.merge(
                Arrays.asList(new Mp4Chapters.Chapter(0, "Start"),
                        new Mp4Chapters.Chapter(60000, "Old label")),
                Arrays.asList(new Mp4Chapters.Chapter(90000, "New"),
                        new Mp4Chapters.Chapter(60000, "Corrected")), 120000);
        assertEquals(3, merged.size());
        assertEquals(60000, merged.get(1).positionMs);
        assertEquals("Corrected", merged.get(1).title);
        assertEquals("New", merged.get(2).title);
    }

    @Test public void invalidAndOutOfRangeMarkersAreIgnored() {
        List<Mp4Chapters.Chapter> merged = ManualChapters.merge(null, Arrays.asList(
                new Mp4Chapters.Chapter(-1, "Before"),
                new Mp4Chapters.Chapter(30000, "Valid"),
                new Mp4Chapters.Chapter(40000, "At end"),
                new Mp4Chapters.Chapter(5, " ")), 40000);
        assertEquals(1, merged.size());
        assertEquals("Valid", merged.get(0).title);
        assertTrue(ManualChapters.merge(null, Collections.emptyList(), 0).isEmpty());
    }

    @Test public void embeddedLongTitleIsKeptWhileManualTitleIsBounded() {
        String longTitle = String.join("", Collections.nCopies(100, "x"));
        List<Mp4Chapters.Chapter> merged = ManualChapters.merge(
                Collections.singletonList(new Mp4Chapters.Chapter(1000, longTitle)),
                Collections.singletonList(new Mp4Chapters.Chapter(2000, longTitle)), 3000);
        assertEquals(1, merged.size());
        assertEquals(longTitle, merged.get(0).title);
    }
}
