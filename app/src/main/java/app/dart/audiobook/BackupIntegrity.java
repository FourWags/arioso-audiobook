package app.dart.audiobook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Optional catalog checksum for plain and encrypted metadata backups. */
final class BackupIntegrity {
    private BackupIntegrity() { }

    static String catalogSha256(String catalogJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(catalogJson.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = digits[(digest[i] >>> 4) & 15];
                hex[i * 2 + 1] = digits[digest[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static boolean matches(String expected, String catalogJson) {
        if (expected == null || !expected.matches("[0-9a-f]{64}")) return false;
        byte[] left = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] right = catalogSha256(catalogJson).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(left, right);
    }
}
