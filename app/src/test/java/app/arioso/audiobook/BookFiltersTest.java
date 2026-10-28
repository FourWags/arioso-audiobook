package app.arioso.audiobook;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BookFiltersTest {
    @Test public void findsWholeTagIgnoringCaseAndWhitespace() {
        assertTrue(BookFilters.hasTag("Favorites, Family ; Road Trips", " family "));
        assertTrue(BookFilters.hasTag("Favorites, Family", "favorites"));
        assertFalse(BookFilters.hasTag("Favorites, Family", "fav"));
        assertFalse(BookFilters.hasTag(null, "Favorites"));
    }
}
