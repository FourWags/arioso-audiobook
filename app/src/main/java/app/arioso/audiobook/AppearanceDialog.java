package app.arioso.audiobook;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Scrollable, labeled previews. All labels wrap at system font scale; the whole choice is one target. */
final class AppearanceDialog {
    interface Listener {
        void navigate(String page);
        void layout(String id);
        void colors(String id);
        void icon(LauncherIcons.Icon icon);
        void reset();
        void closed();
    }
    private final Activity activity;
    private final ThemeCatalog.Theme theme;
    private final Listener listener;
    private final String layout, font;
    private final LauncherIcons.Icon icon;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final AlertDialog dialog;
    AppearanceDialog(Activity activity, ThemeCatalog.Theme theme, String layout, String font,
                     String page, int scrollY, Listener listener) {
        this.activity = activity; this.theme = theme; this.layout = layout; this.font = font;
        this.listener = listener; this.icon = LauncherIcons.current(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(12));
        content.setBackgroundColor(theme.card);
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        boolean home = "home".equals(page);
        String title = "layouts".equals(page) ? "Library layout" : "colors".equals(page) ? "Colors"
                : "icons".equals(page) ? "App icon" : "Appearance";
        TextView dialogTitle = label(title, 22);
        dialogTitle.setTypeface(dialogTitle.getTypeface(), Typeface.BOLD);
        dialogTitle.setPadding(dp(24), dp(18), dp(24), dp(12));
        dialogTitle.setBackgroundColor(theme.card);
        dialog = new AlertDialog.Builder(activity).setCustomTitle(dialogTitle).setView(scroll)
                .setPositiveButton("Done", null)
                .setNeutralButton(home ? "Reset appearance" : "Appearance", null).create();
        if (home) home();
        else if ("layouts".equals(page)) layouts();
        else if ("colors".equals(page)) colors();
        else if ("icons".equals(page)) icons();
        else home();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (home) listener.reset(); else listener.navigate("home");
            });
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
                Button button = dialog.getButton(which);
                button.setMinHeight(dp(48));
                button.setAllCaps(false);
                button.setTextColor(theme.ink);
                if (button.getParent() instanceof View) ((View) button.getParent()).setBackgroundColor(theme.card);
            }
            scroll.post(() -> scroll.scrollTo(0, scrollY));
        });
        dialog.setOnDismissListener(d -> listener.closed());
    }
    void show() { dialog.show(); }
    int scrollY() { return scroll.getScrollY(); }
    void dismissSilently() { dialog.setOnDismissListener(null); dialog.dismiss(); }
    private void home() {
        paragraph("Make Arioso yours. Tap a preview to change it.");
        choice("Library layout", layoutName(layout), new Preview(layout, theme), false,
                () -> listener.navigate("layouts"));
        choice("Colors", theme.name, new Preview("colors", theme), false,
                () -> listener.navigate("colors"));
        choice("App icon", icon.name, iconPreview(icon), false,
                () -> listener.navigate("icons"));
        paragraph("Changes are saved on this device. Your books and listening places stay as they are.");
    }
    private void layouts() {
        paragraph("Choose how you browse your library.");
        String[] ids = {LibraryPresentation.SPLIT, LibraryPresentation.GRID, LibraryPresentation.COMPACT};
        String[] details = {"Authors and series with an A–Z index", "Large covers for browsing",
                "Smaller covers with more book details"};
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            choice(layoutName(id), details[i], new Preview(id, theme), id.equals(layout),
                    () -> listener.layout(id));
        }
    }
    private void colors() {
        paragraph("Preview the background, book card and playback controls.");
        for (ThemeCatalog.Theme palette : ThemeCatalog.ALL)
            choice(palette.name, palette.id.equals(theme.id) ? "Current colors" : "Use these colors",
                    new Preview("colors", palette), palette.id.equals(theme.id),
                    () -> listener.colors(palette.id));
    }
    private void icons() {
        paragraph("Choose Arioso’s home-screen icon. Your launcher may take a moment to refresh.");
        for (LauncherIcons.Icon option : LauncherIcons.ALL)
            choice(option.name, option == icon ? "Current app icon" : "Use this app icon",
                    iconPreview(option), option == icon, () -> listener.icon(option));
    }
    static String layoutName(String id) {
        return LibraryPresentation.COMPACT.equals(id) ? "Compact Rows"
                : LibraryPresentation.GRID.equals(id) ? "Cover Grid" : "Split Shelf";
    }
    private View iconPreview(LauncherIcons.Icon option) {
        ImageView image = new ImageView(activity);
        image.setImageResource(option.drawable);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return image;
    }
    private void paragraph(String value) { content.addView(label(value, 15)); }
    private TextView label(String value, int size) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextColor(theme.ink); text.setTextSize(size);
        text.setPadding(0, dp(6), 0, dp(6));
        if (!"system".equals(font)) text.setTypeface(Typeface.create(font, Typeface.NORMAL));
        return text;
    }
    private void choice(String title, String detail, View preview, boolean selected, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(88));
        GradientDrawable background = new GradientDrawable();
        background.setColor(theme.card); background.setCornerRadius(dp(12));
        background.setStroke(dp(selected ? 3 : 1), selected ? theme.ink : theme.outline);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(theme.outline), background, null));
        row.setClickable(true); row.setFocusable(true);
        row.setContentDescription(title + ". " + detail + (selected ? ". Selected" : ""));
        if (Build.VERSION.SDK_INT >= 30) row.setStateDescription(selected ? "Selected" : "");
        row.setSelected(selected);
        row.setOnClickListener(v -> action.run());
        // One accessible/focusable choice; decorative preview and radio are not separate targets.
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(preview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView heading = label(title, 17);
        heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
        words.addView(heading);
        words.addView(label(detail, 14));
        if (selected) words.addView(label("Selected", 13));
        LinearLayout.LayoutParams wordPlace = new LinearLayout.LayoutParams(0, -2, 1);
        wordPlace.leftMargin = dp(12);
        row.addView(words, wordPlace);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-1, -2);
        place.topMargin = dp(8); place.bottomMargin = dp(4);
        content.addView(row, place);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private final class Preview extends View {
        private final String kind;
        private final ThemeCatalog.Theme palette;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final android.graphics.Path triangle = new android.graphics.Path();
        Preview(String kind, ThemeCatalog.Theme palette) { super(activity); this.kind = kind; this.palette = palette; }
        private void rect(Canvas c, float l, float t, float r, float b, int color) {
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            bounds.set(l, t, r, b);
            c.drawRoundRect(bounds, 2, 2, paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save(); canvas.scale(getWidth() / 64f, getHeight() / 64f);
            rect(canvas, 0, 0, 64, 64, palette.background);
            if ("colors".equals(kind)) {
                rect(canvas, 8, 8, 56, 56, palette.card);
                rect(canvas, 14, 14, 38, 18, palette.ink);
                rect(canvas, 14, 24, 48, 27, palette.ink);
                rect(canvas, 14, 35, 50, 49, palette.action);
                paint.setColor(palette.actionInk);
                triangle.reset();
                triangle.moveTo(29, 38); triangle.lineTo(29, 46); triangle.lineTo(36, 42); triangle.close();
                canvas.drawPath(triangle, paint);
            } else if (LibraryPresentation.GRID.equals(kind)) {
                for (int x = 7; x < 55; x += 27) for (int y = 7; y < 55; y += 27) {
                    rect(canvas, x, y, x + 20, y + 19, palette.card);
                    rect(canvas, x + 3, y + 22, x + 18, y + 24, palette.ink);
                }
            } else if (LibraryPresentation.COMPACT.equals(kind)) {
                for (int y = 6; y < 60; y += 18) {
                    rect(canvas, 5, y, 59, y + 15, palette.card);
                    rect(canvas, 8, y + 3, 17, y + 12, palette.action);
                    rect(canvas, 22, y + 4, 53, y + 6, palette.ink);
                    rect(canvas, 22, y + 10, 43, y + 12, palette.ink);
                }
            } else {
                rect(canvas, 4, 6, 24, 58, palette.card);
                for (int y = 12; y < 56; y += 10) rect(canvas, 8, y, 20, y + 3, palette.ink);
                for (int y = 6; y < 58; y += 19) {
                    rect(canvas, 28, y, 60, y + 15, palette.card);
                    rect(canvas, 31, y + 4, 38, y + 12, palette.action);
                    rect(canvas, 42, y + 4, 56, y + 7, palette.ink);
                }
            }
            canvas.restore();
        }
    }
}
