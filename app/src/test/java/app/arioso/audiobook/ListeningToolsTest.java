package app.arioso.audiobook;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ListeningToolsTest {
    @Test public void seekBoundsHandleNegativeAndOversizedValues() {
        assertEquals(0, ListeningTools.boundedPosition(-5, 20_000));
        assertEquals(20_000, ListeningTools.boundedPosition(Long.MAX_VALUE, 20_000));
        assertEquals(0, ListeningTools.boundedPosition(500, -1));
    }

    @Test public void timerUsesElapsedTimeAndBoundary() {
        long deadline = ListeningTools.deadline(1234, 15);
        assertEquals(901234L, deadline);
        assertFalse(ListeningTools.expired(deadline - 1, deadline));
        assertTrue(ListeningTools.expired(deadline, deadline));
        assertFalse(ListeningTools.expired(deadline + 1, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void timerRejectsUnboundedLength() { ListeningTools.deadline(0, 121); }

    @Test public void progressHandlesUnknownAndBadPositions() {
        assertEquals(0, ListeningTools.progressPercent(50, 0));
        assertEquals(0, ListeningTools.progressPercent(-5, 100));
        assertEquals(50, ListeningTools.progressPercent(50, 100));
        assertEquals(99, ListeningTools.progressPercent(500, 100));
    }

    @Test public void typedSeekTimeRejectsMalformedAndOutOfRangeValues() {
        assertEquals(83_000L, ListeningTools.parseTimeMillis("1:23"));
        assertEquals(3_723_000L, ListeningTools.parseTimeMillis("1:02:03"));
        assertEquals(-1L, ListeningTools.parseTimeMillis("1:60"));
        assertEquals(-1L, ListeningTools.parseTimeMillis("2:3:4:5"));
        assertEquals(-1L, ListeningTools.parseTimeMillis("999:00:00"));
        assertEquals(-1L, ListeningTools.parseTimeMillis("-1"));
    }

    @Test public void repeatWindowKeepsDistanceFromFileEnd() {
        assertTrue(ListeningTools.validRepeatWindow(3000, 7000, 10000));
        assertFalse(ListeningTools.validRepeatWindow(3000, 4000, 10000));
        assertFalse(ListeningTools.validRepeatWindow(3000, 9000, 10000));
        assertFalse(ListeningTools.validRepeatWindow(-1, 7000, 10000));
    }
}
