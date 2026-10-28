package app.arioso.audiobook;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PlaybackDisplayTest {
    @Test public void privateModeHidesBookAndAuthorFromBothSurfaces() {
        assertEquals("Arioso", PlaybackDisplay.title(true, "Private title"));
        assertEquals("", PlaybackDisplay.artist(true, "Private author"));
        assertEquals("Private playback", PlaybackDisplay.notificationText(true, "Private author"));
    }

    @Test public void normalModeShowsAvailableDetails() {
        assertEquals("Book", PlaybackDisplay.title(false, "Book"));
        assertEquals("Author", PlaybackDisplay.artist(false, "Author"));
        assertEquals("Author", PlaybackDisplay.notificationText(false, "Author"));
        assertEquals("Arioso", PlaybackDisplay.title(false, null));
        assertEquals("Audiobook", PlaybackDisplay.notificationText(false, null));
    }
}
