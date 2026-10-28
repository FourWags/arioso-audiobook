package app.arioso.audiobook;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Bounded, opt-in, local-only audio analysis. No decoded audio is retained. */
final class SilenceScanner {
    static final class Span {
        final int startMs;
        final int endMs;
        Span(int startMs, int endMs) { this.startMs = startMs; this.endMs = endMs; }
    }

    private SilenceScanner() { }

    static List<Span> scan(Context context, String source, int startMs, int endMs) {
        Uri uri = Uri.parse(source);
        if (!LocalDocumentPolicy.accepts(uri.getScheme(), uri.getAuthority())) return null;
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        List<Span> found = new ArrayList<>();
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat input = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    input = candidate;
                    break;
                }
            }
            if (input == null) return null;
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();
            extractor.seekTo(Math.max(0, startMs) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long quietStart = -1, quietEnd = -1;
            long deadline = SystemClock.elapsedRealtime() + 10000;
            int loops = 0;
            while (!outputDone && loops++ < 30000 && SystemClock.elapsedRealtime() < deadline
                    && !Thread.currentThread().isInterrupted()) {
                if (!inputDone) {
                    int slot = decoder.dequeueInputBuffer(1000);
                    if (slot >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(slot);
                        long sampleTime = extractor.getSampleTime();
                        if (buffer == null || sampleTime < 0 || sampleTime > endMs * 1000L) {
                            decoder.queueInputBuffer(slot, 0, 0, Math.max(0, sampleTime),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buffer.clear();
                            int size = extractor.readSampleData(buffer, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(slot, 0, 0, sampleTime,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                decoder.queueInputBuffer(slot, 0, size, sampleTime, 0);
                                extractor.advance();
                            }
                        }
                    }
                }
                int slot = decoder.dequeueOutputBuffer(info, 1000);
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (slot < 0) continue;
                MediaFormat format = decoder.getOutputFormat(slot);
                int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        : AudioFormat.ENCODING_PCM_16BIT;
                if (encoding != AudioFormat.ENCODING_PCM_16BIT) return null;
                int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                ByteBuffer pcm = decoder.getOutputBuffer(slot);
                if (pcm != null && info.size > 0 && rate > 0 && channels > 0) {
                    pcm.order(ByteOrder.LITTLE_ENDIAN);
                    int frames = info.size / (2 * channels);
                    int window = Math.max(1, rate / 50);
                    for (int frame = 0; frame < frames; frame += window) {
                        int last = Math.min(frames, frame + window);
                        int peak = 0;
                        for (int sample = frame; sample < last; sample += 8)
                            for (int channel = 0; channel < channels; channel++) {
                                int offset = info.offset + (sample * channels + channel) * 2;
                                peak = Math.max(peak, Math.abs((int) pcm.getShort(offset)));
                            }
                        long begin = info.presentationTimeUs + frame * 1000000L / rate;
                        long end = info.presentationTimeUs + last * 1000000L / rate;
                        if (begin < startMs * 1000L) continue;
                        if (peak <= 350) {
                            if (quietStart < 0) quietStart = begin;
                            quietEnd = end;
                        } else if (quietStart >= 0) {
                            add(found, quietStart, quietEnd);
                            quietStart = quietEnd = -1;
                        }
                    }
                }
                outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(slot, false);
            }
            if (!outputDone || Thread.currentThread().isInterrupted()) return null;
            if (quietStart >= 0) add(found, quietStart, quietEnd);
            return found;
        } catch (Exception | LinkageError e) { return null; }
        finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) { }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static void add(List<Span> output, long startUs, long endUs) {
        if (output.size() >= 100 || endUs - startUs < 900000L) return;
        int start = (int) Math.min(Integer.MAX_VALUE, (startUs + 150000) / 1000);
        int end = (int) Math.min(Integer.MAX_VALUE, (endUs - 150000) / 1000);
        if (end > start) output.add(new Span(start, end));
    }
}
