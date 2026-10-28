package app.arioso.audiobook;

import static org.junit.Assert.*;
import org.junit.Test;

public final class BackupFormatTest {
    @Test public void acceptsCurrentAndPreviousExportsOnly() {
        assertTrue(BackupFormat.supports("arioso-library-v1"));
        String previous = new String(new char[]{
                100, 97, 114, 116, 45, 108, 105, 98, 114, 97, 114, 121,
                45, 118, 49});
        assertTrue(BackupFormat.supports(previous));
        assertTrue(BackupFormat.isPrevious(previous));
        assertFalse(BackupFormat.isPrevious(BackupFormat.CURRENT));
        assertFalse(BackupFormat.supports("another-library-v1"));
        assertFalse(BackupFormat.supports(""));
        assertFalse(BackupFormat.supports(null));
    }
}
