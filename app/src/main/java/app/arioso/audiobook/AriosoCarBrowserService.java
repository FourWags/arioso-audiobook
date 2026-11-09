package app.arioso.audiobook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.utils.MediaConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Opt-in, local media tree for Android Auto. Playback remains in PlaybackService. */
public final class AriosoCarBrowserService extends MediaBrowserServiceCompat {
    private static final String ROOT = "arioso:root";
    private static final String PREFS = "arioso_library_v1";
    private static final int PAGE_SIZE = 40;
    // Bound requests so a malfunctioning car client cannot queue unlimited library reads.
    private final ThreadPoolExecutor browseWorker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
    private boolean bound;
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof PlaybackService.LocalBinder)) return;
            PlaybackService service = ((PlaybackService.LocalBinder) binder).getService();
            if (service.mediaToken() != null)
                setSessionToken(MediaSessionCompat.Token.fromToken(service.mediaToken()));
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
    };

    private boolean enabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean("auto_browse_enabled", false)
                && !prefs.getBoolean("private_playback", false);
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid,
                                            Bundle rootHints) {
        if (!enabled() || !allowedCaller(clientPackageName, clientUid)) return null;
        if (!bound) bound = bindService(new Intent(this, PlaybackService.class),
                playbackConnection, Context.BIND_AUTO_CREATE);
        int limit = rootHints == null ? 4 : rootHints.getInt(
                MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 4);
        limit = Math.max(1, Math.min(4, limit));
        Bundle extras = new Bundle();
        extras.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true);
        return new BrowserRoot(ROOT + ":" + limit, extras);
    }

    private boolean allowedCaller(String packageName, int uid) {
        if (packageName == null || packageName.isEmpty() || uid < 0) return false;
        String[] packages = getPackageManager().getPackagesForUid(uid);
        boolean ownsName = false;
        if (packages != null) for (String candidate : packages)
            if (packageName.equals(candidate)) { ownsName = true; break; }
        if (!ownsName) return false;
        if (packageName.equals(getPackageName())) return true;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            // AAOS callers vary by car; Android's documented policy is to trust system apps.
            if ((info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return true;
            // Some Android Auto / Assistant builds are updated outside the system partition.
            boolean googleCarOrAssistant = packageName.equals("com.google.android.projection.gearhead")
                    || packageName.equals("com.google.android.googlequicksearchbox");
            return googleCarOrAssistant && getPackageManager().checkSignatures(packageName,
                    "com.google.android.gms") == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException denied) { return false; }
    }

    @Override public void onLoadChildren(String parentId,
            Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        try {
            browseWorker.execute(() -> {
                List<MediaBrowserCompat.MediaItem> items = Collections.emptyList();
                if (enabled()) try { items = children(parentId); }
                catch (RuntimeException ignored) { /* A corrupt store must not expose partial data. */ }
                result.sendResult(items);
            });
        } catch (RuntimeException rejected) { result.sendResult(Collections.emptyList()); }
    }

    @Override public void onSearch(String query, Bundle extras,
            Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        try {
            browseWorker.execute(() -> {
                List<MediaBrowserCompat.MediaItem> items = Collections.emptyList();
                if (enabled()) try { items = search(query); }
                catch (RuntimeException ignored) { }
                result.sendResult(items);
            });
        } catch (RuntimeException rejected) { result.sendResult(Collections.emptyList()); }
    }

    private List<MediaBrowserCompat.MediaItem> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            List<MediaBrowserCompat.MediaItem> recent = rawChildren("continue");
            return new ArrayList<>(recent.subList(0, Math.min(PAGE_SIZE, recent.size())));
        }
        if (query.length() > 128) return Collections.emptyList();
        List<JSONObject> matches = books();
        matches.removeIf(item -> CarSearch.score(query, title(item), author(item),
                item.optString("series")) <= 0);
        matches.sort((a, b) -> {
            int score = Integer.compare(CarSearch.score(query, title(b), author(b),
                    b.optString("series")), CarSearch.score(query, title(a), author(a),
                    a.optString("series")));
            return score != 0 ? score : Long.compare(b.optLong("lastPlayedAt"),
                    a.optLong("lastPlayedAt"));
        });
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        for (int i = 0; i < Math.min(matches.size(), PAGE_SIZE); i++) out.add(book(matches.get(i)));
        return out;
    }

    private List<MediaBrowserCompat.MediaItem> children(String parentId) {
        if (parentId == null) return Collections.emptyList();
        if (parentId.startsWith("page:")) {
            int last = parentId.lastIndexOf(':');
            if (last < 5) return Collections.emptyList();
            int start;
            try { start = Integer.parseInt(parentId.substring(last + 1)); }
            catch (NumberFormatException invalid) { return Collections.emptyList(); }
            if (start < 0 || start % PAGE_SIZE != 0) return Collections.emptyList();
            List<MediaBrowserCompat.MediaItem> full = rawChildren(parentId.substring(5, last));
            if (start >= full.size()) return Collections.emptyList();
            return new ArrayList<>(full.subList(start, Math.min(start + PAGE_SIZE, full.size())));
        }
        List<MediaBrowserCompat.MediaItem> full = rawChildren(parentId);
        if (full.size() <= PAGE_SIZE) return full;
        List<MediaBrowserCompat.MediaItem> pages = new ArrayList<>();
        for (int start = 0; start < full.size(); start += PAGE_SIZE)
            pages.add(group("page:" + parentId + ":" + start,
                    (start + 1) + "–" + Math.min(start + PAGE_SIZE, full.size()),
                    full.size() + " entries", R.drawable.rail_books));
        return pages;
    }

    private List<MediaBrowserCompat.MediaItem> rawChildren(String parentId) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        if (parentId.startsWith(ROOT + ":")) {
            int limit;
            try { limit = Integer.parseInt(parentId.substring((ROOT + ":").length())); }
            catch (NumberFormatException invalid) { return out; }
            if (limit < 1 || limit > 4) return out;
            if (limit == 1) {
                out.add(group("library:all", "Library", "Continue, authors, series and books",
                        R.drawable.rail_books));
                return out;
            }
            out.add(group("continue", "Continue", "Unfinished books", R.drawable.rail_history));
            if (limit >= 4) {
                out.add(group("authors", "Authors", "Browse by author", R.drawable.rail_authors));
                out.add(group("series", "Series", "Browse by series", R.drawable.rail_series));
                out.add(group("books", "Books", "All local titles", R.drawable.rail_books));
            } else if (limit >= 2) {
                if (limit == 3) out.add(group("authors", "Authors", "Browse by author", R.drawable.rail_authors));
                out.add(group("library", "Library", "Authors, series and books", R.drawable.rail_books));
            }
            return out;
        }
        if ("library".equals(parentId) || "library:all".equals(parentId)) {
            if ("library:all".equals(parentId))
                out.add(group("continue", "Continue", "Unfinished books", R.drawable.rail_history));
            out.add(group("authors", "Authors", "Browse by author", R.drawable.rail_authors));
            out.add(group("series", "Series", "Browse by series", R.drawable.rail_series));
            out.add(group("books", "Books", "All local titles", R.drawable.rail_books));
            return out;
        }
        List<JSONObject> books = books();
        if ("continue".equals(parentId)) {
            books.sort((a, b) -> Long.compare(b.optLong("lastPlayedAt"), a.optLong("lastPlayedAt")));
            for (JSONObject item : books) if (!item.optBoolean("finished")
                    && item.optLong("position") > 0) out.add(book(item));
        } else if ("books".equals(parentId)) {
            books.sort(Comparator.comparing(AriosoCarBrowserService::title,
                    String.CASE_INSENSITIVE_ORDER));
            for (JSONObject item : books) out.add(book(item));
        } else if ("authors".equals(parentId)) {
            Map<String, String> authors = new LinkedHashMap<>();
            for (JSONObject item : books) {
                String author = author(item);
                authors.putIfAbsent(author.toLowerCase(Locale.ROOT), author);
            }
            for (String author : authors.values()) out.add(group(CarMediaIds.group("author", author),
                    author, "Author", R.drawable.rail_authors));
        } else if ("series".equals(parentId)) {
            Map<String, String[]> series = new LinkedHashMap<>();
            for (JSONObject item : books) {
                String name = series(item);
                if (name.isEmpty()) continue;
                String author = author(item);
                series.putIfAbsent((author + "\u0000" + name).toLowerCase(Locale.ROOT),
                        new String[]{author, name});
            }
            for (String[] item : series.values()) out.add(group(
                    CarMediaIds.group("series", item[0] + "\u0000" + item[1]), item[1], item[0],
                    R.drawable.rail_series));
        } else if (parentId.startsWith("author:")) {
            String selected = null;
            for (JSONObject item : books) if (parentId.equals(CarMediaIds.group("author", author(item)))) {
                selected = author(item); break;
            }
            if (selected == null) return out;
            Map<String, String> series = new LinkedHashMap<>();
            for (JSONObject item : books) if (selected.equalsIgnoreCase(author(item))) {
                String name = series(item);
                if (name.isEmpty()) out.add(book(item));
                else series.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
            for (String name : series.values()) out.add(group(CarMediaIds.group("series", selected
                    + "\u0000" + name), name, "Series", R.drawable.rail_series));
        } else if (parentId.startsWith("series:")) {
            for (JSONObject item : books) if (!series(item).isEmpty()
                    && parentId.equals(CarMediaIds.group("series", author(item)
                    + "\u0000" + series(item)))) out.add(book(item));
        }
        return out;
    }

    private List<JSONObject> books() {
        JSONArray saved = LibraryStore.get(this).readAll();
        List<JSONObject> books = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
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
        return books;
    }

    private static String title(JSONObject item) {
        String title = item.optString("title", "").trim();
        return title.isEmpty() ? item.optString("name", "Book") : title;
    }

    private static String author(JSONObject item) {
        String author = item.optString("author", "").trim();
        return author.isEmpty() ? "Unknown author" : author;
    }

    private static String series(JSONObject item) {
        String series = item.optString("series", "").trim();
        return series.equalsIgnoreCase("Standalone") ? "" : series;
    }

    private Uri icon(int drawable) {
        return Uri.parse("android.resource://" + getPackageName() + "/" + drawable);
    }

    private MediaBrowserCompat.MediaItem group(String id, String title, String subtitle,
                                                int drawable) {
        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(display(title)).setSubtitle(display(subtitle))
                .setIconUri(icon(drawable)).build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem book(JSONObject item) {
        String subtitle = author(item);
        String series = series(item);
        if (!series.isEmpty()) subtitle += " · " + series + " " + item.optString("seriesNumber", "");
        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(CarMediaIds.book(item.optString("uri")))
                .setTitle(display(title(item))).setSubtitle(display(subtitle))
                .setIconUri(icon(R.drawable.ic_arioso)).build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static String display(String value) {
        if (value == null) return "";
        return value.length() <= 120 ? value : value.substring(0, 119) + "…";
    }

    @Override public void onDestroy() {
        browseWorker.shutdownNow();
        if (bound) unbindService(playbackConnection);
        super.onDestroy();
    }
}
