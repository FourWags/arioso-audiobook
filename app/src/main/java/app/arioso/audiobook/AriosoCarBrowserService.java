package app.arioso.audiobook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.IBinder;
import android.service.media.MediaBrowserService;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Opt-in local media tree for Android Auto. Playback stays in PlaybackService. */
public final class AriosoCarBrowserService extends MediaBrowserService {
    private static final String ROOT = "arioso:root";
    private static final String PREFS = "arioso_library_v1";
    private boolean bound;
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof PlaybackService.LocalBinder)) return;
            PlaybackService service = ((PlaybackService.LocalBinder) binder).getService();
            if (service.mediaToken() != null) setSessionToken(service.mediaToken());
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
    };

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_browse_enabled", false)
                || prefs.getBoolean("private_playback", false)
                || !allowedCaller(clientPackageName, clientUid)) return null;
        // Do not start the playback service for an untrusted or disabled browser client.
        if (!bound) bound = bindService(new Intent(this, PlaybackService.class), playbackConnection,
                Context.BIND_AUTO_CREATE);
        return new BrowserRoot(ROOT, null);
    }

    private boolean allowedCaller(String packageName, int uid) {
        if (packageName == null || packageName.isEmpty() || uid < 0) return false;
        String[] packages = getPackageManager().getPackagesForUid(uid);
        boolean ownsName = false;
        if (packages != null) for (String candidate : packages)
            if (packageName.equals(candidate)) { ownsName = true; break; }
        if (!ownsName) return false;
        if (packageName.equals(getPackageName())) return true;
        if (!packageName.equals("com.google.android.projection.gearhead")) return false;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            return system || getPackageManager().checkSignatures(packageName,
                    "com.google.android.gms") == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException denied) { return false; }
    }

    @Override public void onLoadChildren(String parentId,
                                         Result<List<MediaBrowser.MediaItem>> result) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_browse_enabled", false)
                || prefs.getBoolean("private_playback", false)) {
            result.sendResult(Collections.emptyList()); return;
        }
        try { result.sendResult(children(parentId)); }
        catch (RuntimeException ignored) { result.sendResult(Collections.emptyList()); }
    }

    private List<MediaBrowser.MediaItem> children(String parentId) {
        List<MediaBrowser.MediaItem> out = new ArrayList<>();
        if (ROOT.equals(parentId)) {
            out.add(group("continue", "Continue listening", "Unfinished books"));
            out.add(group("authors", "Authors", "Browse by author"));
            out.add(group("series", "Series", "Browse by series"));
            out.add(group("books", "Books", "All local titles"));
            return out;
        }
        JSONArray saved = LibraryStore.get(this).readAll();
        List<JSONObject> books = new ArrayList<>();
        for (int i = 0; i < Math.min(saved.length(), 500); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item == null || item.optBoolean("archived")) continue;
            Uri uri = Uri.parse(item.optString("uri"));
            if (LocalDocumentPolicy.accepts(uri.getScheme(), uri.getAuthority())) books.add(item);
        }
        books.sort((a, b) -> LibraryOrdering.compare(
                a.optString("authorSort", a.optString("author")),
                a.optString("series"), a.optString("seriesNumber"), title(a), a.optString("uri"),
                b.optString("authorSort", b.optString("author")),
                b.optString("series"), b.optString("seriesNumber"), title(b), b.optString("uri")));
        if ("continue".equals(parentId)) {
            books.sort((a, b) -> Long.compare(b.optLong("lastPlayedAt"), a.optLong("lastPlayedAt")));
            for (JSONObject item : books) if (!item.optBoolean("finished")
                    && item.optLong("position") > 0) out.add(book(item));
        } else if ("books".equals(parentId)) {
            for (JSONObject item : books) out.add(book(item));
        } else if ("authors".equals(parentId)) {
            Map<String, String> authors = new LinkedHashMap<>();
            for (JSONObject item : books) {
                String author = author(item);
                authors.putIfAbsent(author.toLowerCase(Locale.ROOT), author);
            }
            for (String author : authors.values())
                out.add(group("author:" + Uri.encode(author), author, "Author"));
        } else if ("series".equals(parentId)) {
            Map<String, String[]> series = new LinkedHashMap<>();
            for (JSONObject item : books) {
                String name = item.optString("series", "").trim();
                if (name.isEmpty() || name.equalsIgnoreCase("Standalone")) continue;
                String author = author(item);
                series.putIfAbsent((author + "\u0000" + name).toLowerCase(Locale.ROOT),
                        new String[]{author, name});
            }
            for (String[] item : series.values()) out.add(group(
                    "series:" + Uri.encode(item[0] + "\u0000" + item[1]), item[1], item[0]));
        } else if (parentId.startsWith("author:")) {
            String selected = Uri.decode(parentId.substring(7));
            for (JSONObject item : books) if (selected.equalsIgnoreCase(author(item)))
                out.add(book(item));
        } else if (parentId.startsWith("series:")) {
            String[] chosen = Uri.decode(parentId.substring(7)).split("\u0000", 2);
            if (chosen.length == 2) for (JSONObject item : books)
                if (chosen[0].equalsIgnoreCase(author(item))
                        && chosen[1].equalsIgnoreCase(item.optString("series"))) out.add(book(item));
        }
        return out;
    }

    private static String title(JSONObject item) {
        String title = item.optString("title", "").trim();
        return title.isEmpty() ? item.optString("name", "Book") : title;
    }

    private static String author(JSONObject item) {
        String author = item.optString("author", "").trim();
        return author.isEmpty() ? "Unknown author" : author;
    }

    private MediaBrowser.MediaItem group(String id, String title, String subtitle) {
        return new MediaBrowser.MediaItem(new MediaDescription.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
                MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowser.MediaItem book(JSONObject item) {
        String subtitle = author(item);
        String series = item.optString("series", "").trim();
        if (!series.isEmpty() && !series.equalsIgnoreCase("Standalone"))
            subtitle += " · " + series + " " + item.optString("seriesNumber", "");
        return new MediaBrowser.MediaItem(new MediaDescription.Builder()
                .setMediaId(CarMediaIds.book(item.optString("uri")))
                .setTitle(title(item)).setSubtitle(subtitle).build(),
                MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    @Override public void onDestroy() {
        if (bound) unbindService(playbackConnection);
        super.onDestroy();
    }
}
