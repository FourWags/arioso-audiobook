package app.arioso.audiobook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioRouting;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns one local decoder, the media session, sleep timer, and persisted playback state. */
public final class PlaybackService extends Service {
    interface Listener { void onPlaybackChanged(); }
    final class LocalBinder extends Binder { PlaybackService getService() { return PlaybackService.this; } }

    private static final String PREFS = "arioso_library_v1";
    private static final String CHANNEL = "arioso_playback";
    private static final int NOTIFICATION_ID = 11;
    private static final String ACTION_TOGGLE = "app.arioso.audiobook.TOGGLE";
    private static final String ACTION_BACK = "app.arioso.audiobook.BACK_15";
    private static final String ACTION_FORWARD = "app.arioso.audiobook.FORWARD_30";
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private LoudnessEnhancer voiceEffect;
    private boolean voiceBoost;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean playing;
    private boolean noisyReceiverRegistered;
    private boolean routeCallbackRegistered;
    private int privateOutputId = -1;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };
    private final AudioRouting.OnRoutingChangedListener routingListener = routing -> {
        if (!playing || routing != player) return;
        AudioDeviceInfo current = routing.getRoutedDevice();
        if (privateOutputId >= 0 && (current == null || current.getId() != privateOutputId)) {
            pause();
        } else if (isPrivateOutput(current)) {
            privateOutputId = current.getId();
        }
    };
    private final AudioDeviceCallback routeCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (!playing) return;
            for (AudioDeviceInfo device : removed) if (device.getId() == privateOutputId
                    || (android.os.Build.VERSION.SDK_INT < 28 && isPrivateOutput(device))) {
                pause();
                return;
            }
        }
    };
    private Listener listener;
    private String uri;
    private String title;
    private String author;
    private int duration;
    private int pendingSeek;
    private final ArrayDeque<Integer> recentPlaces = new ArrayDeque<>();
    private boolean ready;
    private boolean unavailable;
    private boolean completed;
    private boolean autoPlayAfterPrepare;
    private long sleepDeadline;
    private boolean fadeSleep;
    private int sleepFadeMs = 15000;
    private int chapterStopMs;
    private int repeatA = -1;
    private int repeatB = -1;
    private boolean repeatSeeking;
    private String chapterStopLabel = "end of chapter";
    private int generation;
    private float speed = 1f;
    private float pitch = 1f;
    private boolean privatePlayback;
    private String failureReason = "";
    private int backMs = 15000;
    private int forwardMs = 30000;
    private long statsCheckpoint;
    private final ExecutorService silenceWorker = Executors.newSingleThreadExecutor();
    private List<SilenceScanner.Span> silenceSpans = Collections.emptyList();
    private boolean skipSilence;
    private boolean silenceUnavailable;
    private boolean silenceScanInFlight;
    private int silenceScannedUntil;
    private int silenceToken;
    private final Runnable silenceTick = new Runnable() {
        @Override public void run() {
            if (!playing || !ready || !skipSilence || silenceUnavailable || player == null) return;
            if (ListeningTools.validRepeatWindow(repeatA, repeatB, duration)) {
                handler.postDelayed(this, 500); return;
            }
            int at = position();
            for (SilenceScanner.Span span : silenceSpans) if (at >= span.startMs
                    && at < span.endMs && span.endMs < duration - 1000) {
                try { player.seekTo(span.endMs); }
                catch (IllegalStateException ignored) { }
                break;
            }
            if (at + 30000 >= silenceScannedUntil) scanAhead(at);
            handler.postDelayed(this, 250);
        }
    };
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            saveCurrent(false, false);
            if (SystemClock.elapsedRealtime() - statsCheckpoint >= 30000) recordListeningTime();
            publishState();
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable sleepTick = () -> {
        if (sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline) pause();
    };
    private final Runnable fadeTick = new Runnable() {
        @Override public void run() {
            if (!playing || !fadeSleep || sleepDeadline == 0 || player == null) return;
            long remaining = sleepDeadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) { pause(); return; }
            if (remaining > sleepFadeMs) {
                handler.postDelayed(this, remaining - sleepFadeMs);
                return;
            }
            float volume = Math.min(1f, Math.max(0f, remaining / (float) sleepFadeMs));
            try { player.setVolume(volume, volume); }
            catch (IllegalStateException ignored) { }
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable chapterStopTick = new Runnable() {
        @Override public void run() {
            if (!playing || chapterStopMs <= 0) return;
            if (position() >= chapterStopMs) { pause(); return; }
            handler.postDelayed(this, 250);
        }
    };
    private final Runnable repeatTick = new Runnable() {
        @Override public void run() {
            if (!playing || !ready || player == null
                    || !ListeningTools.validRepeatWindow(repeatA, repeatB, duration)) return;
            if (repeatSeeking) return;
            if (position() >= repeatB) {
                try {
                    repeatSeeking = true;
                    player.seekTo(repeatA);
                } catch (IllegalStateException e) { clearRepeat(); }
            } else handler.postDelayed(this, 150);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        SharedPreferences playbackPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        backMs = Math.max(10000, Math.min(120000, playbackPrefs.getInt("skip_back_ms", 15000)));
        forwardMs = Math.max(10000, Math.min(120000, playbackPrefs.getInt("skip_forward_ms", 30000)));
        sleepFadeMs = Math.max(5000, Math.min(60000, playbackPrefs.getInt("sleep_fade_ms", 15000)));
        privatePlayback = playbackPrefs.getBoolean("private_playback", false);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change <= 0 && playing) pause();
                }, handler).build();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Audiobook playback", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "Arioso");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (uri == null) playSelectedFromCar(); else play(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                playFromMediaId(mediaId);
            }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                playFromSearch(query);
            }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSeekTo(long position) { seekTo(position); }
            @Override public void onRewind() { skip(-backMs); }
            @Override public void onFastForward() { skip(forwardMs); }
        }, handler);
        session.setActive(true);
        publishState();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_TOGGLE.equals(intent.getAction())) toggle();
            else if (ACTION_BACK.equals(intent.getAction())) skip(-backMs);
            else if (ACTION_FORWARD.equals(intent.getAction())) skip(forwardMs);
        }
        return START_NOT_STICKY;
    }

    void setListener(Listener target) { listener = target; notifyListener(); }
    void setPrivatePlayback(boolean next) {
        if (privatePlayback == next) return;
        privatePlayback = next;
        publishState();
    }
    void clearListener(Listener target) { if (listener == target) listener = null; }
    String currentUri() { return uri; }
    MediaSession.Token mediaToken() { return session == null ? null : session.getSessionToken(); }
    boolean isReady() { return ready; }
    boolean isPlaying() { return playing; }
    boolean isUnavailable() { return unavailable; }
    String failureReason() { return failureReason; }
    boolean isCompleted() { return completed; }
    int duration() { return duration; }
    List<Integer> recentPlaces() { return new ArrayList<>(recentPlaces); }
    void clearRecentPlaces() { recentPlaces.clear(); }
    int repeatStart() { return repeatA; }
    int repeatEnd() { return repeatB; }
    String outputRoute() {
        if (!ready || player == null || android.os.Build.VERSION.SDK_INT < 28) return "Unknown";
        try {
            AudioDeviceInfo routed = player.getRoutedDevice();
            if (routed == null) return "Unknown";
            int type = routed.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "Speaker";
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET) return "Wired headphones";
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return "Bluetooth";
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET) return "USB audio";
            if (type == AudioDeviceInfo.TYPE_HDMI) return "HDMI";
            return "Other output";
        } catch (RuntimeException ignored) { return "Unknown"; }
    }
    boolean markRepeatStart() {
        if (!ready || duration < 4000 || position() > duration - 4000) return false;
        repeatA = position();
        repeatB = -1;
        repeatSeeking = false;
        handler.removeCallbacks(repeatTick);
        notifyListener();
        return true;
    }
    boolean markRepeatEnd() {
        int end = position();
        if (!ready || !ListeningTools.validRepeatWindow(repeatA, end, duration)) return false;
        repeatB = end;
        repeatSeeking = false;
        handler.removeCallbacks(repeatTick);
        if (playing) handler.post(repeatTick);
        notifyListener();
        return true;
    }
    void clearRepeat() {
        repeatA = -1;
        repeatB = -1;
        repeatSeeking = false;
        handler.removeCallbacks(repeatTick);
        notifyListener();
    }
    long sleepRemaining() { return Math.max(0, sleepDeadline - SystemClock.elapsedRealtime()); }
    boolean isChapterStopSet() { return chapterStopMs > 0; }
    String chapterStopLabel() { return chapterStopLabel; }
    int position() {
        if (completed) return 0;
        if (!ready || player == null) return Math.max(0, pendingSeek);
        try { return player.getCurrentPosition(); }
        catch (IllegalStateException ignored) { return Math.max(0, pendingSeek); }
    }

    void load(String selectedUri, String selectedTitle, String selectedAuthor,
              int resumeMs, boolean wasFinished) {
        loadInternal(selectedUri, selectedTitle, selectedAuthor, resumeMs, wasFinished, false, false);
    }

    void loadAndPlay(String selectedUri, String selectedTitle, String selectedAuthor,
                     int resumeMs, boolean wasFinished) {
        if (selectedUri != null && selectedUri.equals(uri) && player != null && !unavailable) {
            updateDetails(selectedTitle, selectedAuthor);
            if (ready) toggle();
            else autoPlayAfterPrepare = true;
            return;
        }
        loadInternal(selectedUri, selectedTitle, selectedAuthor, resumeMs, wasFinished, true, true);
    }

    private void loadInternal(String selectedUri, String selectedTitle, String selectedAuthor,
                              int resumeMs, boolean wasFinished, boolean autoPlay,
                              boolean keepForeground) {
        if (selectedUri == null || selectedUri.length() > 4096) return;
        Uri document = Uri.parse(selectedUri);
        if (!LocalDocumentPolicy.accepts(document.getScheme(), document.getAuthority())) return;
        if (selectedUri.equals(uri) && player != null && !unavailable) {
            updateDetails(selectedTitle, selectedAuthor);
            return;
        }
        saveCurrent(true, false);
        releaseDecoder(keepForeground);
        recentPlaces.clear();
        uri = selectedUri;
        title = selectedTitle;
        author = selectedAuthor;
        autoPlayAfterPrepare = autoPlay;
        pendingSeek = Math.max(0, resumeMs);
        completed = wasFinished;
        unavailable = false;
        failureReason = "";
        if (keepForeground) startForeground(NOTIFICATION_ID, notification());
        final int token = ++generation;
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            if (android.os.Build.VERSION.SDK_INT >= 28)
                next.addOnRoutingChangedListener(routingListener, handler);
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            next.setDataSource(this, Uri.parse(uri));
            next.setOnPreparedListener(mp -> {
                if (token != generation || mp != player) return;
                applyVoiceBoost();
                duration = Math.max(0, mp.getDuration());
                int resume = ListeningTools.boundedPosition(pendingSeek, Math.max(0, duration - 1000));
                pendingSeek = resume;
                if (resume > 0) {
                    try { mp.seekTo(resume); }
                    catch (IllegalStateException e) { fail(); }
                } else finishPreparation();
            });
            next.setOnSeekCompleteListener(mp -> {
                if (token == generation && mp == player) {
                    if (!ready) finishPreparation();
                    else { saveCurrent(false, false); publishState(); }
                    if (repeatSeeking) {
                        repeatSeeking = false;
                        if (playing) handler.postDelayed(repeatTick, 150);
                    }
                }
            });
            next.setOnCompletionListener(mp -> {
                if (token != generation || mp != player) return;
                recordListeningTime();
                playing = false;
                unregisterNoisyReceiver();
                unregisterRouteCallback();
                pendingSeek = 0;
                completed = true;
                handler.removeCallbacks(saveTick);
                handler.removeCallbacks(sleepTick);
                handler.removeCallbacks(fadeTick);
                handler.removeCallbacks(chapterStopTick);
                handler.removeCallbacks(repeatTick);
                repeatA = repeatB = -1;
                repeatSeeking = false;
                sleepDeadline = 0;
                chapterStopMs = 0;
                chapterStopLabel = "end of chapter";
                fadeSleep = false;
                try { mp.setVolume(1f, 1f); }
                catch (IllegalStateException ignored) { }
                saveCurrent(true, true);
                recordCompletion(uri);
                JSONObject followingBook = nextAfterCompletion();
                if (followingBook == null) {
                    endForeground();
                    publishState();
                } else {
                    speed = (float) followingBook.optDouble("playbackSpeed", 1.0);
                    pitch = (float) followingBook.optDouble("playbackPitch", 1.0);
                    voiceBoost = followingBook.optBoolean("voiceBoost", false);
                    loadInternal(followingBook.optString("uri"), followingBook.optString("title"),
                            followingBook.optString("author"), followingBook.optInt("position"),
                            followingBook.optBoolean("finished"), true, true);
                    setSkipSilence(followingBook.optBoolean("skipSilence", false));
                }
            });
            next.setOnErrorListener((mp, what, extra) -> {
                if (token == generation && mp == player) fail("This audio could not be decoded. Check the file or try another format.");
                return true;
            });
            next.prepareAsync();
        } catch (SecurityException e) { fail("Arioso lost access to this file. Relink it from on-device storage."); }
        catch (java.io.FileNotFoundException e) { fail("This file is missing. Relink its new location."); }
        catch (Exception e) { fail("This file could not be opened. Check its format and storage location."); }
        publishState();
    }

    private void finishPreparation() {
        ready = true;
        pendingSeek = position();
        saveCurrent(false, false);
        publishState();
        if (autoPlayAfterPrepare) {
            autoPlayAfterPrepare = false;
            play();
            if (!playing) endForeground();
        }
    }

    void updateDetails(String newTitle, String newAuthor) {
        title = newTitle;
        author = newAuthor;
        publishState();
    }

    void toggle() { if (playing) pause(); else play(); }

    private void playSelectedFromCar() {
        String selected = getSharedPreferences(PREFS, MODE_PRIVATE).getString("selected_uri", "");
        if (selected == null || selected.isEmpty()) return;
        playFromMediaId(CarMediaIds.book(selected));
    }

    private void playFromSearch(String query) {
        if (query == null || query.trim().isEmpty()) { playSelectedFromCar(); return; }
        SharedPreferences carPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!carPrefs.getBoolean("auto_browse_enabled", false)
                || carPrefs.getBoolean("private_playback", false)) return;
        try {
            JSONArray saved = LibraryStore.get(this).readAll();
            String needle = query.toLowerCase(java.util.Locale.ROOT).trim();
            for (int i = 0; i < Math.min(saved.length(), 500); i++) {
                JSONObject book = saved.optJSONObject(i);
                if (book == null || book.optBoolean("archived")) continue;
                if (book.optString("title", book.optString("name", ""))
                        .toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    playFromMediaId(CarMediaIds.book(book.optString("uri")));
                    return;
                }
            }
        } catch (RuntimeException ignored) { }
    }

    private void playFromMediaId(String mediaId) {
        SharedPreferences carPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!carPrefs.getBoolean("auto_browse_enabled", false)
                || carPrefs.getBoolean("private_playback", false)) return;
        try {
            JSONArray saved = LibraryStore.get(this).readAll();
            for (int i = 0; i < Math.min(saved.length(), 500); i++) {
                JSONObject book = saved.optJSONObject(i);
                if (book == null || book.optBoolean("archived")) continue;
                String selected = book.optString("uri");
                if (!mediaId.equals(CarMediaIds.book(selected))
                        || !LocalDocumentPolicy.accepts(Uri.parse(selected).getScheme(),
                        Uri.parse(selected).getAuthority())) continue;
                if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("selected_uri", selected).commit()) return;
                setSpeed((float) book.optDouble("playbackSpeed", 1.0));
                setPitch((float) book.optDouble("playbackPitch", 1.0));
                setVoiceBoost(book.optBoolean("voiceBoost", false));
                setSkipSilence(book.optBoolean("skipSilence", false));
                if (selected.equals(uri) && ready) play();
                else loadInternal(selected, book.optString("title", book.optString("name", "Book")),
                        book.optString("author", ""), book.optInt("position"),
                        book.optBoolean("finished"), true, true);
                return;
            }
        } catch (RuntimeException ignored) { }
    }

    void play() {
        if (!ready || player == null || playing) return;
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            notifyListener();
            return;
        }
        try {
            if (completed) {
                completed = false;
                player.seekTo(0);
            }
            else if (getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("smart_rewind_enabled", true)) {
                SharedPreferences saved = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (uri != null && uri.equals(saved.getString("last_paused_uri", null))) {
                    int at = position();
                    int target = SmartRewind.position(at,
                            saved.getInt("last_paused_position", -1),
                            saved.getLong("last_paused_at", 0), System.currentTimeMillis());
                    if (target < at) player.seekTo(target);
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("last_paused_uri").remove("last_paused_at")
                    .remove("last_paused_position").apply();
            startForegroundService(new Intent(this, PlaybackService.class));
            startForeground(NOTIFICATION_ID, notification());
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed).setPitch(pitch));
            player.start();
            playing = true;
            statsCheckpoint = SystemClock.elapsedRealtime();
            registerNoisyReceiver();
            registerRouteCallback();
            AudioDeviceInfo output = android.os.Build.VERSION.SDK_INT >= 28
                    ? player.getRoutedDevice() : null;
            privateOutputId = isPrivateOutput(output) ? output.getId() : -1;
            saveCurrent(false, false);
            handler.removeCallbacks(saveTick);
            handler.postDelayed(saveTick, 5000);
            if (chapterStopMs > 0) handler.post(chapterStopTick);
            if (fadeSleep && sleepDeadline > 0) handler.post(fadeTick);
            if (ListeningTools.validRepeatWindow(repeatA, repeatB, duration)) handler.post(repeatTick);
            if (skipSilence) handler.post(silenceTick);
            publishState();
        } catch (RuntimeException e) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            fail();
        }
    }

    void pause() {
        autoPlayAfterPrepare = false;
        boolean wasPlaying = playing;
        if (wasPlaying) recordListeningTime();
        if (player != null && playing) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
        playing = false;
        unregisterNoisyReceiver();
        unregisterRouteCallback();
        handler.removeCallbacks(saveTick);
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(chapterStopTick);
        handler.removeCallbacks(repeatTick);
        handler.removeCallbacks(silenceTick);
        sleepDeadline = 0;
        chapterStopMs = 0;
        chapterStopLabel = "end of chapter";
        fadeSleep = false;
        if (player != null) try { player.setVolume(1f, 1f); }
        catch (IllegalStateException ignored) { }
        saveCurrent(true, false);
        if (wasPlaying && uri != null) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_paused_uri", uri)
                .putInt("last_paused_position", position())
                .putLong("last_paused_at", System.currentTimeMillis()).apply();
        endForeground();
        publishState();
    }

    void seekTo(long requested) {
        if (!ready || player == null) return;
        int target = ListeningTools.boundedPosition(requested, duration);
        int previous = position();
        pendingSeek = target;
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("last_paused_uri").apply();
            // A chapter or bookmark jump from a finished book starts a new listening position.
            completed = false;
            player.seekTo(target);
            if (Math.abs(previous - target) >= 10000) {
                if (recentPlaces.isEmpty() || Math.abs(recentPlaces.peekFirst() - previous) >= 5000) {
                    recentPlaces.addFirst(previous);
                    while (recentPlaces.size() > 10) recentPlaces.removeLast();
                }
            }
            saveCurrent(true, false);
            publishState();
        } catch (IllegalStateException ignored) { }
    }

    void skip(int offset) { seekTo((long) position() + offset); }

    void setSpeed(float next) {
        if (next < 0.5f || next > 3f) return;
        speed = next;
        if (playing && player != null) {
            try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed).setPitch(pitch)); }
            catch (IllegalStateException ignored) { }
        }
        publishState();
    }

    void setPitch(float next) {
        if (next < 0.8f || next > 1.2f) return;
        pitch = next;
        if (playing && player != null) {
            try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed).setPitch(pitch)); }
            catch (IllegalStateException ignored) { }
        }
    }

    void setVoiceBoost(boolean enabled) {
        voiceBoost = enabled;
        applyVoiceBoost();
    }

    void setSkipSilence(boolean enabled) {
        skipSilence = enabled;
        silenceUnavailable = false;
        silenceScannedUntil = 0;
        silenceSpans = Collections.emptyList();
        silenceToken++;
        handler.removeCallbacks(silenceTick);
        if (enabled && playing) handler.post(silenceTick);
        notifyListener();
    }

    boolean isSilenceUnavailable() { return silenceUnavailable; }

    private void scanAhead(int at) {
        if (silenceScanInFlight || uri == null || duration <= 0) return;
        int end = Math.min(duration, at + 120000);
        if (end <= at + 1000) return;
        silenceScanInFlight = true;
        int token = ++silenceToken;
        String source = uri;
        android.content.Context app = getApplicationContext();
        silenceWorker.execute(() -> {
            List<SilenceScanner.Span> found = SilenceScanner.scan(app, source, at, end);
            handler.post(() -> {
                silenceScanInFlight = false;
                if (token != silenceToken || !source.equals(uri) || !skipSilence) return;
                if (found == null) silenceUnavailable = true;
                else { silenceSpans = found; silenceScannedUntil = end; }
                notifyListener();
            });
        });
    }

    private void applyVoiceBoost() {
        if (voiceEffect != null) {
            voiceEffect.release();
            voiceEffect = null;
        }
        if (!voiceBoost || player == null) return;
        try {
            voiceEffect = new LoudnessEnhancer(player.getAudioSessionId());
            voiceEffect.setTargetGain(300);
            voiceEffect.setEnabled(true);
        } catch (RuntimeException e) {
            if (voiceEffect != null) voiceEffect.release();
            voiceEffect = null;
        }
    }

    void setSkipIntervals(int back, int forward) {
        if (back < 10000 || back > 120000 || forward < 10000 || forward > 120000) return;
        backMs = back;
        forwardMs = forward;
        if (playing) startForeground(NOTIFICATION_ID, notification());
    }

    void setSleepMinutes(int minutes, boolean fade) {
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(chapterStopTick);
        chapterStopMs = 0;
        chapterStopLabel = "end of chapter";
        fadeSleep = fade && minutes > 0;
        if (player != null) try { player.setVolume(1f, 1f); }
        catch (IllegalStateException ignored) { }
        sleepDeadline = minutes <= 0 ? 0 : ListeningTools.deadline(SystemClock.elapsedRealtime(), minutes);
        if (sleepDeadline > 0) handler.postDelayed(sleepTick, minutes * 60000L);
        if (fadeSleep && playing) handler.post(fadeTick);
        notifyListener();
    }

    void setFadeDuration(int millis) {
        if (millis < 5000 || millis > 60000) return;
        sleepFadeMs = millis;
        handler.removeCallbacks(fadeTick);
        if (fadeSleep && playing && sleepDeadline > 0) handler.post(fadeTick);
    }

    void stopAtPosition(int targetMs, String label) {
        if (!ready || targetMs <= position()) return;
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(chapterStopTick);
        sleepDeadline = 0;
        fadeSleep = false;
        chapterStopMs = targetMs;
        chapterStopLabel = label;
        if (player != null) try { player.setVolume(1f, 1f); }
        catch (IllegalStateException ignored) { }
        if (playing) handler.post(chapterStopTick);
        notifyListener();
    }

    void saveNow() { saveCurrent(true, false); }

    void clear() {
        saveCurrent(true, false);
        releaseDecoder();
        recentPlaces.clear();
        uri = null;
        title = null;
        author = null;
        duration = 0;
        pendingSeek = 0;
        unavailable = false;
        completed = false;
        publishState();
    }

    private void fail() { fail("This audio could not be played. Check the file and try relinking it."); }

    private void recordCompletion(String completedUri) {
        if (completedUri == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONArray prior = new JSONArray(prefs.getString("completion_history", "[]"));
            JSONArray next = new JSONArray();
            JSONObject event = new JSONObject();
            event.put("uri", completedUri);
            event.put("at", System.currentTimeMillis());
            next.put(event);
            for (int i = 0; i < Math.min(99, prior.length()); i++) {
                JSONObject entry = prior.optJSONObject(i);
                if (entry != null && !completedUri.equals(entry.optString("uri"))) next.put(entry);
            }
            if (!prefs.edit().putString("completion_history", next.toString()).commit())
                return;
        } catch (JSONException ignored) { }
    }

    private void fail(String reason) {
        saveCurrent(true, false);
        releaseDecoder();
        unavailable = true;
        failureReason = reason;
        publishState();
    }

    private void releaseDecoder() { releaseDecoder(false); }

    private void releaseDecoder(boolean keepForeground) {
        if (playing) recordListeningTime();
        generation++;
        autoPlayAfterPrepare = false;
        handler.removeCallbacks(saveTick);
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(chapterStopTick);
        handler.removeCallbacks(repeatTick);
        handler.removeCallbacks(silenceTick);
        repeatA = repeatB = -1;
        silenceToken++;
        skipSilence = false;
        silenceSpans = Collections.emptyList();
        silenceScannedUntil = 0;
        silenceUnavailable = false;
        repeatSeeking = false;
        sleepDeadline = 0;
        fadeSleep = false;
        chapterStopMs = 0;
        chapterStopLabel = "end of chapter";
        playing = false;
        statsCheckpoint = 0;
        unregisterNoisyReceiver();
        unregisterRouteCallback();
        ready = false;
        duration = 0;
        if (player != null) {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                player.removeOnRoutingChangedListener(routingListener);
            if (voiceEffect != null) { voiceEffect.release(); voiceEffect = null; }
            try { player.reset(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
        if (!keepForeground) endForeground();
    }

    private void endForeground() {
        audioManager.abandonAudioFocusRequest(focusRequest);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyReceiverRegistered = true;
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        unregisterReceiver(noisyReceiver);
        noisyReceiverRegistered = false;
    }

    private static boolean isPrivateOutput(AudioDeviceInfo device) {
        if (device == null) return false;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return true;
            default:
                return false;
        }
    }

    private void registerRouteCallback() {
        if (routeCallbackRegistered) return;
        audioManager.registerAudioDeviceCallback(routeCallback, handler);
        routeCallbackRegistered = true;
    }

    private void unregisterRouteCallback() {
        privateOutputId = -1;
        if (!routeCallbackRegistered) return;
        audioManager.unregisterAudioDeviceCallback(routeCallback);
        routeCallbackRegistered = false;
    }

    private JSONObject nextAfterCompletion() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONArray entries = LibraryStore.get(this).readAll();
            JSONObject current = null;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item != null && uri.equals(item.optString("uri"))) { current = item; break; }
            }
            if (current != null && !current.optString("partGroup").trim().isEmpty()) {
                BigDecimal order = LibraryOrdering.parseOrder(current.optString("partOrder"));
                JSONObject nextPart = null;
                BigDecimal nextOrder = null;
                if (order != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject item = entries.optJSONObject(i);
                    if (item == null || !samePartGroup(current, item)) continue;
                    BigDecimal candidate = LibraryOrdering.parseOrder(item.optString("partOrder"));
                    if (candidate == null || candidate.compareTo(order) <= 0
                            || (nextOrder != null && candidate.compareTo(nextOrder) >= 0)) continue;
                    Uri document = Uri.parse(item.optString("uri"));
                    if (!LocalDocumentPolicy.accepts(document.getScheme(), document.getAuthority())) continue;
                    nextPart = item;
                    nextOrder = candidate;
                }
                if (nextPart != null && prefs.edit()
                        .putString("selected_uri", nextPart.optString("uri")).commit()) return nextPart;
            }
            JSONArray queued = new JSONArray(prefs.getString("queue_uris", "[]"));
            for (int i = 0; i < Math.min(queued.length(), 100); i++) {
                String queuedUri = queued.optString(i);
                if (queuedUri.equals(uri)) continue;
                Uri document = Uri.parse(queuedUri);
                if (!LocalDocumentPolicy.accepts(document.getScheme(), document.getAuthority())) continue;
                JSONObject next = null;
                for (int j = 0; j < entries.length(); j++) {
                    JSONObject item = entries.optJSONObject(j);
                    if (item != null && queuedUri.equals(item.optString("uri"))) { next = item; break; }
                }
                if (next == null) continue;
                JSONArray remaining = new JSONArray();
                for (int j = 0; j < queued.length(); j++) if (j != i) remaining.put(queued.optString(j));
                if (prefs.edit().putString("selected_uri", queuedUri)
                        .putString("queue_uris", remaining.toString()).commit()) return next;
                return null;
            }
        } catch (RuntimeException | JSONException ignored) { }
        return null;
    }

    private static boolean samePartGroup(JSONObject left, JSONObject right) {
        return !left.optString("uri").equals(right.optString("uri"))
                && left.optString("partGroup").equalsIgnoreCase(right.optString("partGroup"))
                && left.optString("author").equalsIgnoreCase(right.optString("author"))
                && left.optString("series").equalsIgnoreCase(right.optString("series"));
    }

    private void saveCurrent(boolean commit, boolean finished) {
        if (uri == null) return;
        try {
            LibraryStore.get(this).updateProgress(uri, finished ? 0 : position(),
                    duration, finished || completed);
        } catch (RuntimeException ignored) { }
    }

    void resetStatisticsCheckpoint() {
        statsCheckpoint = playing ? SystemClock.elapsedRealtime() : 0;
    }

    private void recordListeningTime() {
        long now = SystemClock.elapsedRealtime();
        if (!playing || statsCheckpoint <= 0) return;
        long elapsed = Math.min(300000L, Math.max(0, now - statsCheckpoint));
        statsCheckpoint = now;
        if (elapsed == 0) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("listening_stats_enabled", false)) return;
        long total = prefs.getLong("listening_ms", 0);
        prefs.edit().putLong("listening_ms", Math.min(Long.MAX_VALUE - elapsed, total) + elapsed).apply();
    }

    private void publishState() {
        if (session == null) return;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, PlaybackDisplay.title(privatePlayback, title))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, PlaybackDisplay.artist(privatePlayback, author))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
        int state = unavailable ? PlaybackState.STATE_ERROR : playing ? PlaybackState.STATE_PLAYING
                : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_CONNECTING;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions).setState(state, position(), playing ? speed : 0f).build());
        if (playing) startForeground(NOTIFICATION_ID, notification());
        AriosoWidget.update(this, title, playing, privatePlayback);
        notifyListener();
    }

    private Notification notification() {
        Intent toggle = new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE);
        PendingIntent control = PendingIntent.getService(this, 1, toggle,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent back = PendingIntent.getService(this, 3,
                new Intent(this, PlaybackService.class).setAction(ACTION_BACK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent forward = PendingIntent.getService(this, 4,
                new Intent(this, PlaybackService.class).setAction(ACTION_FORWARD),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action pauseAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause", control).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(app.arioso.audiobook.R.drawable.ic_arioso)
                .setContentTitle(PlaybackDisplay.title(privatePlayback, title))
                .setContentText(PlaybackDisplay.notificationText(privatePlayback, author))
                .setContentIntent(open).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_rew, "Back " + (backMs / 1000) + " seconds", back).build())
                .addAction(pauseAction)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_ff, "Forward " + (forwardMs / 1000) + " seconds", forward).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)).build();
    }

    private void notifyListener() { if (listener != null) listener.onPlaybackChanged(); }

    @Override public void onDestroy() {
        saveCurrent(true, false);
        releaseDecoder();
        if (session != null) { session.setActive(false); session.release(); session = null; }
        listener = null;
        handler.removeCallbacksAndMessages(null);
        silenceWorker.shutdownNow();
        super.onDestroy();
    }
}
