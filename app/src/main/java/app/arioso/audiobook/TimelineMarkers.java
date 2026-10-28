package app.arioso.audiobook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Passive chapter and bookmark landmarks beneath the seek bar. */
@SuppressLint("ViewConstructor") // Created with palette colors only in the player UI.
final class TimelineMarkers extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Integer> chapters = new ArrayList<>();
    private List<Integer> bookmarks = new ArrayList<>();
    private int duration;
    private int chapterColor;
    private int bookmarkColor;

    TimelineMarkers(Context context, int chapterColor, int bookmarkColor) {
        super(context);
        this.chapterColor = chapterColor;
        this.bookmarkColor = bookmarkColor;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setMarkers(int duration, List<Integer> chapters, List<Integer> bookmarks) {
        if (this.duration == duration && this.chapters.equals(chapters)
                && this.bookmarks.equals(bookmarks)) return;
        this.duration = duration;
        this.chapters = new ArrayList<>(chapters);
        this.bookmarks = new ArrayList<>(bookmarks);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (duration <= 0) return;
        float left = getPaddingLeft() + 8f;
        float width = Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight() - 16f);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        paint.setColor(chapterColor);
        for (int value : chapters) {
            float x = left + width * Math.min(duration, Math.max(0, value)) / duration;
            canvas.drawLine(x, 0, x, getHeight() * .65f, paint);
        }
        paint.setColor(bookmarkColor);
        for (int value : bookmarks) {
            float x = left + width * Math.min(duration, Math.max(0, value)) / duration;
            canvas.drawCircle(x, getHeight() * .72f,
                    getResources().getDisplayMetrics().density * 2.5f, paint);
        }
    }
}
