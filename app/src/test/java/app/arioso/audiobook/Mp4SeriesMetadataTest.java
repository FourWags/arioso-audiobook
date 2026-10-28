package app.arioso.audiobook;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Test;

public final class Mp4SeriesMetadataTest {
    private static byte[] box(String type, byte[]... children) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = 8;
        for (byte[] child : children) length += child.length;
        for (int i = 3; i >= 0; i--) out.write(length >> (8 * i));
        out.write(type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        for (byte[] child : children) out.write(child);
        return out.toByteArray();
    }
    private static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static byte[] zeroes(int length) { return new byte[length]; }
    private static byte[] field(String name, String value) throws IOException {
        return box("----", box("name", zeroes(4), bytes(name)),
                box("data", zeroes(8), bytes(value)));
    }
    private static Mp4SeriesMetadata.Details read(byte[] data) throws Exception {
        Path temporary = Files.createTempFile("arioso-series-", ".m4b");
        try {
            Files.write(temporary, data);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                return Mp4SeriesMetadata.read(channel);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Test public void importsOnlyExplicitSeriesAndNumericPart() throws Exception {
        byte[] fixture = box("moov", box("udta", box("meta", zeroes(4), box("ilst",
                field("SERIES", "Orbit 星"), field("PART", "2.5"), field("SUBTITLE", "Ignored")))));
        Mp4SeriesMetadata.Details details = read(fixture);
        assertEquals("Orbit 星", details.series);
        assertEquals("2.5", details.number);
    }
    @Test public void rejectsInvalidPartAndOversizedSeries() throws Exception {
        byte[] fixture = box("moov", box("udta", box("meta", zeroes(4), box("ilst",
                field("SERIES", "x".repeat(3000)), field("PART", "Book two")))));
        Mp4SeriesMetadata.Details details = read(fixture);
        assertNull(details.series);
        assertNull(details.number);
    }
    @Test public void importsSeriesPartUsedByPersonalLibrary() throws Exception {
        byte[] fixture = box("moov", box("udta", box("meta", zeroes(4), box("ilst",
                field("SERIES", "Test series"), field("series-part", "3.5")))));
        Mp4SeriesMetadata.Details details = read(fixture);
        assertEquals("Test series", details.series);
        assertEquals("3.5", details.number);
    }
}
