package app.arioso.audiobook;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class CarMediaIdsTest {
    @Test public void stableOpaqueIdsDoNotExposeDocumentPaths() {
        String first = CarMediaIds.book("content://com.android.externalstorage.documents/document/primary%3ABooks%2FOne.m4b");
        assertEquals(first, CarMediaIds.book("content://com.android.externalstorage.documents/document/primary%3ABooks%2FOne.m4b"));
        assertNotEquals(first, CarMediaIds.book("content://com.android.externalstorage.documents/document/primary%3ABooks%2FTwo.m4b"));
        assertEquals(69, first.length());
        assertFalse(first.contains("Books"));
    }

    @Test public void groupIdsDoNotExposeAuthorsOrSeries() {
        String author = CarMediaIds.group("author", "Private Author");
        String series = CarMediaIds.group("series", "Private Author\u0000Secret Saga");
        assertEquals(author, CarMediaIds.group("author", "Private Author"));
        assertNotEquals(author, series);
        assertFalse(author.contains("Private"));
        assertFalse(series.contains("Secret"));
    }
}
