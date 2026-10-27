package app.dart.audiobook;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EmbeddedArtworkTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void readsSmallMp4Cover() throws Exception {
        byte[] picture = {1, 2, 3, 4};
        byte[] data = box("data", concat(new byte[8], picture));
        byte[] file = box("moov", box("udta", box("meta",
                concat(new byte[4], box("ilst", box("covr", data))))));
        assertArrayEquals(picture, read(file));
    }

    @Test public void readsSmallMp3Apic() throws Exception {
        byte[] picture = {10, 20, 30};
        byte[] apic = concat(new byte[]{0, 'i', 'm', 'a', 'g', 'e', '/', 'p', 'n', 'g', 0, 3, 0}, picture);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write("APIC".getBytes("US-ASCII"));
        number(frame, apic.length);
        frame.write(new byte[2]);
        frame.write(apic);
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write("ID3".getBytes("US-ASCII"));
        tag.write(new byte[]{3, 0, 0});
        int length = frame.size();
        tag.write(new byte[]{(byte) (length >>> 21), (byte) (length >>> 14 & 127),
                (byte) (length >>> 7 & 127), (byte) (length & 127)});
        tag.write(frame.toByteArray());
        assertArrayEquals(picture, read(tag.toByteArray()));
    }

    @Test public void rejectsOversizedCoverWithoutAllocatingIt() throws Exception {
        byte[] data = box("data", new byte[EmbeddedArtwork.MAX_IMAGE_BYTES + 9]);
        byte[] file = box("moov", box("meta",
                concat(new byte[4], box("ilst", box("covr", data)))));
        assertNull(read(file));
    }

    @Test public void rejectsTruncatedAndInvalidBoxes() throws Exception {
        assertNull(read(new byte[]{0, 0, 0, 100, 'm', 'o', 'o', 'v', 0, 0}));
        assertNull(read(new byte[]{'I', 'D', '3', 3, 0, 0, 127, 127, 127, 127}));
    }

    @Test public void readsGeneratedMp3WithRealId3Tag() throws Exception {
        try (FileChannel channel = FileChannel.open(
                Paths.get(getClass().getResource("/artwork-tone.mp3").toURI()), StandardOpenOption.READ)) {
            byte[] cover = EmbeddedArtwork.read(channel);
            assertNotNull(cover);
            assertTrue(cover.length > 100);
            assertTrue(cover.length <= EmbeddedArtwork.MAX_IMAGE_BYTES);
        }
    }

    private byte[] read(byte[] data) throws Exception {
        File file = folder.newFile();
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(data); }
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            return EmbeddedArtwork.read(channel);
        }
    }

    private static byte[] box(String type, byte[] body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        number(out, body.length + 8);
        out.write(type.getBytes("US-ASCII"));
        out.write(body);
        return out.toByteArray();
    }

    private static void number(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24 & 255);
        out.write(value >>> 16 & 255);
        out.write(value >>> 8 & 255);
        out.write(value & 255);
    }

    private static byte[] concat(byte[] first, byte[] second) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(first);
        out.write(second);
        return out.toByteArray();
    }
}
