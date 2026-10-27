package app.dart.audiobook;

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
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;

/** Owns one local decoder, the media session, sleep timer, and persisted playback state. */
public final class PlaybackService extends Service {
    interface Listener { void onPlaybackChanged(); }
    final class LocalBinder extends Binder { PlaybackService getService() { return PlaybackService.this; } }

    private static final String PREFS = "dart_library_v1";
    private static final String CHANNEL = "dart_playback";
    private static final int NOTIFICATION_ID = 11;
    private static final String ACTION_TOGGLE = "app.dart.audiobook.TOGGLE";
    private static final String ACTION_BACK = "app.dart.audiobook.BACK_15";
    private static final String ACTION_FORWARD = "app.dart.audiobook.FORWARD_30";
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean noisyReceiverRegistered;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };
    private Listener listener;
    private String uri;
    private String title;
    private String author;
    private int duration;
    private int pendingSeek;
    private boolean ready;
    private boolean playing;
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
    private String failureReason = "";
    private int backMs = 15000;
    private int forwardMs = 30000;
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            saveCurrent(false, false);
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
        session = new MediaSession(this, "Dart");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
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
    void clearListener(Listener target) { if (listener == target) listener = null; }
    String currentUri() { return uri; }
    boolean isReady() { return ready; }
    boolean isPlaying() { return playing; }
    boolean isUnavailable() { return unavailable; }
    String failureReason() { return failureReason; }
    boolean isCompleted() { return completed; }
    int duration() { return duration; }
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
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            next.setDataSource(this, Uri.parse(uri));
            next.setOnPreparedListener(mp -> {
                if (token != generation || mp != player) return;
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
                playing = false;
                unregisterNoisyReceiver();
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
                    loadInternal(followingBook.optString("uri"), followingBook.optString("title"),
                            followingBook.optString("author"), followingBook.optInt("position"),
                            followingBook.optBoolean("finished"), true, true);
                }
            });
            next.setOnErrorListener((mp, what, extra) -> {
                if (token == generation && mp == player) fail("This audio could not be decoded. Check the file or try another format.");
                return true;
            });
            next.prepareAsync();
        } catch (SecurityException e) { fail("Dart lost access to this file. Relink it from on-device storage."); }
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
            startForegroundService(new Intent(this, PlaybackService.class));
            startForeground(NOTIFICATION_ID, notification());
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed).setPitch(pitch));
            player.start();
            playing = true;
            registerNoisyReceiver();
            saveCurrent(false, false);
            handler.removeCallbacks(saveTick);
            handler.postDelayed(saveTick, 5000);
            if (chapterStopMs > 0) handler.post(chapterStopTick);
            if (fadeSleep && sleepDeadline > 0) handler.post(fadeTick);
            if (ListeningTools.validRepeatWindow(repeatA, repeatB, duration)) handler.post(repeatTick);
            publishState();
        } catch (RuntimeException e) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            fail();
        }
    }

    void pause() {
        autoPlayAfterPrepare = false;
        if (player != null && playing) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
        playing = false;
        unregisterNoisyReceiver();
        handler.removeCallbacks(saveTick);
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(chapterStopTick);
        handler.removeCallbacks(repeatTick);
        sleepDeadline = 0;
        chapterStopMs = 0;
        chapterStopLabel = "end of chapter";
        fadeSleep = false;
        if (player != null) try { player.setVolume(1f, 1f); }
        catch (IllegalStateException ignored) { }
        saveCurrent(true, false);
        endForeground();
        publishState();
    }

    void seekTo(long requested) {
        if (!ready || player == null) return;
        int target = ListeningTools.boundedPosition(requested, duration);
        pendingSeek = target;
        try {
            // A chapter or bookmark jump from a finished book starts a new listening position.
            completed = false;
            player.seekTo(target);
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
        generation++;
        autoPlayAfterPrepare = false;
        handler.removeCallbacks(saveTick);
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(chapterStopTick);
        handler.removeCallbacks(repeatTick);
        repeatA = repeatB = -1;
        repeatSeeking = false;
        sleepDeadline = 0;
        fadeSleep = false;
        chapterStopMs = 0;
        chapterStopLabel = "end of chapter";
        playing = false;
        unregisterNoisyReceiver();
        ready = false;
        duration = 0;
        if (player != null) {
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

    private void publishState() {
        if (session == null) return;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title == null ? "Dart" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, author == null ? "" : author)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
        int state = unavailable ? PlaybackState.STATE_ERROR : playing ? PlaybackState.STATE_PLAYING
                : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_CONNECTING;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions).setState(state, position(), playing ? speed : 0f).build());
        if (playing) startForeground(NOTIFICATION_ID, notification());
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
                .setSmallIcon(app.dart.audiobook.R.drawable.ic_dart)
                .setContentTitle(title == null ? "Dart" : title)
                .setContentText(author == null || author.isEmpty() ? "Audiobook" : author)
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
        super.onDestroy();
    }
}
