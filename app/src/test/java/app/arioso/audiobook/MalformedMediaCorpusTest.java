package app.arioso.audiobook;

import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.junit.Test;

/** Deterministic malformed inputs exercise parser bounds without native decoding. */
public final class MalformedMediaCorpusTest {
    @Test public void boundedParsersSurviveRandomAndMutatedContainers() throws Exception {
        Random random = new Random(20261027L);
        byte[] valid = Files.readAllBytes(Path.of(getClass().getResource("/chapter-tone.m4a").toURI()));
        Path scratch = Files.createTempFile("arioso-malformed-", ".bin");
        try {
            for (int sample = 0; sample < 200; sample++) {
                byte[] data;
                if (sample < 120) {
                    data = new byte[random.nextInt(2048)];
                    random.nextBytes(data);
                } else {
                    data = valid.clone();
                    int changes = 1 + random.nextInt(12);
                    for (int i = 0; i < changes; i++) data[random.nextInt(data.length)] ^= 1 << random.nextInt(8);
                    if (sample % 3 == 0) data = java.util.Arrays.copyOf(data,
                            random.nextInt(Math.min(data.length, 4096)));
                }
                Files.write(scratch, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try (FileChannel channel = FileChannel.open(scratch, StandardOpenOption.READ)) {
                    try { assertTrue(Mp4Chapters.read(channel).size() <= 255); }
                    catch (IOException expectedForMalformedInput) { }
                    try { assertTrue(QuickTimeChapters.read(channel).size() <= 255); }
                    catch (IOException expectedForMalformedInput) { }
                    try {
                        byte[] art = EmbeddedArtwork.read(channel);
                        assertTrue(art == null || art.length <= EmbeddedArtwork.MAX_IMAGE_BYTES);
                    } catch (IOException expectedForMalformedInput) { }
                    try { Mp4SeriesMetadata.read(channel); }
                    catch (IOException expectedForMalformedInput) { }
                }
            }
        } finally { Files.deleteIfExists(scratch); }
    }
}
