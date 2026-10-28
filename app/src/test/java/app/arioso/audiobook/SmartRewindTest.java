package app.arioso.audiobook;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SmartRewindTest {
    @Test public void respectsPauseAndExplicitSeek() {
        assertEquals(15000, SmartRewind.position(15000, 15000, 1000, 29000));
        assertEquals(12000, SmartRewind.position(15000, 15000, 1000, 31000));
        assertEquals(10000, SmartRewind.position(15000, 15000, 1000, 301000));
        assertEquals(5000, SmartRewind.position(15000, 15000, 1000, 1801000));
        assertEquals(15000, SmartRewind.position(15000, 13000, 1000, 500000));
        assertEquals(0, SmartRewind.position(2000, 2000, 1000, 4000000));
    }
}
