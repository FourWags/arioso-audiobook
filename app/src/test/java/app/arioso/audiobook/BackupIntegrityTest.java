package app.arioso.audiobook;

import static org.junit.Assert.*;
import org.junit.Test;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

public final class BackupIntegrityTest {
    @Test public void checksumDetectsCatalogMutationAndBadManifest() {
        String books = "[{\"title\":\"Café 星\"}]";
        String hash = BackupIntegrity.catalogSha256(books);
        assertEquals(64, hash.length());
        assertTrue(BackupIntegrity.matches(hash, books));
        assertFalse(BackupIntegrity.matches(hash, books.replace("Café", "Cafe")));
        assertFalse(BackupIntegrity.matches("bad", books));
    }

    @Test public void backupTextRejectsMalformedUtf8() throws Exception {
        String title = "Café 星";
        assertEquals(title, BackupIntegrity.decodeUtf8Strict(title.getBytes(StandardCharsets.UTF_8)));
        try {
            BackupIntegrity.decodeUtf8Strict(new byte[]{(byte) 0xc3, 0x28});
            fail("Malformed UTF-8 must not be replaced during backup import");
        } catch (CharacterCodingException expected) { }
    }
}
