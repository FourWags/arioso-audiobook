package app.dart.audiobook;

import static org.junit.Assert.*;
import org.junit.Test;

public final class BackupIntegrityTest {
    @Test public void checksumDetectsCatalogMutationAndBadManifest() {
        String books = "[{\"title\":\"Café 星\"}]";
        String hash = BackupIntegrity.catalogSha256(books);
        assertEquals(64, hash.length());
        assertTrue(BackupIntegrity.matches(hash, books));
        assertFalse(BackupIntegrity.matches(hash, books.replace("Café", "Cafe")));
        assertFalse(BackupIntegrity.matches("bad", books));
    }
}
