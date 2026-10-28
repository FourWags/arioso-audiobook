package app.arioso.audiobook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator integration check for the exported, opt-in car media browser. */
public final class BrowserSmokeInstrumentation extends Instrumentation {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        Context app = getTargetContext();
        SharedPreferences prefs = app.getSharedPreferences("arioso_library_v1", Context.MODE_PRIVATE);
        boolean original = prefs.getBoolean("auto_browse_enabled", false);
        boolean privatePlayback = prefs.getBoolean("private_playback", false);
        try {
            if (!prefs.edit().putBoolean("auto_browse_enabled", false)
                    .putBoolean("private_playback", false).commit()) throw new AssertionError("prefs");
            String disabled = connect(app, false);
            if (!"denied".equals(disabled)) throw new AssertionError("disabled=" + disabled);
            if (!prefs.edit().putBoolean("auto_browse_enabled", true).commit())
                throw new AssertionError("enable");
            String enabled = connect(app, true);
            if (!"4 root categories".equals(enabled))
                throw new AssertionError("enabled=" + enabled);
            String playback = playSyntheticBook(app);
            if (!"selected book through media session".equals(playback))
                throw new AssertionError("playback=" + playback);
            if (!prefs.edit().putBoolean("private_playback", true).commit())
                throw new AssertionError("private");
            String privateResult = connect(app, false);
            if (!"denied".equals(privateResult))
                throw new AssertionError("private=" + privateResult);
            result.putString("browser", "disabled and private denied; four categories; media selection passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("browser", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            prefs.edit().putBoolean("auto_browse_enabled", original)
                    .putBoolean("private_playback", privatePlayback).commit();
        }
    }

    private String connect(Context app, boolean expectRoot) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>("timeout");
        MediaBrowser[] browser = new MediaBrowser[1];
        main.post(() -> {
            browser[0] = new MediaBrowser(app,
                    new ComponentName(app, AriosoCarBrowserService.class),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            if (!expectRoot) {
                                status.set("unexpected connection"); done.countDown(); return;
                            }
                            browser[0].subscribe("arioso:root", new MediaBrowser.SubscriptionCallback() {
                                @Override public void onChildrenLoaded(String parentId,
                                        List<MediaBrowser.MediaItem> children) {
                                    status.set(children.size() + " root categories"); done.countDown();
                                }
                                @Override public void onError(String parentId) {
                                    status.set("browse error"); done.countDown();
                                }
                            });
                        }
                        @Override public void onConnectionFailed() {
                            status.set("denied"); done.countDown();
                        }
                        @Override public void onConnectionSuspended() {
                            status.set("suspended"); done.countDown();
                        }
                    }, null);
            browser[0].connect();
        });
        done.await(8, TimeUnit.SECONDS);
        main.post(() -> {
            if (browser[0] != null) browser[0].disconnect();
        });
        return status.get();
    }

    private String playSyntheticBook(Context app) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>("timeout");
        MediaBrowser[] browser = new MediaBrowser[1];
        main.post(() -> {
            browser[0] = new MediaBrowser(app,
                    new ComponentName(app, AriosoCarBrowserService.class),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            browser[0].subscribe("books", new MediaBrowser.SubscriptionCallback() {
                                @Override public void onChildrenLoaded(String parentId,
                                        List<MediaBrowser.MediaItem> children) {
                                    String id = null;
                                    for (MediaBrowser.MediaItem child : children)
                                        if (String.valueOf(child.getDescription().getTitle())
                                                .contains("Synthetic-Tone-Silence"))
                                            id = child.getMediaId();
                                    if (id == null) { status.set("fixture missing"); done.countDown(); return; }
                                    String selectedId = id;
                                    new MediaController(app, browser[0].getSessionToken())
                                            .getTransportControls().playFromMediaId(id, null);
                                    main.postDelayed(() -> {
                                        String selected = app.getSharedPreferences("arioso_library_v1",
                                                Context.MODE_PRIVATE).getString("selected_uri", "");
                                        status.set(selectedId.equals(CarMediaIds.book(selected))
                                                ? "selected book through media session" : "selection failed");
                                        done.countDown();
                                    }, 1500);
                                }
                                @Override public void onError(String parentId) {
                                    status.set("browse error"); done.countDown();
                                }
                            });
                        }
                        @Override public void onConnectionFailed() {
                            status.set("connection failed"); done.countDown();
                        }
                    }, null);
            browser[0].connect();
        });
        done.await(8, TimeUnit.SECONDS);
        main.post(() -> { if (browser[0] != null) browser[0].disconnect(); });
        return status.get();
    }
}
