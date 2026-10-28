package app.arioso.audiobook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded reader for QuickTime text chapter tracks referenced by an audio track. */
final class QuickTimeChapters {
    private static final int MAX_BOXES = 10000;
    private static final int MAX_CHAPTERS = 1000;
    private static final int MAX_SAMPLE_BYTES = 4096;
    private static final class Box {
        final long from, end;
        Box(long from, long end) { this.from = from; this.end = end; }
    }
    private static final class Track {
        int id;
        boolean audio;
        boolean text;
        int chapterReference;
        long timescale;
        Box sampleTable;
    }
    private QuickTimeChapters() { }

    static List<Mp4Chapters.Chapter> read(SeekableByteChannel channel) throws IOException {
        long length = channel.size();
        int[] visits = {0};
        Box moov = child(channel, new Box(0, length), "moov", visits);
        if (moov == null) return Collections.emptyList();
        List<Track> tracks = new ArrayList<>();
        for (Box box : children(channel, moov, "trak", visits)) {
            Track track = track(channel, box, visits);
            if (track != null) tracks.add(track);
        }
        for (Track audio : tracks) if (audio.audio && audio.chapterReference > 0)
            for (Track chapter : tracks) if (chapter.id == audio.chapterReference
                    && chapter.text && chapter.sampleTable != null && chapter.timescale > 0)
                return samples(channel, chapter, visits, length);
        return Collections.emptyList();
    }

    private static Track track(SeekableByteChannel ch, Box box, int[] visits) throws IOException {
        Box tkhd = child(ch, box, "tkhd", visits);
        Box mdia = child(ch, box, "mdia", visits);
        if (tkhd == null || mdia == null) return null;
        Track track = new Track();
        int version = u8(ch, tkhd.from);
        long idAt = tkhd.from + (version == 1 ? 20 : 12);
        if ((version != 0 && version != 1) || idAt + 4 > tkhd.end) return null;
        track.id = (int) u32(ch, idAt);
        Box hdlr = child(ch, mdia, "hdlr", visits);
        Box mdhd = child(ch, mdia, "mdhd", visits);
        if (hdlr == null || mdhd == null || hdlr.from + 12 > hdlr.end) return null;
        String handler = ascii(ch, hdlr.from + 8);
        track.audio = "soun".equals(handler);
        track.text = "text".equals(handler);
        int mdVersion = u8(ch, mdhd.from);
        long scaleAt = mdhd.from + (mdVersion == 1 ? 20 : 12);
        if ((mdVersion != 0 && mdVersion != 1) || scaleAt + 4 > mdhd.end) return null;
        track.timescale = u32(ch, scaleAt);
        Box tref = child(ch, box, "tref", visits);
        Box chap = tref == null ? null : child(ch, tref, "chap", visits);
        if (chap != null && chap.from + 4 <= chap.end) track.chapterReference = (int) u32(ch, chap.from);
        Box minf = child(ch, mdia, "minf", visits);
        track.sampleTable = minf == null ? null : child(ch, minf, "stbl", visits);
        return track;
    }

