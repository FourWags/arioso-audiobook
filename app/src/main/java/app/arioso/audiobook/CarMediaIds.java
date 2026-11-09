package app.arioso.audiobook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Stable opaque identifiers for local media browser entries. */
final class CarMediaIds {
    private CarMediaIds() { }

    static String book(String uri) {
        return digest("book", uri);
    }

    static String group(String type, String value) {
        if (!"author".equals(type) && !"series".equals(type))
            throw new IllegalArgumentException("Unsupported car group");
        return digest(type, value.toLowerCase(Locale.ROOT));
    }

    private static String digest(String type, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(type).append(':');
            for (byte octet : digest) {
                id.append(Character.forDigit((octet >>> 4) & 15, 16));
                id.append(Character.forDigit(octet & 15, 16));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
