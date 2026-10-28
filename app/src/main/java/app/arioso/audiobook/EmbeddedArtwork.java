package app.arioso.audiobook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/** Reads only small embedded cover payloads from seekable local MP3 and MP4 files. */
final class EmbeddedArtwork {
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BOXES = 10000;

    private EmbeddedArtwork() { }

    static byte[] read(SeekableByteChannel channel) throws IOException {
        long length = channel.size();
        if (length < 10) return null;
        byte[] first = bytes(channel, 0, 10);
        if (first[0] == 'I' && first[1] == 'D' && first[2] == '3')
            return id3(channel, first, length);
        int[] visits = {0};
        return mp4Boxes(channel, 0, length, 0, visits);
    }

    private static byte[] id3(SeekableByteChannel channel, byte[] header, long length)
            throws IOException {
        int version = header[3] & 255;
        if ((version != 3 && version != 4) || (header[5] & 0x80) != 0) return null;
        int tagSize = syncSafe(header, 6);
        if (tagSize < 10 || tagSize > length - 10) return null;
        long end = 10L + tagSize;
        long cursor = 10;
        for (int frames = 0; frames < MAX_BOXES && cursor <= end - 10; frames++) {
            byte[] frame = bytes(channel, cursor, 10);
            if (frame[0] == 0) break;
            long size = version == 4 ? syncSafe(frame, 4) : unsigned32(frame, 4);
            if (size <= 0 || size > end - cursor - 10) break;
            // Compressed/encrypted/unsynchronised frames require a different decoder.
            int flags = frame[9] & 255;
            if (frame[0] == 'A' && frame[1] == 'P' && frame[2] == 'I' && frame[3] == 'C'
                    && flags == 0 && size <= MAX_IMAGE_BYTES + 1024L) {
                byte[] payload = bytes(channel, cursor + 10, (int) size);
                byte[] image = apicImage(payload);
                if (image != null) return image;
            }
            cursor += 10 + size;
        }
        return null;
    }

    private static byte[] apicImage(byte[] payload) {
        if (payload.length < 5) return null;
        int encoding = payload[0] & 255;
        int cursor = 1;
        while (cursor < payload.length && payload[cursor] != 0) cursor++;
        if (cursor >= payload.length - 2) return null;
        cursor += 2; // MIME terminator and picture type.
        if (encoding == 0 || encoding == 3) {
            while (cursor < payload.length && payload[cursor] != 0) cursor++;
            cursor++;
        } else if (encoding == 1 || encoding == 2) {
            while (cursor + 1 < payload.length
                    && !(payload[cursor] == 0 && payload[cursor + 1] == 0)) cursor += 2;
            cursor += 2;
        } else return null;
        int remaining = payload.length - cursor;
        if (remaining <= 0 || remaining > MAX_IMAGE_BYTES) return null;
        byte[] image = new byte[remaining];
        System.arraycopy(payload, cursor, image, 0, remaining);
        return image;
    }

    private static byte[] mp4Boxes(SeekableByteChannel channel, long from, long end,
                                   int depth, int[] visits) throws IOException {
        if (depth > 5) return null;
        long cursor = from;
        while (cursor <= end - 8 && ++visits[0] <= MAX_BOXES) {
            byte[] header = bytes(channel, cursor, 8);
            long size = unsigned32(header, 0);
            String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            int headerSize = 8;
            if (size == 1) {
                if (cursor > end - 16) break;
                byte[] extended = bytes(channel, cursor + 8, 8);
                if (unsigned32(extended, 0) > Integer.MAX_VALUE) break;
                size = (unsigned32(extended, 0) << 32) | unsigned32(extended, 4);
                headerSize = 16;
            } else if (size == 0) size = end - cursor;
            if (size < headerSize || size > end - cursor) break;
            long data = cursor + headerSize;
            long next = cursor + size;
            if ("data".equals(type) && depth >= 4 && next - data >= 8
                    && next - data - 8 <= MAX_IMAGE_BYTES) {
                byte[] image = bytes(channel, data + 8, (int) (next - data - 8));
                if (image.length > 0) return image;
            } else if ("moov".equals(type) && depth == 0
                    || "udta".equals(type) && depth == 1
                    || "meta".equals(type) && (depth == 1 || depth == 2)
                    || "ilst".equals(type) && (depth == 2 || depth == 3)
                    || "covr".equals(type) && (depth == 3 || depth == 4)) {
                long child = data + ("meta".equals(type) ? 4 : 0);
                if (child <= next) {
                    byte[] image = mp4Boxes(channel, child, next, depth + 1, visits);
                    if (image != null) return image;
                }
            }
            cursor = next;
        }
        return null;
    }

    private static byte[] bytes(SeekableByteChannel channel, long position, int count)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        channel.position(position);
        int emptyReads = 0;
        while (buffer.hasRemaining()) {
            int got = channel.read(buffer);
            if (got < 0 || (got == 0 && ++emptyReads > 3)) throw new IOException("Truncated cover data");
            if (got > 0) emptyReads = 0;
        }
        return buffer.array();
    }

    private static int syncSafe(byte[] bytes, int offset) {
        for (int i = 0; i < 4; i++) if ((bytes[offset + i] & 0x80) != 0) return -1;
        return (bytes[offset] & 127) << 21 | (bytes[offset + 1] & 127) << 14
                | (bytes[offset + 2] & 127) << 7 | (bytes[offset + 3] & 127);
    }

    private static long unsigned32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }
}
