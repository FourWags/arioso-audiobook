package app.arioso.audiobook;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LibraryOrderingTest {
    private static final class Book {
        final String author, series, number, title, uri;
        Book(String author, String series, String number, String title, String uri) {
            this.author = author; this.series = series; this.number = number;
            this.title = title; this.uri = uri;
        }
    }

    @Test public void authorViewKeepsEachSeriesInNumericOrder() {
        List<Book> books = new ArrayList<>(Arrays.asList(
                new Book("Ada", "Orbit", "10", "Ten", "10"),
                new Book("Zed", "Orbit", "1", "Other author", "z"),
                new Book("Ada", "Orbit", "", "Unnumbered", "u"),
                new Book("Ada", "Orbit", "2", "Café 星", "2"),
                new Book("Ada", "Another", "1", "Other series", "a"),
                new Book("Ada", "Orbit", "1", "One", "1")));
        books.sort((a, b) -> LibraryOrdering.compare(a.author, a.series, a.number,
                a.title, a.uri, b.author, b.series, b.number, b.title, b.uri));
        assertEquals(Arrays.asList("Other series", "One", "Café 星", "Ten",
                "Unnumbered", "Other author"), Arrays.asList(books.get(0).title,
                books.get(1).title, books.get(2).title, books.get(3).title,
                books.get(4).title, books.get(5).title));
    }

    @Test public void invalidOrdersFollowValidNumbersAndTieByUri() {
        assertTrue(LibraryOrdering.compare("Ada", "Orbit", "2", "Same", "b",
                "Ada", "Orbit", "bad", "Same", "a") < 0);
        assertTrue(LibraryOrdering.compare("Ada", "Orbit", "1", "Same", "a",
                "Ada", "Orbit", "1.00", "Same", "b") < 0);
        assertNull(LibraryOrdering.parseOrder("1e50"));
        assertEquals(0, LibraryOrdering.parseOrder("-1").compareTo(new java.math.BigDecimal("-1")));
        assertTrue(LibraryOrdering.compare("Ada", "Orbit", "-1", "Prequel", "p",
                "Ada", "Orbit", "1", "First", "f") < 0);
    }

    @Test public void unnumberedVolumesSortNaturallyWithoutIntegerOverflow() {
        assertTrue(LibraryOrdering.compareNatural("Volume 2", "Volume 10") < 0);
        assertTrue(LibraryOrdering.compareNatural("Part 999999999999999999999", "Part 1000000000000000000000") < 0);
        assertTrue(LibraryOrdering.compareNatural("Café 9", "café 10") < 0);
        assertTrue(LibraryOrdering.compareNatural("Part 02", "Part 2") > 0);
    }

    @Test public void sameTitleAtDifferentSeriesPositionsStaysSeparate() {
        String first = LibraryOrdering.bookGroupKey("Ada", "Orbit", "Home", "", "1");
        String second = LibraryOrdering.bookGroupKey("Ada", "Orbit", "Home", "", "2");
        assertNotEquals(first, second);
        assertEquals(first, LibraryOrdering.bookGroupKey("ADA", "ORBIT", "home", "", "1.0"));
        assertEquals(first, LibraryOrdering.bookGroupKey("Ada", "Orbit", "Home", "", "01"));
        assertNotEquals(first, LibraryOrdering.bookGroupKey("Ada", "Orbit", "Home", "", ""));
        assertEquals(LibraryOrdering.bookGroupKey("Ada", "Orbit", "Part 1", "Volume A", "1"),
                LibraryOrdering.bookGroupKey("Ada", "Orbit", "Part 2", "Volume A", "2"));
    }
}
