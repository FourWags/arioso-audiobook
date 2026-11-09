package app.arioso.audiobook;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CarSearchTest {
    @Test public void ranksExactTitleAboveAuthorAndSeries() {
        assertTrue(CarSearch.score("Blue Moon", "Blue Moon", "Ada", "")
                > CarSearch.score("Blue Moon", "Another", "Blue Moon", ""));
        assertTrue(CarSearch.score("Blue Moon", "Blue Moon Rising", "Ada", "")
                > CarSearch.score("Blue Moon", "Another", "Ada", "Blue Moon"));
    }

    @Test public void supportsVoiceWordingUnicodeAndWhitespace() {
        assertEquals(100, CarSearch.score("  PLAY   Café  Tales ", "Café Tales", "Ada", ""));
        assertTrue(CarSearch.score("Ada", "Book", "Ada Author", "") > 0);
        assertTrue(CarSearch.score("Chronicles", "Book", "Ada", "Chronicles") > 0);
        assertEquals(0, CarSearch.score("missing", "Book", "Ada", "Chronicles"));
        assertEquals(0, CarSearch.score("  ", "Book", "Ada", "Chronicles"));
    }
}
