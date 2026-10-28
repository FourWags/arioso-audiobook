package app.arioso.audiobook;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local playback shortcut; never reads or displays a book from a remote provider. */
public final class AriosoWidget extends AppWidgetProvider {
    static final String ACTION_PLAY = "app.arioso.audiobook.WIDGET_PLAY";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences prefs = context.getSharedPreferences("arioso_library_v1", Context.MODE_PRIVATE);
        String selected = prefs.getString("selected_uri", "");
        String title = "Choose a book in Arioso";
        if (!prefs.getBoolean("private_playback", false)) try {
            JSONArray books = LibraryStore.get(context).readAll();
            for (int i = 0; i < books.length(); i++) {
                JSONObject item = books.optJSONObject(i);
                if (item != null && selected.equals(item.optString("uri"))) {
                    title = item.optString("title", item.optString("name", "Arioso"));
                    break;
                }
            }
        } catch (RuntimeException ignored) { title = "Arioso"; }
        render(context, manager, ids, title, false);
    }

    static void update(Context context, String title, boolean playing, boolean privatePlayback) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, AriosoWidget.class));
        if (ids.length == 0) return;
        render(context, manager, ids,
                privatePlayback ? "Arioso" : title == null ? "Choose a book in Arioso" : title, playing);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids,
                               String title, boolean playing) {
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent play = new Intent(context, MainActivity.class).setAction(ACTION_PLAY);
        play.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(context, 48, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent playIntent = PendingIntent.getActivity(context, 49, play,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.arioso_widget);
            views.setTextViewText(R.id.widget_title, title);
            views.setTextViewText(R.id.widget_play, playing ? "Pause" : "Play");
            views.setOnClickPendingIntent(R.id.widget_title, openIntent);
            views.setOnClickPendingIntent(R.id.widget_play, playIntent);
            manager.updateAppWidget(id, views);
        }
    }
}
