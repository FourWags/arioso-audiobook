package app.dart.audiobook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SeriesNumberSuggestionTest {
    @Test public void recognizesExplicitSeriesAndPrequel() {
        SeriesNumberSuggestion.Suggestion numbered =
                SeriesNumberSuggestion.fromFilename("River Tales - 02 - North.m4b");
        assertEquals("River Tales", numbered.series);
        assertEquals("02", numbered.number);
        assertEquals("North", numbered.title);
        SeriesNumberSuggestion.Suggestion prequel =
                SeriesNumberSuggestion.fromFilename("River Tales #-1 - Before.m4a");
        assertEquals("-1", prequel.number);
    }

    @Test public void rejectsAmbiguousAndInvalidNames() {
        assertNull(SeriesNumberSuggestion.fromFilename("Book 2.m4b"));
        assertNull(SeriesNumberSuggestion.fromFilename("River Tales - 2 - .m4b"));
        assertNull(SeriesNumberSuggestion.fromFilename("River Tales - 2.333 - North.m4b"));
    }
}
