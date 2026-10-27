package app.dart.audiobook;

import static org.junit.Assert.*;
import org.junit.Test;

public final class BookmarkCsvTest {
    @Test public void quotesCommasQuotesAndLineBreaks() {
        assertEquals("\"a,b\",\"line\n\"\"quote\"\"\"\r\n",
                BookmarkCsv.row("a,b", "line\n\"quote\""));
    }

    @Test public void spreadsheetFormulaPrefixesStayText() {
        assertEquals("\"'=SUM(1,2)\",\"'  @unsafe\"\r\n",
                BookmarkCsv.row("=SUM(1,2)", "  @unsafe"));
    }
}
