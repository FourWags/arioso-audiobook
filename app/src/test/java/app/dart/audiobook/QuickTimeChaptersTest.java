package app.dart.audiobook;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.Test;

public final class QuickTimeChaptersTest {
    private static void number(ByteArrayOutputStream out, long value) {
        for (int shift = 24; shift >= 0; shift -= 8) out.write((int) (value >> shift) & 255);
    }
    private static byte[] payload(long... numbers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long value : numbers) number(out, value);
        return out.toByteArray();
    }
    private static byte[] box(String name, byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = 8;
        for (byte[] part : parts) size += part.length;
        number(out, size);
        out.write(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
    private static byte[] text(String value) {
        byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(valueBytes.length >> 8); out.write(valueBytes.length & 255);
        out.write(valueBytes, 0, valueBytes.length);
        return out.toByteArray();
    }
    private static byte[] fixture(boolean invalidOffset) throws IOException {
        byte[] first = text("Café 星");
        byte[] second = text("Second");
        byte[] media = box("mdat", first, second);
        byte[] audioHeader = box("tkhd", payload(0, 0, 0, 1));
        byte[] chapterHeader = box("tkhd", payload(0, 0, 0, 2));
        byte[] audioMedia = box("mdia", box("mdhd", payload(0, 0, 0, 1000)),
                box("hdlr", payload(0, 0), "soun".getBytes()));
        byte[] chapterTable = box("stbl",
                box("stsd", payload(0, 1, 8), "text".getBytes()),
                box("stts", payload(0, 1, 2, 1000)),
                box("stsc", payload(0, 1, 1, 1, 1)),
                box("stsz", payload(0, 0, 2, first.length, second.length)),
                box("stco", payload(0, 2, 8, invalidOffset ? 1000000 : 8 + first.length)));
        byte[] chapterMedia = box("mdia", box("mdhd", payload(0, 0, 0, 1000)),
                box("hdlr", payload(0, 0), "text".getBytes()),
                box("minf", chapterTable));
        byte[] movie = box("moov", box("trak", audioHeader,
                        box("tref", box("chap", payload(2))), audioMedia),
                box("trak", chapterHeader, chapterMedia));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(media); file.write(movie);
        return file.toByteArray();
    }
    private static List<Mp4Chapters.Chapter> read(byte[] data) throws Exception {
        Path temporary = Files.createTempFile("dart-quicktime-", ".mp4");
        try {
            Files.write(temporary, data);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                return QuickTimeChapters.read(channel);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Test public void linkedTextTrackYieldsOrderedUnicodeChapters() throws Exception {
        List<Mp4Chapters.Chapter> found = read(fixture(false));
        assertEquals(2, found.size());
        assertEquals(0, found.get(0).positionMs);
        assertEquals("Café 星", found.get(0).title);
        assertEquals(1000, found.get(1).positionMs);
        assertEquals("Second", found.get(1).title);
    }
    @Test public void outOfFileSampleCannotYieldPartialChapters() throws Exception {
        assertTrue(read(fixture(true)).isEmpty());
    }
}
