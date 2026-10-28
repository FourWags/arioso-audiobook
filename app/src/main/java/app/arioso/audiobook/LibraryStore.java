package app.arioso.audiobook;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private, transactional storage for book metadata. Audio stays with its document provider. */
final class LibraryStore extends SQLiteOpenHelper {
    private static final String DATABASE = "arioso_library.db";
    // The singleton captures only getApplicationContext(), never an Activity or Service.
    @SuppressLint("StaticFieldLeak")
    private static LibraryStore instance;
    private final Context context;

    static synchronized LibraryStore get(Context context) {
        if (instance == null) instance = new LibraryStore(context.getApplicationContext());
        return instance;
    }

    private LibraryStore(Context context) {
        super(context, DATABASE, null, 1);
        this.context = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (uri TEXT PRIMARY KEY NOT NULL, ordinal INTEGER NOT NULL, data TEXT NOT NULL)");
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Unsupported library database version");
    }

    private synchronized void ensureMigrated() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor marker = db.rawQuery("SELECT value FROM state WHERE key='migrated'", null)) {
            if (marker.moveToFirst()) return;
        }
        SharedPreferences prefs = context.getSharedPreferences("arioso_library_v1", Context.MODE_PRIVATE);
        JSONArray legacy;
        try { legacy = new JSONArray(prefs.getString("books", "[]")); }
        catch (JSONException e) { throw new SQLiteException("Legacy library is invalid; migration paused", e); }
        if (legacy.length() > 500) throw new SQLiteException("Legacy library exceeds the supported size");
        db.beginTransaction();
        try {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < legacy.length(); i++) {
                JSONObject item = legacy.optJSONObject(i);
                if (item == null) throw new SQLiteException("Invalid legacy book");
                if (!seen.add(item.optString("uri"))) continue;
                insert(db, item, i);
            }
            db.execSQL("INSERT INTO state(key,value) VALUES('migrated','1')");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized JSONArray readAll() {
        ensureMigrated();
        JSONArray books = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT data FROM books ORDER BY ordinal, uri", null)) {
            while (cursor.moveToNext()) books.put(new JSONObject(cursor.getString(0)));
        } catch (JSONException e) { throw new SQLiteException("Stored book metadata is invalid", e); }
        return books;
    }

    synchronized boolean replaceAll(List<JSONObject> items) {
        if (items.size() > 500) return false;
        try {
            ensureMigrated();
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("books", null, null);
                for (int i = 0; i < items.size(); i++) insert(db, items.get(i), i);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return true;
        } catch (RuntimeException e) { return false; }
    }

    synchronized boolean updateProgress(String uri, int position, int duration, boolean finished) {
        try {
            ensureMigrated();
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try (Cursor cursor = db.query("books", new String[]{"data"}, "uri=?",
                    new String[]{uri}, null, null, null)) {
                if (!cursor.moveToFirst()) return false;
                JSONObject book = new JSONObject(cursor.getString(0));
                int previousPosition = book.optInt("position");
                boolean wasFinished = book.optBoolean("finished");
                book.put("position", Math.max(0, position));
                if (duration > 0) book.put("duration", duration);
                book.put("finished", finished);
                if (Math.abs((long) position - previousPosition) >= 1000
                        || (finished && !wasFinished))
                    book.put("lastPlayedAt", System.currentTimeMillis());
                ContentValues values = new ContentValues();
                values.put("data", book.toString());
                if (db.update("books", values, "uri=?", new String[]{uri}) != 1) return false;
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return true;
        } catch (SQLiteException | JSONException e) { return false; }
    }

    synchronized boolean clearPlayedAt() {
        try {
            JSONArray saved = readAll();
            java.util.ArrayList<JSONObject> updated = new java.util.ArrayList<>();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject book = saved.getJSONObject(i);
                book.remove("lastPlayedAt");
                updated.add(book);
            }
            return replaceAll(updated);
        } catch (RuntimeException | JSONException e) { return false; }
    }

    synchronized String integrityReport() {
        try {
            ensureMigrated();
            SQLiteDatabase db = getReadableDatabase();
            String result;
            try (Cursor cursor = db.rawQuery("PRAGMA integrity_check", null)) {
                result = cursor.moveToFirst() ? cursor.getString(0) : "No result";
            }
            int rows;
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM books", null)) {
                rows = cursor.moveToFirst() ? cursor.getInt(0) : -1;
            }
            return "Database check: " + result + "\nBook listings: " + rows;
        } catch (RuntimeException e) {
            return "Database could not be checked. Existing data was left untouched.";
        }
    }

    private static void insert(SQLiteDatabase db, JSONObject book, int ordinal) {
        String uri = book.optString("uri");
        String data = book.toString();
        if (uri.isEmpty() || uri.length() > 4096 || data.length() > 1048576)
            throw new SQLiteException("Invalid book metadata");
        ContentValues values = new ContentValues();
        values.put("uri", uri);
        values.put("ordinal", ordinal);
        values.put("data", data);
        if (db.insertOrThrow("books", null, values) < 0)
            throw new SQLiteException("Could not save book metadata");
    }
}
