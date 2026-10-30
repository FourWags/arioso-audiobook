package app.arioso.audiobook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Synthetic, disposable-emulator check that Home and Authors show different destinations. */
public final class RailNavigationInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context app = getTargetContext();
        SharedPreferences prefs = app.getSharedPreferences("arioso_library_v1", Context.MODE_PRIVATE);
        Activity activity = null;
        boolean seeded = false;
        try {
            if (!app.getPackageName().endsWith(".qa") || !("ranchu".equals(android.os.Build.HARDWARE)
                    || "goldfish".equals(android.os.Build.HARDWARE)))
                throw new AssertionError("Disposable .qa emulator only");
            if (LibraryStore.get(app).readAll().length() != 0 || !prefs.getAll().isEmpty())
                throw new AssertionError("Requires a fresh empty QA install");
            JSONObject older = new JSONObject().put("uri", "content://synthetic/older")
                    .put("title", "Older story").put("author", "Ada Author")
                    .put("importedAt", 1000L);
            JSONObject newer = new JSONObject().put("uri", "content://synthetic/newer")
                    .put("title", "Newer story").put("author", "Zoe Author")
                    .put("importedAt", 2000L);
            JSONObject seriesBook = new JSONObject().put("uri", "content://synthetic/series")
                    .put("title", "Series opener").put("author", "Ada Author")
                    .put("series", "Ada Chronicles").put("seriesNumber", "1")
                    .put("importedAt", 1500L);
            if (!LibraryStore.get(app).replaceAll(Arrays.asList(older, newer, seriesBook)))
                throw new AssertionError("Could not seed synthetic books");
            seeded = true;
            prefs.edit().putString("library_layout", "rail").commit();
            activity = startActivitySync(new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            MainActivity main = (MainActivity) activity;
            if (!(boolean) field(main, "railEnabled")) throw new AssertionError("Rail unavailable");
            View home = (View) field(main, "railHomeButton");
            View[] tabs = (View[]) field(main, "railTabButtons");
            runOnMainSync(home::performClick);
            waitForIdleSync();
            assertHeading(main, "Your library");
            assertFirst(main, "Newer story");
            if (((View) field(main, "recentBooksHeading")).getVisibility() != View.VISIBLE)
                throw new AssertionError("Recent books heading hidden");
            capture(app, "rail-home.png");
            runOnMainSync(tabs[0]::performClick);
            waitForIdleSync();
            assertHeading(main, "Authors");
            assertFirst(main, "Ada Author");
            if (((View) field(main, "recentBooksHeading")).getVisibility() != View.GONE)
                throw new AssertionError("Recent heading still visible on Authors");
            if (!"Authors".contentEquals(tabs[0].getContentDescription()))
                throw new AssertionError("Author rail label missing");
            capture(app, "rail-authors.png");
            openEntry(main, firstItem(main));
            waitForIdleSync();
            assertHeading(main, "Ada Author");
            if (!tabs[0].isSelected() || tabs[1].isSelected())
                throw new AssertionError("Author page highlights the wrong rail destination");
            if (!((TextView) field(main, "libraryCount")).getText().toString()
                    .startsWith("2 books by Ada Author"))
                throw new AssertionError("Author book count still shows the full library");
            assertFirst(main, "Older story");
            List<?> authorItems = (List<?>) field(main, "shelfItems");
            if (authorItems.size() != 2 || field(authorItems.get(0), "book") == null
                    || field(authorItems.get(1), "book") != null
                    || !"Ada Chronicles".equals(field(authorItems.get(1), "label")))
                throw new AssertionError("Standalone book and real series were not separate destinations");
            capture(app, "rail-author-detail.png");
            openEntry(main, authorItems.get(1));
            waitForIdleSync();
            if (!"Ada Chronicles".equals(field(main, "selectedSeries"))
                    || (int) field(main, "libraryTab") != 2)
                throw new AssertionError("Real series did not open its books");
            runOnMainSync(tabs[0]::performClick);
            waitForIdleSync();
            openEntry(main, firstItem(main));
            waitForIdleSync();
            openEntry(main, firstItem(main));
            waitForIdleSync();
            if (!(boolean) field(main, "showingPlayer") || field(main, "selectedSeries") != null)
                throw new AssertionError("Standalone book did not open directly from the author page");
            result.putString("rail", "Home recent books, alphabetical Authors, direct standalone book, and series navigation passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("rail", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) { Activity current = activity; runOnMainSync(current::finish); }
            if (seeded) {
                LibraryStore.get(app).replaceAll(Collections.emptyList());
                prefs.edit().clear().commit();
            }
        }
    }

    private void capture(Context app, String name) throws Exception {
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("Screenshot unavailable");
        File output = new File(app.getExternalCacheDir(), name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
                throw new AssertionError("Screenshot failed");
        } finally { screenshot.recycle(); }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object firstItem(MainActivity activity) throws Exception {
        List<?> items = (List<?>) field(activity, "shelfItems");
        if (items.isEmpty()) throw new AssertionError("No shelf items");
        return items.get(0);
    }

    private void openEntry(MainActivity activity, Object entry) throws Exception {
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("openShelfEntry", entry.getClass());
        method.setAccessible(true);
        runOnMainSync(() -> {
            try { method.invoke(activity, entry); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private static void assertHeading(MainActivity activity, String expected) throws Exception {
        if (!expected.contentEquals(((TextView) field(activity, "libraryHeading")).getText()))
            throw new AssertionError("Expected heading " + expected);
    }

    private static void assertFirst(MainActivity activity, String expected) throws Exception {
        List<?> items = (List<?>) field(activity, "shelfItems");
        if (items.isEmpty()) throw new AssertionError("No shelf items");
        Field label = items.get(0).getClass().getDeclaredField("label");
        label.setAccessible(true);
        if (!expected.equals(label.get(items.get(0))))
            throw new AssertionError("Expected first item " + expected + ", got " + label.get(items.get(0)));
    }
}
