package app.arioso.audiobook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.support.v4.media.MediaBrowserCompat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import androidx.media.utils.MediaConstants;

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
        try {
            if (!app.getPackageName().endsWith(".qa") || !("ranchu".equals(android.os.Build.HARDWARE)
                    || "goldfish".equals(android.os.Build.HARDWARE)))
                throw new AssertionError("Disposable QA emulator only");
            if (LibraryStore.get(app).readAll().length() != 0 || !prefs.getAll().isEmpty())
                throw new AssertionError("Fresh empty QA app required");
            String uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FAriosoAutoTone.wav";
            String second = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FArc1.wav";
            String third = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FArc2.wav";
            String fourth = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FZoe.wav";
            if (!LibraryStore.get(app).replaceAll(Arrays.asList(
                    new JSONObject().put("uri", uri).put("title", "Synthetic-Tone-Silence")
                            .put("author", "Ada Author"),
                    new JSONObject().put("uri", second).put("title", "Arc One")
                            .put("author", "Ada Author").put("series", "Arc")
                            .put("seriesNumber", "1"),
                    new JSONObject().put("uri", third).put("title", "Arc Two")
                            .put("author", "Ada Author").put("series", "Arc")
                            .put("seriesNumber", "2"),
                    new JSONObject().put("uri", fourth).put("title", "Zoe Walk")
                            .put("author", "Zoe Author"),
                    new JSONObject().put("uri", "content://com.android.externalstorage.documents/document/primary%3AHidden.wav")
                            .put("title", "Hidden title").put("author", "Private Author")
                            .put("archived", true)))) throw new AssertionError("seed");
            if (!prefs.edit().putBoolean("auto_browse_enabled", false)
                    .putBoolean("private_playback", false).commit()) throw new AssertionError("prefs");
            String disabled = connect(app, false);
            if (!"denied".equals(disabled)) throw new AssertionError("disabled=" + disabled);
            if (!prefs.edit().putBoolean("auto_browse_enabled", true).commit())
                throw new AssertionError("enable");
            String enabled = connect(app, true);
            if (!"4 root categories".equals(enabled))
                throw new AssertionError("enabled=" + enabled);
            browseHierarchy(app);
            search(app);
            String playback = playSyntheticBook(app);
            if (!"selection and voice search through media session".equals(playback))
                throw new AssertionError("playback=" + playback);
            largePages(app);
            if (!prefs.edit().putBoolean("private_playback", true).commit())
                throw new AssertionError("private");
            String privateResult = connect(app, false);
            if (!"denied".equals(privateResult))
                throw new AssertionError("private=" + privateResult);
            result.putString("browser", "disabled/private denied; root limits, author/series order, standalone, large-library groups, search and media commands passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("browser", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            LibraryStore.get(app).replaceAll(Collections.emptyList());
            prefs.edit().clear().commit();
        }
    }

    private void browseHierarchy(Context app) throws InterruptedException {
        List<MediaBrowser.MediaItem> root = children(app, null, null);
        if (root == null || root.size() != 4 || !"Continue".contentEquals(root.get(0).getDescription().getTitle()))
            throw new AssertionError("root hierarchy");
        Bundle limited = new Bundle();
        limited.putInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 2);
        List<MediaBrowser.MediaItem> shortRoot = children(app, null, limited);
        if (shortRoot == null || shortRoot.size() != 2
                || !"Library".contentEquals(shortRoot.get(1).getDescription().getTitle()))
            throw new AssertionError("root limit ignored");
        limited.putInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 1);
        List<MediaBrowser.MediaItem> singleRoot = children(app, null, limited);
        if (singleRoot == null || singleRoot.size() != 1
                || !"Library".contentEquals(singleRoot.get(0).getDescription().getTitle())
                || children(app, singleRoot.get(0).getMediaId(), null).size() != 4)
            throw new AssertionError("single-tab library missing");
        List<MediaBrowser.MediaItem> authors = children(app, "authors", null);
        if (authors == null || authors.size() != 2
                || !"Ada Author".contentEquals(authors.get(0).getDescription().getTitle()))
            throw new AssertionError("author index");
        List<MediaBrowser.MediaItem> allBooks = children(app, "books", null);
        if (allBooks == null || allBooks.size() != 4)
            throw new AssertionError("archived book leaked or book missing");
        List<MediaBrowser.MediaItem> ada = children(app, authors.get(0).getMediaId(), null);
        if (ada == null || ada.size() != 2 || !ada.get(0).isPlayable()
                || !ada.get(1).isBrowsable()
                || !"Arc".contentEquals(ada.get(1).getDescription().getTitle()))
            throw new AssertionError("standalone and series grouping");
        List<MediaBrowser.MediaItem> arc = children(app, ada.get(1).getMediaId(), null);
        if (arc == null || arc.size() != 2
                || !"Arc One".contentEquals(arc.get(0).getDescription().getTitle())
                || !"Arc Two".contentEquals(arc.get(1).getDescription().getTitle()))
            throw new AssertionError("series order");
    }

    private List<MediaBrowser.MediaItem> children(Context app, String parent, Bundle hints)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<MediaBrowser.MediaItem>> items = new AtomicReference<>();
        MediaBrowser[] browser = new MediaBrowser[1];
        main.post(() -> {
            browser[0] = new MediaBrowser(app, new ComponentName(app, AriosoCarBrowserService.class),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            browser[0].subscribe(parent == null ? browser[0].getRoot() : parent,
                                    new MediaBrowser.SubscriptionCallback() {
                                        @Override public void onChildrenLoaded(String id,
                                                List<MediaBrowser.MediaItem> children) {
                                            items.set(children); done.countDown();
                                        }
                                        @Override public void onError(String id) { done.countDown(); }
                                    });
                        }
                        @Override public void onConnectionFailed() { done.countDown(); }
                    }, hints);
            browser[0].connect();
        });
        done.await(8, TimeUnit.SECONDS);
        main.post(() -> { if (browser[0] != null) browser[0].disconnect(); });
        return items.get();
    }

    private void largePages(Context app) throws Exception {
        java.util.ArrayList<JSONObject> many = new java.util.ArrayList<>();
        for (int i = 0; i < 95; i++) many.add(new JSONObject()
                .put("uri", "content://com.android.externalstorage.documents/document/primary%3DAuto"
                        + String.format(java.util.Locale.ROOT, "%03d", i) + ".wav")
                .put("title", String.format(java.util.Locale.ROOT, "Synthetic %03d", i))
                .put("author", "Synthetic Author"));
        if (!LibraryStore.get(app).replaceAll(many)) throw new AssertionError("large seed");
        List<MediaBrowser.MediaItem> pages = children(app, "books", null);
        if (pages == null || pages.size() != 3 || !pages.get(0).isBrowsable())
            throw new AssertionError("large library grouping");
        int total = 0;
        for (int i = 0; i < pages.size(); i++) {
            List<MediaBrowser.MediaItem> page = children(app, pages.get(i).getMediaId(), null);
            if (page == null || page.size() != (i == 2 ? 15 : 40))
                throw new AssertionError("large library page " + i);
            total += page.size();
        }
        if (total != 95) throw new AssertionError("lost books in browse tree");
    }

    private void search(Context app) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Integer> count = new AtomicReference<>(-1);
        MediaBrowserCompat[] browser = new MediaBrowserCompat[1];
        main.post(() -> {
            browser[0] = new MediaBrowserCompat(app,
                    new ComponentName(app, AriosoCarBrowserService.class),
                    new MediaBrowserCompat.ConnectionCallback() {
                        @Override public void onConnected() {
                            browser[0].search("Arc", null, new MediaBrowserCompat.SearchCallback() {
                                @Override public void onSearchResult(String query, Bundle extras,
                                        List<MediaBrowserCompat.MediaItem> items) {
                                    count.set(items == null ? -1 : items.size()); done.countDown();
                                }
                                @Override public void onError(String query, Bundle extras) {
                                    done.countDown();
                                }
                            });
                        }
                        @Override public void onConnectionFailed() { done.countDown(); }
                    }, null);
            browser[0].connect();
        });
        done.await(8, TimeUnit.SECONDS);
        main.post(() -> { if (browser[0] != null) browser[0].disconnect(); });
        if (count.get() != 2) throw new AssertionError("car search=" + count.get());
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
                            MediaController controller = new MediaController(app,
                                    browser[0].getSessionToken());
                            if (controller.getPlaybackState() == null
                                    || controller.getPlaybackState().getState()
                                    != android.media.session.PlaybackState.STATE_STOPPED) {
                                status.set("car connection started playback or lacks initial state");
                                done.countDown(); return;
                            }
                            browser[0].subscribe(browser[0].getRoot(), new MediaBrowser.SubscriptionCallback() {
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
                                        if (!selectedId.equals(CarMediaIds.book(selected))) {
                                            status.set("selection failed"); done.countDown(); return;
                                        }
                                        MediaController controller = new MediaController(app,
                                                browser[0].getSessionToken());
                                        if (controller.getPlaybackState() == null
                                                || (controller.getPlaybackState().getActions()
                                                & android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH) == 0) {
                                            status.set("search action missing"); done.countDown(); return;
                                        }
                                        controller.getTransportControls().playFromSearch("Zoe Author", null);
                                        main.postDelayed(() -> {
                                            String after = app.getSharedPreferences("arioso_library_v1",
                                                    Context.MODE_PRIVATE).getString("selected_uri", "");
                                            status.set(!after.equals(selected) && after.endsWith("Zoe.wav")
                                                    ? "selection and voice search through media session"
                                                    : "voice search failed");
                                            done.countDown();
                                        }, 600);
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
