package app.arioso.audiobook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;

/** Disposable-emulator regression: startup/recreation must not replace per-book speed with a global default. */
public final class AppearanceSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context app = getTargetContext();
        Activity activity = null;
        SharedPreferences prefs = app.getSharedPreferences("arioso_library_v1", Context.MODE_PRIVATE);
        boolean seeded = false;
        try {
            if (!app.getPackageName().endsWith(".qa") || !("ranchu".equals(android.os.Build.HARDWARE) || "goldfish".equals(android.os.Build.HARDWARE)))
                throw new AssertionError("Disposable .qa emulator only");
            if (LibraryStore.get(app).readAll().length() != 0 || !prefs.getAll().isEmpty())
                throw new AssertionError("Requires a fresh empty QA install; existing test data preserved");
            String uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsynthetic.wav";
            JSONObject book = new JSONObject().put("uri", uri).put("title", "Synthetic speed regression")
                    .put("position", 12345).put("duration", 120000).put("playbackSpeed", 1.25)
                    .put("note", "Synthetic note");
            if (!LibraryStore.get(app).replaceAll(Collections.singletonList(book))) throw new AssertionError("seed");
            seeded = true;
            if (!prefs.edit().putString("selected_uri", uri).putFloat("speed_value", 1f).commit())
                throw new AssertionError("seed prefs");
            activity = startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            assertBook(app);
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            Activity current = activity;
            runOnMainSync(current::recreate);
            activity = monitor.waitForActivityWithTimeout(5000);
            removeMonitor(monitor);
            if (activity == null) throw new AssertionError("recreation timeout");
            waitForIdleSync();
            assertBook(app);
            result.putString("appearance", "startup and recreation retain 1.25x speed, 12345ms place and note against 1x global default");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("appearance", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) { Activity current = activity; runOnMainSync(current::finish); }
            if (seeded) {
                LibraryStore.get(app).replaceAll(Collections.emptyList());
                prefs.edit().clear().commit();
            }
        }
    }
    private static void assertBook(Context app) throws Exception {
        JSONArray books = LibraryStore.get(app).readAll();
        if (books.length() != 1) throw new AssertionError("book count");
        JSONObject book = books.getJSONObject(0);
        if (Math.abs(book.getDouble("playbackSpeed") - 1.25) > .001)
            throw new AssertionError("book speed changed during startup/recreation");
        if (book.getInt("position") != 12345 || !"Synthetic note".equals(book.getString("note")))
            throw new AssertionError("saved place/note changed");
    }
}
