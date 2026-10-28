package app.arioso.audiobook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class LibraryPresentationTest {
    @Test public void splitIsNewDefaultWhileExplicitOlderViewsRemain() {
        assertEquals(LibraryPresentation.SPLIT, LibraryPresentation.layout(null, false, false));
        assertEquals(LibraryPresentation.GRID, LibraryPresentation.layout(null, true, false));
        assertEquals(LibraryPresentation.COMPACT, LibraryPresentation.layout(null, true, true));
        assertEquals(LibraryPresentation.SPLIT, LibraryPresentation.layout("split", true, false));
        assertEquals(LibraryPresentation.GRID, LibraryPresentation.layout("grid", false, false));
    }

    @Test public void indexHandlesEmptyUnicodeAndSeriesWithSharedNames() {
        assertEquals("#", LibraryPresentation.authorInitial("  "));
        assertEquals("#", LibraryPresentation.authorInitial("42 stories"));
        assertEquals("M", LibraryPresentation.authorInitial(" mira vale"));
        assertEquals("É", LibraryPresentation.authorInitial("Émile"));
        assertEquals(LibraryPresentation.bookSection("MIRA", "Harbor"),
                LibraryPresentation.bookSection("mira", "HARBOR"));
        assertNotEquals(LibraryPresentation.bookSection("Mira", "Harbor"),
                LibraryPresentation.bookSection("Eli", "Harbor"));
    }
}
