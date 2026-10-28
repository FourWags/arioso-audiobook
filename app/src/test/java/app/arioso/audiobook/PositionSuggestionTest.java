package app.arioso.audiobook;

import org.junit.Test;
import static org.junit.Assert.*;

public class PositionSuggestionTest {
    @Test public void boundsAndRoundsOnlyValidSuggestions() {
        assertEquals(750, PositionSuggestion.target(0.75, 1000));
        assertEquals(-1, PositionSuggestion.target(Double.NaN, 1000));
        assertEquals(-1, PositionSuggestion.target(-0.1, 1000));
        assertEquals(-1, PositionSuggestion.target(1.1, 1000));
        assertEquals(-1, PositionSuggestion.target(0.5, 0));
        assertEquals(-1, PositionSuggestion.target(0.5, (long) Integer.MAX_VALUE + 1));
    }

    @Test public void staleDestinationRequiresNewReview() {
        assertTrue(PositionSuggestion.unchanged(20, 100, 20, 100));
        assertFalse(PositionSuggestion.unchanged(21, 100, 20, 100));
        assertFalse(PositionSuggestion.unchanged(20, 101, 20, 100));
        assertFalse(PositionSuggestion.unchanged(-1, 100, 20, 100));
        assertTrue(PositionSuggestion.fresh(600_000, 300_000));
        assertFalse(PositionSuggestion.fresh(600_001, 300_000));
        assertFalse(PositionSuggestion.fresh(100, 101));
    }
}
