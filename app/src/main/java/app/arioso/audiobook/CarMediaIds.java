package app.arioso.audiobook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable opaque identifiers for local media browser entries. */
final class CarMediaIds {
    private CarMediaIds() { }

    static String book(String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uri.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("book:");
            for (byte value : digest) {
                id.append(Character.forDigit((value >>> 4) & 15, 16));
                id.append(Character.forDigit(value & 15, 16));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
