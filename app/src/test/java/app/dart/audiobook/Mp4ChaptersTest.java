package app.dart.audiobook;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class Mp4ChaptersTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void parsesNestedVersionZeroChapters() throws Exception {
        byte[] data = box("moov", box("udta", box("chpl", payload(0,
                entry(0, "Introduction", 4), entry(100_000_000L, "Chapter 1", 4)))));
        List<Mp4Chapters.Chapter> chapters = read(data);
        assertEquals(2, chapters.size());
        assertEquals(0, chapters.get(0).positionMs);
        assertEquals("Introduction", chapters.get(0).title);
        assertEquals(10_000, chapters.get(1).positionMs);
    }

    @Test public void parsesVersionOneWithExtendedBox() throws Exception {
        byte[] chpl = extendedBox("chpl", payload(1, entry(200_000_000L, "Later", 8)));
        List<Mp4Chapters.Chapter> chapters = read(box("moov", box("udta", chpl)));
        assertEquals(1, chapters.size());
        assertEquals(20_000, chapters.get(0).positionMs);
        assertEquals("Later", chapters.get(0).title);
    }

    @Test public void rejectsTruncatedAndDescendingChapters() throws Exception {
        byte[] invalid = payload(0, entry(100_000L, "First", 4), entry(0, "Earlier", 4));
        assertTrue(read(box("moov", box("udta", box("chpl", invalid)))).isEmpty());
        byte[] truncated = box("moov", box("udta", box("chpl", payload(0, entry(0, "OK", 4)))));
        truncated[truncated.length - 3] = (byte) 250;
        assertTrue(read(truncated).isEmpty());
    }

    @Test public void ignoresUnrelatedAndOversizedBoxes() throws Exception {
        assertTrue(read(box("moov", box("udta", box("free", new byte[10])))).isEmpty());
        assertTrue(read(box("moov", box("udta", box("chpl", new byte[65537])))).isEmpty());
    }

    @Test public void readsPlayableSyntheticAacFixture() throws Exception {
        try (FileChannel channel = FileChannel.open(
                Paths.get(getClass().getResource("/chapter-tone.m4a").toURI()), StandardOpenOption.READ)) {
            List<Mp4Chapters.Chapter> chapters = Mp4Chapters.read(channel);
            assertEquals(3, chapters.size());
            assertEquals("Opening", chapters.get(0).title);
            assertEquals(2_000, chapters.get(1).positionMs);
            assertEquals("Finish", chapters.get(2).title);
        }
    }

    private List<Mp4Chapters.Chapter> read(byte[] bytes) throws Exception {
        File file = folder.newFile();
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            return Mp4Chapters.read(channel);
        }
    }

    private static byte[] box(String name, byte[] body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, body.length + 8, 4);
        out.write(name.getBytes("US-ASCII"));
        out.write(body);
        return out.toByteArray();
    }

    private static byte[] extendedBox(String name, byte[] body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, 1, 4);
        out.write(name.getBytes("US-ASCII"));
        put(out, body.length + 16, 8);
        out.write(body);
        return out.toByteArray();
    }

    private static byte[] payload(int version, byte[]... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, version, 1);
        put(out, 0, 3);
        if (version == 1) put(out, 0, 4);
        put(out, entries.length, 1);
        for (byte[] entry : entries) out.write(entry);
        return out.toByteArray();
    }

    private static byte[] entry(long ticks, String name, int width) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, ticks, width);
        byte[] title = name.getBytes("UTF-8");
        put(out, title.length, 1);
        out.write(title);
        return out.toByteArray();
    }

    private static void put(ByteArrayOutputStream out, long value, int width) {
        for (int i = width - 1; i >= 0; i--) out.write((int) (value >>> (i * 8)) & 255);
    }
}
