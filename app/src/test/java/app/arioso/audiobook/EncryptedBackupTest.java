package app.arioso.audiobook;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
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

    @Test public void importsPreviousEncryptedEnvelope() throws Exception {
        byte[] header = new byte[]{100, 97, 114, 116, 45, 101, 110, 99,
                114, 121, 112, 116, 101, 100, 45, 118, 49, 10};
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        Arrays.fill(salt, (byte) 3);
        Arrays.fill(nonce, (byte) 5);
        char[] password = "previous backup password".toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, 210000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        byte[] plain = "{\"books\":[]}".getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        byte[] encrypted = cipher.doFinal(plain);
        byte[] previous = new byte[header.length + salt.length + nonce.length + encrypted.length];
        int offset = 0;
        System.arraycopy(header, 0, previous, offset, header.length); offset += header.length;
        System.arraycopy(salt, 0, previous, offset, salt.length); offset += salt.length;
        System.arraycopy(nonce, 0, previous, offset, nonce.length); offset += nonce.length;
        System.arraycopy(encrypted, 0, previous, offset, encrypted.length);
        assertTrue(EncryptedBackup.isEncrypted(previous));
        assertArrayEquals(plain, EncryptedBackup.decrypt(previous, password));
        previous[0] ^= 1;
        assertFalse(EncryptedBackup.isEncrypted(previous));
    }
}
