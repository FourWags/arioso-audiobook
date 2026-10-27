package app.dart.audiobook;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class FileFingerprintTest {
    @Test public void knownSha256AndSameSizeChanges() throws Exception {
        String abc = FileFingerprint.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), () -> false);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc);
        String abd = FileFingerprint.sha256(new ByteArrayInputStream("abd".getBytes(StandardCharsets.UTF_8)), () -> false);
        assertNotEquals(abc, abd);
    }

    @Test public void cancellationDoesNotReturnAHash() throws Exception {
        try {
            FileFingerprint.sha256(new ByteArrayInputStream(new byte[100]), () -> true);
            fail("canceled hash returned");
        } catch (IOException expected) { assertEquals("Canceled", expected.getMessage()); }
    }
}
