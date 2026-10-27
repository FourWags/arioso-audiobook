package app.dart.audiobook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Local metadata backup envelope. Passwords and derived keys are never persisted. */
final class EncryptedBackup {
    private static final byte[] MAGIC = "dart-encrypted-v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PLAINTEXT = 4 * 1024 * 1024;
    private static final int ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptedBackup() { }

    static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        if (plain == null || plain.length > MAX_PLAINTEXT || password == null || password.length < 8)
            throw new GeneralSecurityException("Invalid backup size or password");
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] key = derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            byte[] ciphertext = cipher.doFinal(plain);
            byte[] result = new byte[MAGIC.length + salt.length + nonce.length + ciphertext.length];
            int offset = 0;
            System.arraycopy(MAGIC, 0, result, offset, MAGIC.length); offset += MAGIC.length;
            System.arraycopy(salt, 0, result, offset, salt.length); offset += salt.length;
            System.arraycopy(nonce, 0, result, offset, nonce.length); offset += nonce.length;
            System.arraycopy(ciphertext, 0, result, offset, ciphertext.length);
            Arrays.fill(ciphertext, (byte) 0);
            return result;
        } finally { Arrays.fill(key, (byte) 0); }
    }

    static byte[] decrypt(byte[] packed, char[] password) throws GeneralSecurityException {
        if (!isEncrypted(packed) || packed.length < MAGIC.length + 16 + 12 + 16
                || packed.length > MAX_PLAINTEXT + MAGIC.length + 16 + 12 + 16
                || password == null || password.length == 0)
            throw new GeneralSecurityException("Invalid encrypted backup");
        int offset = MAGIC.length;
        byte[] salt = Arrays.copyOfRange(packed, offset, offset + 16); offset += 16;
        byte[] nonce = Arrays.copyOfRange(packed, offset, offset + 12); offset += 12;
        byte[] key = derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            return cipher.doFinal(packed, offset, packed.length - offset);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
