package app.dart.audiobook;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/** Bounded streaming fingerprint for an explicitly selected local file. */
final class FileFingerprint {
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private FileFingerprint() { }

    static String sha256(InputStream stream, BooleanSupplier canceled) throws IOException {
        if (stream == null) throw new IOException("File cannot be opened");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (canceled != null && canceled.getAsBoolean()) throw new IOException("Canceled");
                total += count;
                if (total > MAX_BYTES) throw new IOException("File exceeds fingerprint limit");
                digest.update(buffer, 0, count);
            }
            if (canceled != null && canceled.getAsBoolean()) throw new IOException("Canceled");
            byte[] hash = digest.digest();
            char[] hex = new char[hash.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                hex[i * 2] = digits[(hash[i] >> 4) & 15];
                hex[i * 2 + 1] = digits[hash[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }
}