    private static List<Mp4Chapters.Chapter> samples(SeekableByteChannel ch, Track track,
                                                        int[] visits, long length) throws IOException {
        Box stbl = track.sampleTable;
        Box stsd = child(ch, stbl, "stsd", visits);
        Box stts = child(ch, stbl, "stts", visits);
        Box stsc = child(ch, stbl, "stsc", visits);
        Box stsz = child(ch, stbl, "stsz", visits);
        Box stco = child(ch, stbl, "stco", visits);
        boolean wide = false;
        if (stco == null) { stco = child(ch, stbl, "co64", visits); wide = true; }
        if (stsd == null || stts == null || stsc == null || stsz == null || stco == null
                || stsd.from + 16 > stsd.end || stts.from + 8 > stts.end
                || stsc.from + 8 > stsc.end || stsz.from + 12 > stsz.end
                || stco.from + 8 > stco.end
                || !"text".equals(ascii(ch, stsd.from + 12)))
            return Collections.emptyList();
        long count = u32(ch, stsz.from + 8);
        if (count == 0 || count > MAX_CHAPTERS) return Collections.emptyList();
        int n = (int) count;
        long defaultSize = u32(ch, stsz.from + 4);
        if (defaultSize == 0 && stsz.from + 12 + 4L * n > stsz.end) return Collections.emptyList();
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            long size = defaultSize == 0 ? u32(ch, stsz.from + 12 + 4L * i) : defaultSize;
            if (size < 2 || size > MAX_SAMPLE_BYTES) return Collections.emptyList();
            sizes[i] = (int) size;
        }
        long runs = u32(ch, stts.from + 4);
        if (runs == 0 || runs > MAX_CHAPTERS || stts.from + 8 + runs * 8 > stts.end)
            return Collections.emptyList();
        long[] starts = new long[n];
        long ticks = 0;
        int sample = 0;
        for (int i = 0; i < runs; i++) {
            long amount = u32(ch, stts.from + 8 + i * 8L);
            long delta = u32(ch, stts.from + 12 + i * 8L);
            if (amount == 0 || amount > n - sample || delta > Long.MAX_VALUE / amount
                    || ticks > Long.MAX_VALUE - amount * delta) return Collections.emptyList();
            for (int j = 0; j < amount; j++) { starts[sample++] = ticks; ticks += delta; }
        }
        if (sample != n) return Collections.emptyList();
        long mappingCount = u32(ch, stsc.from + 4);
        if (mappingCount == 0 || mappingCount > MAX_CHAPTERS
                || stsc.from + 8 + mappingCount * 12 > stsc.end) return Collections.emptyList();
        long chunkCount = u32(ch, stco.from + 4);
        int width = wide ? 8 : 4;
        if (chunkCount == 0 || chunkCount > MAX_CHAPTERS
                || stco.from + 8 + chunkCount * width > stco.end) return Collections.emptyList();
        long[] firstChunks = new long[(int) mappingCount];
        long[] perChunk = new long[(int) mappingCount];
        for (int i = 0; i < mappingCount; i++) {
            long at = stsc.from + 8 + i * 12L;
            firstChunks[i] = u32(ch, at);
            perChunk[i] = u32(ch, at + 4);
            if (firstChunks[i] < 1 || firstChunks[i] > chunkCount || perChunk[i] < 1
                    || perChunk[i] > n || (i > 0 && firstChunks[i] <= firstChunks[i - 1]))
                return Collections.emptyList();
        }
        if (firstChunks[0] != 1) return Collections.emptyList();
        List<Mp4Chapters.Chapter> result = new ArrayList<>(n);
        sample = 0;
        int mapping = 0;
        for (int chunk = 1; chunk <= chunkCount; chunk++) {
            while (mapping + 1 < mappingCount && firstChunks[mapping + 1] <= chunk) mapping++;
            long offsetAt = stco.from + 8 + (chunk - 1L) * width;
            long offset = wide ? u64(ch, offsetAt) : u32(ch, offsetAt);
            for (int inChunk = 0; inChunk < perChunk[mapping]; inChunk++) {
                if (sample >= n || offset < 0 || offset > length - sizes[sample])
                    return Collections.emptyList();
                byte[] raw = bytes(ch, offset, sizes[sample]);
                int textSize = ((raw[0] & 255) << 8) | (raw[1] & 255);
                if (textSize > raw.length - 2) return Collections.emptyList();
                String title = new String(raw, 2, textSize, StandardCharsets.UTF_8)
                        .replace('\n', ' ').replace('\r', ' ').trim();
                if (title.codePointCount(0, title.length()) > 180)
                    title = title.substring(0, title.offsetByCodePoints(0, 180));
                long millis = starts[sample] > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE
                        : starts[sample] * 1000 / track.timescale;
                if (millis > Integer.MAX_VALUE || !result.isEmpty()
                        && millis <= result.get(result.size() - 1).positionMs)
                    return Collections.emptyList();
                result.add(new Mp4Chapters.Chapter((int) millis,
                        title.isEmpty() ? "Chapter " + (sample + 1) : title));
                offset += sizes[sample++];
            }
        }
        return sample == n ? result : Collections.emptyList();
    }

    private static Box child(SeekableByteChannel ch, Box parent, String target,
                             int[] visits) throws IOException {
        for (Box box : children(ch, parent, target, visits)) return box;
        return null;
    }

    private static List<Box> children(SeekableByteChannel ch, Box parent, String target,
                                      int[] visits) throws IOException {
        List<Box> found = new ArrayList<>();
        long cursor = parent.from;
        while (cursor <= parent.end - 8 && ++visits[0] <= MAX_BOXES) {
            byte[] header = bytes(ch, cursor, 8);
            long size = unsigned32(header, 0);
            String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
            int headerSize = 8;
            if (size == 1) {
                if (cursor > parent.end - 16) break;
                size = u64(ch, cursor + 8);
                headerSize = 16;
            } else if (size == 0) size = parent.end - cursor;
            if (size < headerSize || size > parent.end - cursor) break;
            if (target.equals(type)) found.add(new Box(cursor + headerSize, cursor + size));
            cursor += size;
        }
        return found;
    }

    private static byte[] bytes(SeekableByteChannel ch, long offset, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        ch.position(offset);
        int emptyReads = 0;
        while (buffer.hasRemaining()) {
            int read = ch.read(buffer);
            if (read < 0 || (read == 0 && ++emptyReads > 3)) throw new IOException("Truncated MP4");
            if (read > 0) emptyReads = 0;
        }
        return buffer.array();
    }
    private static int u8(SeekableByteChannel ch, long at) throws IOException { return bytes(ch, at, 1)[0] & 255; }
    private static long u32(SeekableByteChannel ch, long at) throws IOException {
        return unsigned32(bytes(ch, at, 4), 0);
    }
    private static long u64(SeekableByteChannel ch, long at) throws IOException {
        byte[] raw = bytes(ch, at, 8);
        if ((raw[0] & 128) != 0) return -1;
        return (unsigned32(raw, 0) << 32) | unsigned32(raw, 4);
    }
    private static String ascii(SeekableByteChannel ch, long at) throws IOException {
        return new String(bytes(ch, at, 4), StandardCharsets.US_ASCII);
    }
    private static long unsigned32(byte[] raw, int at) {
        return ((long) (raw[at] & 255) << 24) | ((long) (raw[at + 1] & 255) << 16)
                | ((long) (raw[at + 2] & 255) << 8) | (raw[at + 3] & 255);
    }
}
