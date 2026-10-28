package app.arioso.audiobook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads bounded iTunes freeform series and numeric order fields from seekable MP4 media. */
final class Mp4SeriesMetadata {
    static final class Details {
        final String series, number;
        Details(String series, String number) { this.series = series; this.number = number; }
    }
    private static final int MAX_BOXES = 10000;
    private static final int MAX_FIELD_BYTES = 2048;
    private Mp4SeriesMetadata() { }

    static Details read(SeekableByteChannel channel) throws IOException {
        long[] moov = find(channel, 0, channel.size(), "moov", new int[]{0});
        if (moov == null) return new Details(null, null);
        int[] visits = {0};
        long[] udta = find(channel, moov[0], moov[1], "udta", visits);
        if (udta == null) return new Details(null, null);
        long[] meta = find(channel, udta[0], udta[1], "meta", visits);
        if (meta == null || meta[0] > meta[1] - 4) return new Details(null, null);
        long[] ilst = find(channel, meta[0] + 4, meta[1], "ilst", visits);
        if (ilst == null) return new Details(null, null);
        String series = null;
        String number = null;
        long cursor = ilst[0];
        while (cursor <= ilst[1] - 8 && ++visits[0] <= MAX_BOXES) {
            byte[] head = bytes(channel, cursor, 8);
            long size = u32(head, 0);
            String type = new String(head, 4, 4, StandardCharsets.US_ASCII);
            if (size < 8 || size > ilst[1] - cursor) break;
            if ("----".equals(type)) {
                long from = cursor + 8;
                long end = cursor + size;
                long[] nameBox = find(channel, from, end, "name", visits);
                long[] dataBox = find(channel, from, end, "data", visits);
                if (nameBox != null && dataBox != null
                        && nameBox[1] - nameBox[0] >= 4 && dataBox[1] - dataBox[0] >= 8
                        && nameBox[1] - nameBox[0] <= MAX_FIELD_BYTES + 4
                        && dataBox[1] - dataBox[0] <= MAX_FIELD_BYTES + 8) {
                    String field = new String(bytes(channel, nameBox[0] + 4,
                            (int) (nameBox[1] - nameBox[0] - 4)), StandardCharsets.UTF_8)
                            .trim().toUpperCase(Locale.ROOT);
                    if ("SERIES".equals(field) || "PART".equals(field)
                            || "SERIES-PART".equals(field)) {
                        String value = new String(bytes(channel, dataBox[0] + 8,
                                (int) (dataBox[1] - dataBox[0] - 8)), StandardCharsets.UTF_8)
                                .replace('\n', ' ').replace('\r', ' ').trim();
                        if ("SERIES".equals(field) && series == null && !value.isEmpty()) {
                            if (value.codePointCount(0, value.length()) > 180)
                                value = value.substring(0, value.offsetByCodePoints(0, 180));
                            series = value;
                        } else if (("PART".equals(field) || "SERIES-PART".equals(field))
                                && number == null
                                && value.matches("[0-9]{1,4}(\\.[0-9]{1,2})?")) number = value;
                    }
                }
            }
            cursor += size;
        }
        return new Details(series, number);
    }

    private static long[] find(SeekableByteChannel ch, long from, long end, String target,
                               int[] visits) throws IOException {
        long cursor = from;
        while (cursor <= end - 8 && ++visits[0] <= MAX_BOXES) {
            byte[] head = bytes(ch, cursor, 8);
            long size = u32(head, 0);
            String type = new String(head, 4, 4, StandardCharsets.US_ASCII);
            int header = 8;
            if (size == 1) {
                if (cursor > end - 16) break;
                byte[] large = bytes(ch, cursor + 8, 8);
                if ((large[0] & 128) != 0) break;
                size = (u32(large, 0) << 32) | u32(large, 4);
                header = 16;
            } else if (size == 0) size = end - cursor;
            if (size < header || size > end - cursor) break;
            if (target.equals(type)) return new long[]{cursor + header, cursor + size};
            cursor += size;
        }
        return null;
    }
    private static byte[] bytes(SeekableByteChannel ch, long position, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        ch.position(position);
        int emptyReads = 0;
        while (buffer.hasRemaining()) {
            int n = ch.read(buffer);
            if (n < 0 || (n == 0 && ++emptyReads > 3)) throw new IOException("Truncated MP4 metadata");
            if (n > 0) emptyReads = 0;
        }
        return buffer.array();
    }
    private static long u32(byte[] data, int at) {
        return ((long) (data[at] & 255) << 24) | ((long) (data[at + 1] & 255) << 16)
                | ((long) (data[at + 2] & 255) << 8) | (data[at + 3] & 255);
    }
}
