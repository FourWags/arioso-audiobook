package app.dart.audiobook;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.Test;

public class EncryptedBackupTest {
    @Test public void roundTripAndTamperDetection() throws Exception {
        byte[] original = "{\"books\":[{\"title\":\"Café ☕\"}]}".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = EncryptedBackup.encrypt(original, "long local password".toCharArray());
        assertTrue(EncryptedBackup.isEncrypted(encrypted));
        assertArrayEquals(original, EncryptedBackup.decrypt(encrypted, "long local password".toCharArray()));
        encrypted[encrypted.length - 1] ^= 1;
        try {
            EncryptedBackup.decrypt(encrypted, "long local password".toCharArray());
            fail("tampered backup accepted");
        } catch (GeneralSecurityException expected) { }
    }

    @Test public void rejectsWrongPasswordAndOversize() throws Exception {
        byte[] encrypted = EncryptedBackup.encrypt("{}".getBytes(StandardCharsets.UTF_8),
                "another long password".toCharArray());
        try { EncryptedBackup.decrypt(encrypted, "wrong password".toCharArray()); fail(); }
        catch (GeneralSecurityException expected) { }
        try { EncryptedBackup.encrypt(new byte[4 * 1024 * 1024 + 1], "long password".toCharArray()); fail(); }
        catch (GeneralSecurityException expected) { }
    }
}
