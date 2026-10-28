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
}
