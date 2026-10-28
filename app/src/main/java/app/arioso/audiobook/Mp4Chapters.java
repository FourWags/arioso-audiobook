package app.arioso.audiobook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded reader for Nero chpl markers in seekable MP4/M4B files. */
final class Mp4Chapters {
    static final class Chapter {
        final int positionMs;
        final String title;
        Chapter(int positionMs, String title) { this.positionMs = positionMs; this.title = title; }
    }

    private static final long MAX_CHPL_BYTES = 65536;
    private static final int MAX_BOXES = 10000;
    private Mp4Chapters() { }

    static List<Chapter> read(SeekableByteChannel channel) throws IOException {
        long length = channel.size();
        if (length < 8) return Collections.emptyList();
        int[] visits = {0};
        return boxes(channel, 0, length, 0, visits);
    }

    private static List<Chapter> boxes(SeekableByteChannel channel, long from, long end,
                                       int depth, int[] visits) throws IOException {
        long cursor = from;
        while (cursor <= end - 8 && ++visits[0] <= MAX_BOXES) {
            byte[] header = bytes(channel, cursor, 8);
            long size = unsigned32(header, 0);
            String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
            int headerSize = 8;
            if (size == 1) {
                if (cursor > end - 16) break;
                byte[] extended = bytes(channel, cursor + 8, 8);
                long high = unsigned32(extended, 0);
                if (high > Integer.MAX_VALUE) break;
                size = (high << 32) | unsigned32(extended, 4);
                headerSize = 16;
            } else if (size == 0) size = end - cursor;
            if (size < headerSize || size > end - cursor) break;
            long data = cursor + headerSize;
            long next = cursor + size;
            if (depth == 0 && "moov".equals(type)) {
                List<Chapter> found = boxes(channel, data, next, 1, visits);
                if (!found.isEmpty()) return found;
            } else if (depth == 1 && "udta".equals(type)) {
                List<Chapter> found = boxes(channel, data, next, 2, visits);
                if (!found.isEmpty()) return found;
            } else if (depth == 2 && "chpl".equals(type) && next - data <= MAX_CHPL_BYTES) {
                return parse(bytes(channel, data, (int) (next - data)));
            }
            cursor = next;
        }
        return Collections.emptyList();
    }

    private static List<Chapter> parse(byte[] payload) {
        if (payload.length < 5) return Collections.emptyList();
        int version = payload[0] & 255;
        if (version != 0 && version != 1) return Collections.emptyList();
        int offset = version == 1 ? 8 : 4;
        if (offset >= payload.length) return Collections.emptyList();
        int count = payload[offset++] & 255;
        List<Chapter> chapters = new ArrayList<>(count);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int width = version == 1 ? 8 : 4;
            if (offset > payload.length - width - 1) return Collections.emptyList();
            long ticks;
            if (version == 1) {
                long high = unsigned32(payload, offset);
                if (high > 4) return Collections.emptyList();
                ticks = (high << 32) | unsigned32(payload, offset + 4);
            } else ticks = unsigned32(payload, offset);
            offset += width;
            int nameLength = payload[offset++] & 255;
            if (nameLength > payload.length - offset) return Collections.emptyList();
            long millis = ticks / 10000;
            if (millis > Integer.MAX_VALUE || millis <= previous) return Collections.emptyList();
            String name = new String(payload, offset, nameLength, StandardCharsets.UTF_8).trim();
            offset += nameLength;
            chapters.add(new Chapter((int) millis, name.isEmpty() ? "Chapter " + (i + 1) : name));
            previous = (int) millis;
        }
        return chapters;
    }

    private static byte[] bytes(SeekableByteChannel channel, long position, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count).order(ByteOrder.BIG_ENDIAN);
        channel.position(position);
        int emptyReads = 0;
        while (buffer.hasRemaining()) {
            int got = channel.read(buffer);
            if (got < 0 || (got == 0 && ++emptyReads > 3)) throw new IOException("Truncated MP4 box");
            if (got > 0) emptyReads = 0;
        }
        return buffer.array();
    }

    private static long unsigned32(byte[] data, int offset) {
        return ((long) (data[offset] & 255) << 24) | ((long) (data[offset + 1] & 255) << 16)
                | ((long) (data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }
}
