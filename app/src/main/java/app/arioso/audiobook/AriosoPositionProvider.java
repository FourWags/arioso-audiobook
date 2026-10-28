package app.arioso.audiobook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Read-only saved places for a trusted local Library. Audio and bookmarks stay private. */
public final class AriosoPositionProvider extends ContentProvider {
    private static final String LIBRARY_PACKAGE = "app.petallibrary";
    private static final String LIBRARY_CERT =
            "386f097ea7de3749f0b0cc87e433d0f8a8a70cebb1848d7e7cb4bd5f60cff2b4";

    @Override public boolean onCreate() { return true; }

    private void checkCaller() {
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(Binder.getCallingUid());
        boolean named = false;
        if (packages != null) for (String name : packages) named |= LIBRARY_PACKAGE.equals(name);
        if (!named || !certificateMatches(pm)) throw new SecurityException("Trusted Library required");
    }

    static boolean certificateMatches(PackageManager pm) {
        try {
            byte[] digest = new byte[LIBRARY_CERT.length() / 2];
            for (int i = 0; i < digest.length; i++)
                digest[i] = (byte) Integer.parseInt(LIBRARY_CERT.substring(i * 2, i * 2 + 2), 16);
            if (Build.VERSION.SDK_INT >= 28)
                return pm.hasSigningCertificate(LIBRARY_PACKAGE, digest, PackageManager.CERT_INPUT_SHA256);
            @SuppressWarnings("deprecation")
            android.content.pm.PackageInfo info = pm.getPackageInfo(LIBRARY_PACKAGE,
                    PackageManager.GET_SIGNATURES);
            @SuppressWarnings("deprecation")
            android.content.pm.Signature[] signatures = info.signatures;
            if (signatures != null) for (android.content.pm.Signature signature : signatures)
                if (MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray()))) return true;
        } catch (Exception ignored) { }
        return false;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        checkCaller();
        if (!"/books".equals(uri.getPath())) throw new IllegalArgumentException("Unsupported path");
        MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "title", "author",
                "progress", "positionMs", "durationMs", "bookUrl"});
        JSONArray books = LibraryStore.get(getContext()).readAll();
        for (int i = 0; i < books.length() && i < 500; i++) {
            JSONObject book = books.optJSONObject(i);
            if (book == null) continue;
            String id = book.optString("uri", "");
            int duration = book.optInt("duration", 0);
            if (id.isEmpty() || id.length() > 4096 || duration <= 0) continue;
            int position = Math.max(0, Math.min(duration, book.optInt("position", 0)));
            boolean finished = book.optBoolean("finished", false);
            String title = bounded(book.optString("title", ""));
            if (title.isEmpty()) title = bounded(book.optString("name", ""));
            if (title.isEmpty()) title = "Untitled audio";
            cursor.addRow(new Object[]{stableId(id), title,
                    bounded(book.optString("author", "")),
                    PositionSnapshot.progress(position, duration, finished),
                    position, duration, id});
        }
        return cursor;
    }

    private static String bounded(String value) {
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 512 ? clean.substring(0, 512).trim() : clean;
    }

    private static String stableId(String uri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uri.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", part & 0xff));
            return hex.toString();
        } catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        Bundle result = new Bundle();
        result.putString("error", "unsupported");
        return result;
    }

    @Override public String getType(Uri uri) { checkCaller(); return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { checkCaller(); return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { checkCaller(); return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        checkCaller(); return 0;
    }
}
