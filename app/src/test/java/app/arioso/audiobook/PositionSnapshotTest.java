package app.arioso.audiobook;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PositionSnapshotTest {
    @Test public void handlesEndAndBoundaries() {
        assertEquals(0.5, PositionSnapshot.progress(500, 1000, false), 0.0001);
        assertEquals(1.0, PositionSnapshot.progress(0, 1000, true), 0.0);
        assertEquals(0.0, PositionSnapshot.progress(-10, 1000, false), 0.0);
        assertEquals(1.0, PositionSnapshot.progress(Integer.MAX_VALUE, 1000, false), 0.0);
        assertTrue(Double.isNaN(PositionSnapshot.progress(500, 0, false)));
    }
}
