package app.arioso.audiobook;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.util.LruCache;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputType;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.ArrayDeque;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.math.BigDecimal;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** A small, original, document-picker-based audiobook player. */
public final class MainActivity extends Activity {
    /** Shortens the lifetime of the temporary plaintext backing array after backup import. */
    private static final class WipeableBytes extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }

    private static final int PICK_AUDIO = 10;
    private static final int PICK_RELINK = 11;
    private static final int PICK_EXPORT = 12;
    private static final int PICK_IMPORT = 13;
    private static final int PICK_COVER = 14;
    private static final int PICK_FOLDER = 15;
    private static final int PICK_EXPORT_ENCRYPTED = 16;
    private static final int PICK_IMPORT_ENCRYPTED = 17;
    private static final int PICK_BOOKMARK_EXPORT = 18;
    private static final int PICK_EXPORT_CATALOG = 19;
    private static final int PICK_COMPANION_PDF = 20;
    private static final String PREFS = "arioso_library_v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_ARTWORK_WAITERS = 24;
    private ThemeCatalog.Theme theme;
    private AppearanceDialog appearanceDialog;
    private String appearancePage;
    private int STONE;
    private int PAPER;
    private int INK;
    private int PLUM;
    private int BLUSH;
    private int OUTLINE;
    private int OUTSIDE;
    private int ON_ACTION;
    private static final String UNKNOWN_AUTHOR = "Unknown author";
    private static final String STANDALONE = "Standalone";
    private static final String BACKUP_FORMAT = BackupFormat.CURRENT;
    private static final String[] BACKUP_FIELDS = {"title", "author", "authorSort", "authorAliases",
            "series", "seriesNumber", "narrator", "edition", "tags", "partGroup", "partOrder",
            "playbackSpeed", "playbackPitch", "manualChapters", "note",
            "favorite", "listenLater", "archived", "companionPdfUri", "voiceBoost",
            "skipSilence", "characters", "folderCoverUri"};
    private static final String[] UNDO_FIELDS = {"title", "author", "authorSort", "authorAliases",
            "series", "seriesNumber", "seriesSuggested", "narrator", "edition", "tags",
            "partGroup", "partOrder", "manualEdited", "chapterLabels", "manualChapters", "note",
            "favorite", "listenLater", "archived", "companionPdfUri", "voiceBoost",
            "skipSilence", "characters", "folderCoverUri"};
    private final List<JSONObject> books = new ArrayList<>();
    private boolean libraryLoaded;
    private final LruCache<String, Bitmap> artwork = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Set<String> absentArtwork = new HashSet<>();
    private final Set<String> chapterScanInFlight = new HashSet<>();
    private final Map<String, List<Mp4Chapters.Chapter>> chapterCache =
            new LinkedHashMap<String, List<Mp4Chapters.Chapter>>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Mp4Chapters.Chapter>> eldest) {
                    return size() > 4;
                }
            };
    private final Map<String, CombinedChapters> combinedChapterCache =
            new LinkedHashMap<String, CombinedChapters>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CombinedChapters> eldest) {
                    return size() > 4;
                }
            };
    private final Map<String, List<WeakReference<ImageView>>> waitingArtwork = new HashMap<>();
    private final ExecutorService artworkWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService localSyncWorker = Executors.newSingleThreadExecutor();
    private String lastLocalSuggestionUrl;
    private int localSuggestionGeneration;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object nestedBackCallback;
    private boolean nestedBackRegistered;
    @TargetApi(33)
    private static final class Api33Back {
        static Object register(MainActivity activity) {
            android.window.OnBackInvokedCallback callback = activity::navigateBackWithinApp;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(MainActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) callback);
        }
    }
    private final String[] speeds = {"0.75×", "0.85×", "1.0×", "1.1×", "1.25×", "1.5×",
            "1.75×", "2.0×", "2.25×", "2.5×", "3.0×"};
    private final float[] speedValues = {.75f, .85f, 1f, 1.1f, 1.25f, 1.5f,
            1.75f, 2f, 2.25f, 2.5f, 3f};
    private SharedPreferences prefs;
    private PlaybackService playback;
    private boolean serviceBound;
    private final PlaybackService.Listener playbackListener = this::refreshPlayerUi;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed) return;
            playback = ((PlaybackService.LocalBinder) binder).getService();
            playback.setListener(playbackListener);
            playback.setPrivatePlayback(prefs.getBoolean("private_playback", false));
            playback.setSkipIntervals(backMs, forwardMs);
            JSONObject selected = find(currentUri);
            if (selected != null) scanChapters(selected);
            if (widgetPlayRequested && selected == null) {
                widgetPlayRequested = false;
                message("Choose a book in Arioso first");
            } else if (widgetPlayRequested) {
                widgetPlayRequested = false;
                startWidgetPlayback(selected);
            } else if (selected != null && !currentUri.equals(playback.currentUri())) loadSelected(selected);
            else if (selected == null && playback.currentUri() != null) playback.clear();
            refreshPlayerUi();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            refreshPlayerUi();
        }
    };
    private String currentUri;
    private String relinkOriginalUri;
    private final ArrayDeque<String> relinkBatchQueue = new ArrayDeque<>();
    private String coverBookUri;
    private String companionBookUri;
    private String bookmarkExportBookUri;
    private String pendingJumpUri;
    private int pendingJumpPosition;
    private char[] pendingExportPassword;
    private boolean dragging;
    private TextView title;
    private TextView playerByline;
    private TextView timeRemaining;
    private TextView clock;
    private TextView chapterProgress;
    private TextView outputRoute;
    private Button repeatButton;
    private Button boostButton;
    private Button silenceButton;
    private Button companionButton;
    private AlertDialog drivingDialog;
    private Button drivingPlay;
    private TextView drivingTitle;
    private GridView shelf;
    private BaseAdapter shelfAdapter;
    private final List<ShelfItem> shelfItems = new ArrayList<>();
    private LinearLayout libraryPanel;
    private LinearLayout headerPanel;
    private ScrollView playerPanel;
    private Button playerShortcut;
    private Button[] tabButtons;
    private Button scopeButton;
    private Button shelfFilter;
    private Button layoutButton;
    private LinearLayout continuePanel;
    private LinearLayout continueBooks;
    private LinearLayout miniPlayer;
    private TextView miniTitle;
    private TextView miniProgress;
    private Button miniPlay;
    private TextView libraryCount;
    private TextView libraryHeading;
    private TextView libraryEyebrow;
    private TextView emptyShelfTitle;
    private TextView emptyShelfDetail;
    private Button jumpIndexButton;
    private LinearLayout indexBar;
    private FrameLayout playerCoverHolder;
    private View playerAccent;
    private String playerCoverUri;
    private LinearLayout advancedTools;
    private Button advancedToggle;
    private boolean advancedOpen;
    private boolean extraLargeLibrary;
    private String selectedShelf = "All books";
    private EditText search;
    private int libraryTab;
    private boolean compactLibrary;
    private String libraryLayout;
    private String uiFont;
    private boolean chapterTimeline;
    private int timelineStart;
    private int timelineEnd;
    private String selectedAuthor;
    private String selectedSeries;
    private boolean showingPlayer;
    private boolean destroyed;
    private boolean widgetPlayRequested;
    private AtomicBoolean activeFolderScan;
    private Uri activeFolderTree;
    private AtomicBoolean activeFingerprintCheck;
    private AlertDialog folderScanDialog;
    private Button play;
    private Button backButton;
    private Button forwardButton;
    private int backMs = 15000;
    private int forwardMs = 30000;
    private SeekBar seek;
    private TimelineMarkers timelineMarkers;
    private Spinner speed;
    private int speedIndex;
    private boolean selectingBookSpeed;
    private Button sleepButton;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playback == null || !playback.isReady()) return;
            int position = playback.position();
            int duration = playback.duration();
            String reading = time(position) + " / " + time(duration);
            if (!reading.contentEquals(clock.getText())) clock.setText(reading);
            if (timeRemaining != null)
                timeRemaining.setText("Time left  ·  " + time(Math.max(0, duration - position)));
            renderChapterProgress(position, duration);
            refreshMiniPlayer();
            if (outputRoute != null) outputRoute.setText(getString(R.string.output_route,
                    playback.outputRoute()));
            if (playback.isPlaying()) handler.postDelayed(this, 1000);
        }
    };

    private static final class ShelfItem {
        String author;
        String series;
        String label;
        String subtitle;
        JSONObject book;
        final List<JSONObject> covers = new ArrayList<>();
        final List<JSONObject> parts = new ArrayList<>();
    }

    private static final class CombinedChapters {
        final JSONObject book;
        final JSONArray manual;
        final List<Mp4Chapters.Chapter> embedded;
        final int duration;
        final List<Mp4Chapters.Chapter> chapters;
        CombinedChapters(JSONObject book, JSONArray manual, List<Mp4Chapters.Chapter> embedded,
                         int duration, List<Mp4Chapters.Chapter> chapters) {
            this.book = book;
            this.manual = manual;
            this.embedded = embedded;
            this.duration = duration;
            this.chapters = chapters;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        widgetPlayRequested = state == null && AriosoWidget.ACTION_PLAY.equals(getIntent().getAction());
        if (widgetPlayRequested) setIntent(new Intent(this, MainActivity.class));
        backMs = Math.max(10000, Math.min(120000, prefs.getInt("skip_back_ms", 15000)));
        forwardMs = Math.max(10000, Math.min(120000, prefs.getInt("skip_forward_ms", 30000)));
        libraryLayout = LibraryPresentation.layout(prefs.getString("library_layout", null),
                prefs.contains("compact_library"), prefs.getBoolean("compact_library", false));
        compactLibrary = LibraryPresentation.COMPACT.equals(libraryLayout);
        uiFont = prefs.getString("ui_font", "system");
        libraryTab = LibraryPresentation.SPLIT.equals(libraryLayout) ? 1 : 0;
        chapterTimeline = prefs.getBoolean("chapter_timeline", false);
        applyTheme(ThemeCatalog.forId(prefs.getString("theme_id", ThemeCatalog.DEFAULT_ID)));
        readLibrary();
        currentUri = prefs.getString("selected_uri", null);
        if (state != null) {
            libraryTab = state.getInt("libraryTab", 0);
            selectedAuthor = state.getString("selectedAuthor");
            selectedSeries = state.getString("selectedSeries");
            showingPlayer = state.getBoolean("showingPlayer", false);
            advancedOpen = state.getBoolean("advancedOpen", false);
            selectedShelf = state.getString("selectedShelf", "All books");
            relinkOriginalUri = state.getString("relinkOriginalUri");
            String[] pendingRelinks = state.getStringArray("relinkBatchQueue");
            if (pendingRelinks != null) Collections.addAll(relinkBatchQueue, pendingRelinks);
            coverBookUri = state.getString("coverBookUri");
            companionBookUri = state.getString("companionBookUri");
            bookmarkExportBookUri = state.getString("bookmarkExportBookUri");
        }
        buildUi();
        if (state != null) search.setText(state.getString("search", ""));
        renderLibrary();
        if (state != null && state.getString("appearancePage") != null) {
            String page = state.getString("appearancePage");
            int scrollY = state.getInt("appearanceScroll", 0);
            getWindow().getDecorView().post(() -> showAppearance(page, scrollY));
        }
        if (state == null) handleReaderSyncContinue(getIntent());
        if (state != null) {
            String resumeTree = state.getString("activeFolderTree");
            if (resumeTree != null) scanFolder(Uri.parse(resumeTree));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("app.petallibrary.CONTINUE".equals(intent.getAction())) {
            handleReaderSyncContinue(intent);
            setIntent(new Intent(this, MainActivity.class));
            return;
        }
        if (!AriosoWidget.ACTION_PLAY.equals(intent.getAction())) return;
        setIntent(new Intent(this, MainActivity.class));
        JSONObject selected = find(currentUri);
        if (selected == null) {
            widgetPlayRequested = false;
            message("Choose a book in Arioso first");
        } else if (playback != null) startWidgetPlayback(selected);
        else widgetPlayRequested = true;
    }

    private void startWidgetPlayback(JSONObject item) {
        if (sourceChanged(item)) { open(item); return; }
        if (playback == null) return;
        playback.setSpeed((float) item.optDouble("playbackSpeed", 1.0));
        playback.setPitch((float) item.optDouble("playbackPitch", 1.0));
        playback.setVoiceBoost(item.optBoolean("voiceBoost", false));
        playback.loadAndPlay(item.optString("uri"), bookTitle(item),
                metadata(item, "author", UNKNOWN_AUTHOR), item.optInt("position"),
                item.optBoolean("finished"));
        playback.setSkipSilence(item.optBoolean("skipSilence", false));
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString("appearancePage", appearancePage);
        if (appearanceDialog != null) outState.putInt("appearanceScroll", appearanceDialog.scrollY());
        outState.putInt("libraryTab", libraryTab);
        outState.putString("selectedAuthor", selectedAuthor);
        outState.putString("selectedSeries", selectedSeries);
        outState.putBoolean("showingPlayer", showingPlayer);
        outState.putBoolean("advancedOpen", advancedOpen);
        outState.putString("selectedShelf", selectedShelf);
        outState.putString("relinkOriginalUri", relinkOriginalUri);
        outState.putStringArray("relinkBatchQueue", relinkBatchQueue.toArray(new String[0]));
        outState.putString("coverBookUri", coverBookUri);
        outState.putString("companionBookUri", companionBookUri);
        outState.putString("bookmarkExportBookUri", bookmarkExportBookUri);
        if (activeFolderTree != null) outState.putString("activeFolderTree", activeFolderTree.toString());
        if (search != null) outState.putString("search", search.getText().toString());
        super.onSaveInstanceState(outState);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_F && event.isCtrlPressed() && search != null) {
            showPlayer(false);
            search.requestFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void applyTheme(ThemeCatalog.Theme chosen) {
        theme = chosen;
        STONE = chosen.background;
        PAPER = chosen.card;
        INK = chosen.ink;
        PLUM = chosen.action;
        BLUSH = chosen.button;
        OUTLINE = chosen.outline;
        OUTSIDE = chosen.outside;
        ON_ACTION = chosen.actionInk;
        getWindow().setStatusBarColor(STONE);
        int navigation = Color.luminance(STONE) > 0.5
                ? (Color.luminance(INK) < 0.2 ? INK : 0xff211b22) : STONE;
        getWindow().setNavigationBarColor(navigation);
        int flags = 0;
        if (Color.luminance(STONE) > 0.5) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override protected void onStart() {
        super.onStart();
        books.clear();
        readLibrary();
        currentUri = prefs.getString("selected_uri", null);
        renderLibrary();
        serviceBound = bindService(new Intent(this, PlaybackService.class),
                serviceConnection, BIND_AUTO_CREATE);
    }

    private void buildUi() {
        int pad = dp(12);
        float fontScale = getResources().getConfiguration().fontScale;
        boolean extraLargeText = fontScale >= 2f;
        extraLargeLibrary = extraLargeText;
        int searchHeight = dp(Math.max(44, Math.round((extraLargeText ? 26 : 30) * fontScale)));
        int tabHeight = dp(Math.max(44, Math.round((extraLargeText ? 24 : 34) * fontScale)));
        int actionHeight = dp(Math.max(48, Math.round((extraLargeText ? 26 : 34) * fontScale)));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(STONE);
        root.setPadding(pad, dp(8), pad, 0);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(pad + insets.getSystemWindowInsetLeft(),
                    dp(8) + insets.getSystemWindowInsetTop(), pad + insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top = new LinearLayout(this);
        headerPanel = top;
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(4), dp(2), dp(4), dp(4));
        boolean largeText = fontScale >= 2f;
        boolean shortLandscape = getResources().getConfiguration().screenHeightDp < 500
                && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        TextView brand = text("ARIOSO", extraLargeText ? 11 : 12);
        brand.setTextColor(INK);
        brand.setLetterSpacing(0.12f);
        brand.setPadding(0, 0, 0, 0);
        if (!shortLandscape) top.addView(brand);
        libraryEyebrow = text("YOUR LIBRARY", 10);
        libraryEyebrow.setLetterSpacing(0.12f);
        libraryEyebrow.setPadding(0, dp(2), 0, 0);
        if (!shortLandscape && !LibraryPresentation.SPLIT.equals(libraryLayout)) top.addView(libraryEyebrow);
        libraryHeading = text("Authors", extraLargeText ? 22 : 25);
        libraryHeading.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
        libraryHeading.setPadding(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) libraryHeading.setAccessibilityHeading(true);
        if (!shortLandscape) top.addView(libraryHeading);
        libraryCount = text("Your books stay on this device", extraLargeText ? 11 : 12);
        libraryCount.setPadding(0, 0, 0, dp(2));
        if (!shortLandscape) top.addView(libraryCount);
        LinearLayout headerControls = new LinearLayout(this);
        headerControls.setGravity(Gravity.CENTER_VERTICAL);
        Button appearance = button("Appearance");
        appearance.setTextSize(13);
        appearance.setContentDescription("Appearance: change library layout, colors and app icon");
        appearance.setOnClickListener(v -> showAppearance("home", 0));
        playerShortcut = button("Player");
        playerShortcut.setTextSize(13);
        playerShortcut.setBackground(control(PLUM, PLUM, PAPER));
        playerShortcut.setTextColor(ON_ACTION);
        playerShortcut.setOnClickListener(v -> showPlayer(true));
        Button more = libraryActionsButton();
        more.setTextSize(13);
        if (largeText && !shortLandscape) {
            // A full-width primary entry keeps its label visible at 200% font scale.
            top.addView(appearance, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(0, -2, 1.35f);
            place.rightMargin = dp(6);
            headerControls.addView(appearance, place);
        }
        LinearLayout.LayoutParams playerPlacement = new LinearLayout.LayoutParams(0, -2, 1);
        playerPlacement.rightMargin = dp(6);
        headerControls.addView(playerShortcut, playerPlacement);
        headerControls.addView(more, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(headerControls);
        LinearLayout.LayoutParams headerPlace = new LinearLayout.LayoutParams(-1, -2);
        headerPlace.bottomMargin = dp(2);
        root.addView(top, headerPlace);

        libraryPanel = new LinearLayout(this);
        libraryPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(libraryPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(extraLargeText ? 14 : 16);
        search.setHint("Search library");
        search.setContentDescription("Search authors, series and books");
        search.setId(View.generateViewId());
        search.setTextColor(INK);
        search.setHintTextColor(INK);
        if (!"system".equals(uiFont)) search.setTypeface(android.graphics.Typeface.create(uiFont,
                android.graphics.Typeface.NORMAL));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(control(PAPER, OUTLINE, PLUM));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        libraryPanel.addView(search, new LinearLayout.LayoutParams(-1, searchHeight));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderLibrary(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        continuePanel = new LinearLayout(this);
        continuePanel.setOrientation(LinearLayout.VERTICAL);
        continuePanel.setPadding(0, dp(6), 0, dp(5));
        continueBooks = new LinearLayout(this);
        continueBooks.setOrientation(LinearLayout.HORIZONTAL);
        TextView continueHeading = text("PICK UP THE THREAD", 12);
        continueHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        continueHeading.setLetterSpacing(0.09f);
        continueHeading.setTextColor(INK);
        continuePanel.addView(continueHeading);
        HorizontalScrollView continueScroll = new HorizontalScrollView(this);
        continueScroll.setHorizontalScrollBarEnabled(false);
        continueScroll.addView(continueBooks);
        continuePanel.addView(continueScroll);
        libraryPanel.addView(continuePanel);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(8), 0, dp(5));
        tabButtons = new Button[3];
        String[] names = {"Authors", "Series", "Books"};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            Button button = button(names[i]);
            button.setMinHeight(tabHeight);
            button.setTextSize(extraLargeText ? 11 : 14);
            button.setOnClickListener(v -> {
                libraryTab = tab;
                selectedAuthor = null;
                selectedSeries = null;
                renderLibrary();
            });
            button.setId(View.generateViewId());
            LinearLayout.LayoutParams size = largeText
                    ? new LinearLayout.LayoutParams(dp(145), -2)
                    : new LinearLayout.LayoutParams(0, tabHeight, 1);
            size.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(button, size);
            tabButtons[i] = button;
        }
        View tabStrip;
        if (largeText) {
            HorizontalScrollView tabScroll = new HorizontalScrollView(this);
            tabScroll.setHorizontalScrollBarEnabled(false);
            tabScroll.addView(tabs);
            tabStrip = tabScroll;
        } else tabStrip = tabs;
        shelfFilter = button("Shelf: All books");
        shelfFilter.setTextSize(extraLargeText ? 11 : 13);
        shelfFilter.setOnClickListener(v -> chooseShelf());
        layoutButton = button(layoutName());
        layoutButton.setTextSize(extraLargeText ? 11 : 13);
        layoutButton.setOnClickListener(v -> chooseLibraryLayout());
        if (LibraryPresentation.SPLIT.equals(libraryLayout)) {
            shelfFilter.setBackground(control(STONE, STONE, PAPER));
            layoutButton.setBackground(control(STONE, STONE, PAPER));
        }
        if (extraLargeText && !shortLandscape) {
            libraryPanel.addView(shelfFilter);
            libraryPanel.addView(layoutButton);
        } else {
            LinearLayout filters = new LinearLayout(this);
            filters.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(48), 1);
            left.rightMargin = dp(6);
            filters.addView(shelfFilter, left);
            filters.addView(layoutButton, new LinearLayout.LayoutParams(0, dp(48), 1));
            libraryPanel.addView(filters);
        }
        scopeButton = button("All authors");
        scopeButton.setTextSize(13);
        scopeButton.setMinHeight(dp(48));
        scopeButton.setOnClickListener(v -> {
            if (selectedSeries != null) {
                selectedSeries = null;
                libraryTab = 1;
            } else {
                selectedAuthor = null;
                libraryTab = 0;
            }
            renderLibrary();
        });
        libraryPanel.addView(scopeButton);
        indexBar = new LinearLayout(this);
        indexBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView indexLabel = text("AUTHOR INDEX", 11);
        indexLabel.setLetterSpacing(0.1f);
        indexBar.addView(indexLabel, new LinearLayout.LayoutParams(0, -2, 1));
        jumpIndexButton = button("A–Z");
        jumpIndexButton.setContentDescription("Jump to author by first letter");
        jumpIndexButton.setTextSize(12);
        jumpIndexButton.setMinHeight(dp(36));
        jumpIndexButton.setBackground(control(STONE, STONE, PAPER));
        jumpIndexButton.setOnClickListener(v -> jumpToAuthor());
        indexBar.addView(jumpIndexButton, new LinearLayout.LayoutParams(dp(64), dp(48)));
        libraryPanel.addView(indexBar, new LinearLayout.LayoutParams(-1, dp(48)));
        shelf = new GridView(this);
        shelf.setId(View.generateViewId());
        shelf.setFocusable(true);
        search.setNextFocusDownId(tabButtons[0].getId());
        for (Button tab : tabButtons) tab.setNextFocusDownId(shelf.getId());
        shelf.setNextFocusUpId(tabButtons[0].getId());
        int availableDp = Math.round(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        shelf.setNumColumns(LibraryPresentation.GRID.equals(libraryLayout)
                ? Math.max(2, Math.min(5, availableDp / 176)) : 1);
        shelf.setHorizontalSpacing(dp(10));
        shelf.setVerticalSpacing(dp(12));
        shelf.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        shelf.setClipToPadding(false);
        shelf.setPadding(0, dp(4), 0, dp(8));
        shelf.setBackgroundColor(STONE);
        FrameLayout shelfArea = new FrameLayout(this);
        shelfAdapter = new BaseAdapter() {
            @Override public int getCount() { return shelfItems.size(); }
            @Override public Object getItem(int position) { return shelfItems.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return makeTile(shelfItems.get(position), position);
            }
        };
        shelf.setAdapter(shelfAdapter);
        shelfArea.addView(shelf, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout emptyShelf = new LinearLayout(this);
        emptyShelf.setOrientation(LinearLayout.VERTICAL);
        emptyShelf.setGravity(Gravity.CENTER);
        emptyShelf.setPadding(dp(24), dp(12), dp(24), dp(12));
        emptyShelfTitle = text(getString(R.string.empty_library_title), 20);
        emptyShelfTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        emptyShelfTitle.setGravity(Gravity.CENTER);
        emptyShelf.addView(emptyShelfTitle);
        emptyShelfDetail = text(getString(R.string.empty_library_detail), 14);
        emptyShelfDetail.setGravity(Gravity.CENTER);
        emptyShelf.addView(emptyShelfDetail);
        Button addFolder = button("Choose audiobook folder");
        addFolder.setContentDescription("Choose a folder of audiobooks on device storage");
        addFolder.setBackground(control(PLUM, PLUM, PAPER));
        addFolder.setTextColor(ON_ACTION);
        addFolder.setOnClickListener(v -> pickFolder());
        LinearLayout.LayoutParams folderPlace = new LinearLayout.LayoutParams(-1, dp(52));
        folderPlace.topMargin = dp(12);
        emptyShelf.addView(addFolder, folderPlace);
        Button addFirst = button("Add individual audio files");
        addFirst.setOnClickListener(v -> pickFiles());
        LinearLayout.LayoutParams addPlace = new LinearLayout.LayoutParams(-1, dp(52));
        addPlace.topMargin = dp(8);
        emptyShelf.addView(addFirst, addPlace);
        shelfArea.addView(emptyShelf, new FrameLayout.LayoutParams(-1, -1));
        shelf.setEmptyView(emptyShelf);
        libraryPanel.addView(shelfArea, new LinearLayout.LayoutParams(-1, 0, 1));
        miniPlayer = new LinearLayout(this);
        miniPlayer.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.setPadding(dp(10), dp(6), dp(8), dp(6));
        miniPlayer.setBackground(control(PAPER, OUTLINE, PLUM));
        miniPlayer.setVisibility(View.GONE);
        LinearLayout miniWords = new LinearLayout(this);
        miniWords.setOrientation(LinearLayout.VERTICAL);
        miniTitle = text("", 14);
        miniTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniProgress = text("", 12);
        miniWords.addView(miniTitle);
        miniWords.addView(miniProgress);
        miniWords.setContentDescription("Open player and saved listening place");
        miniWords.setMinimumHeight(dp(48));
        miniWords.setFocusable(true);
        miniWords.setOnClickListener(v -> showPlayer(true));
        miniPlayer.addView(miniWords, new LinearLayout.LayoutParams(0, -2, 1));
        miniPlay = button("Play");
        miniPlay.setBackground(control(PLUM, PLUM, PAPER));
        miniPlay.setTextColor(ON_ACTION);
        miniPlay.setOnClickListener(v -> toggle());
        miniPlayer.addView(miniPlay, new LinearLayout.LayoutParams(dp(76), dp(48)));
        libraryPanel.addView(miniPlayer);
        libraryPanel.addView(tabStrip);

        playerPanel = new ScrollView(this);
        LinearLayout playerContent = new LinearLayout(this);
        playerContent.setOrientation(LinearLayout.VERTICAL);
        playerContent.setPadding(0, 0, 0, dp(16));
        playerPanel.addView(playerContent);
        root.addView(playerPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        Button backToLibrary = button("← Library");
        backToLibrary.setOnClickListener(v -> showPlayer(false));
        playerContent.addView(backToLibrary);
        LinearLayout playerCard = card(playerContent);
        playerCard.setBackground(decoratedCard());
        TextView playerHeading = text("NOW LISTENING", 12);
        playerHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        playerHeading.setLetterSpacing(0.12f);
        playerHeading.setTextColor(INK);
        playerHeading.setGravity(Gravity.CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 28) playerHeading.setAccessibilityHeading(true);
        playerCard.addView(playerHeading);
        playerAccent = new View(this);
        playerAccent.setBackgroundColor(PLUM);
        LinearLayout.LayoutParams accentPlace = new LinearLayout.LayoutParams(dp(96), dp(5));
        accentPlace.gravity = Gravity.CENTER_HORIZONTAL;
        accentPlace.topMargin = dp(2);
        playerCard.addView(playerAccent, accentPlace);
        playerCoverHolder = new FrameLayout(this);
        playerCoverHolder.setBackground(control(PLUM, OUTLINE, PAPER));
        TextView coverPlaceholder = text("ARIOSO", 32);
        coverPlaceholder.setTypeface(null, android.graphics.Typeface.BOLD);
        coverPlaceholder.setTextColor(ON_ACTION);
        coverPlaceholder.setGravity(Gravity.CENTER);
        playerCoverHolder.addView(coverPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams coverPlace = new LinearLayout.LayoutParams(dp(136), dp(164));
        coverPlace.gravity = Gravity.CENTER_HORIZONTAL;
        coverPlace.topMargin = dp(8);
        playerCard.addView(playerCoverHolder, coverPlace);
        title = text("No audio selected", 22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(9), 0, 0);
        playerCard.addView(title);
        playerByline = text("Your audio stays on this device", 14);
        playerByline.setGravity(Gravity.CENTER);
        playerByline.setTextColor(INK);
        playerByline.setPadding(0, 0, 0, dp(8));
        playerCard.addView(playerByline);
        clock = text("0:00 / 0:00", 16);
        clock.setTextColor(INK);
        clock.setGravity(Gravity.CENTER);
        playerCard.addView(clock);
        timeRemaining = text("", 13);
        timeRemaining.setGravity(Gravity.CENTER);
        timeRemaining.setTextColor(INK);
        timeRemaining.setPadding(0, 0, 0, dp(4));
        playerCard.addView(timeRemaining);
        chapterProgress = text("", 13);
        chapterProgress.setVisibility(View.GONE);
        playerCard.addView(chapterProgress);
        outputRoute = text(getString(R.string.output_route, "Unknown"), 13);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(INK));
        seek.setThumbTintList(ColorStateList.valueOf(INK));
        seek.setContentDescription("Playback position");
        seek.setEnabled(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                seekToAndSave(timelineStart + bar.getProgress());
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) clock.setText(time(timelineStart + progress) + " / "
                        + time(playback == null ? 0 : playback.duration()));
            }
        });
        playerCard.addView(seek);
        timelineMarkers = new TimelineMarkers(this, INK, PLUM);
        playerCard.addView(timelineMarkers, new LinearLayout.LayoutParams(-1, dp(12)));
        Button seekTime = button("Go to time");
        seekTime.setOnClickListener(v -> promptSeekTime());
        Button recentPlaces = button("Recent places");
        recentPlaces.setOnClickListener(v -> showRecentPlaces());
        Button timelineMode = button(chapterTimeline ? "Timeline: Current chapter" : "Timeline: Whole book");
        timelineMode.setOnClickListener(v -> {
            chapterTimeline = !chapterTimeline;
            if (!prefs.edit().putBoolean("chapter_timeline", chapterTimeline).commit()) {
                chapterTimeline = !chapterTimeline;
                message("Could not save timeline choice"); return;
            }
            timelineMode.setText(chapterTimeline ? "Timeline: Current chapter" : "Timeline: Whole book");
            refreshPlayerUi();
        });
        repeatButton = button(getString(R.string.repeat_off));
        repeatButton.setOnClickListener(v -> showRepeatControls());
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        backButton = button("−" + (backMs / 1000) + " s");
        backButton.setContentDescription("Skip back " + (backMs / 1000) + " seconds");
        backButton.setOnClickListener(v -> skip(-backMs));
        backButton.setOnLongClickListener(v -> { chooseSkipIntervals(); return true; });
        controls.addView(backButton, weightedControl());
        play = button("Play");
        play.setBackground(control(PLUM, PLUM, PAPER));
        play.setTextColor(ON_ACTION);
        play.setEnabled(false);
        play.setAlpha(0.55f);
        play.setOnClickListener(v -> toggle());
        play.setTextSize(19);
        controls.addView(play, new LinearLayout.LayoutParams(0, dp(64), 1.15f));
        forwardButton = button("+" + (forwardMs / 1000) + " s");
        forwardButton.setContentDescription("Skip forward " + (forwardMs / 1000) + " seconds");
        forwardButton.setOnClickListener(v -> skip(forwardMs));
        forwardButton.setOnLongClickListener(v -> { chooseSkipIntervals(); return true; });
        controls.addView(forwardButton, weightedControl());
        playerCard.addView(controls);
        TextView skipHint = text("Hold skip to change the interval", 12);
        skipHint.setGravity(Gravity.CENTER);
        sleepButton = button("Sleep timer");
        sleepButton.setOnClickListener(v -> chooseSleepTimer());
        sleepButton.setOnLongClickListener(v -> { repeatLastSleepTimer(); return true; });
        LinearLayout.LayoutParams sleepPlace = new LinearLayout.LayoutParams(-1, dp(52));
        sleepPlace.topMargin = dp(10);
        playerCard.addView(sleepButton, sleepPlace);
        TextView speedLabel = text("Playback speed", 16);
        playerCard.addView(speedLabel);
        speed = new Spinner(this);
        speed.setBackgroundTintList(ColorStateList.valueOf(INK));
        speed.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, speeds) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, recycled, parent);
                view.setTextColor(INK);
                if (!"system".equals(uiFont)) view.setTypeface(android.graphics.Typeface.create(uiFont,
                        android.graphics.Typeface.NORMAL));
                return view;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, recycled, parent);
                view.setTextColor(INK);
                view.setBackgroundColor(PAPER);
                if (!"system".equals(uiFont)) view.setTypeface(android.graphics.Typeface.create(uiFont,
                        android.graphics.Typeface.NORMAL));
                return view;
            }
        });
        float savedSpeed = prefs.contains("speed_value") ? prefs.getFloat("speed_value", 1f)
                : new float[]{1f, 1.25f, 1.5f, 2f}[
                Math.max(0, Math.min(3, prefs.getInt("speed_index", 0)))];
        JSONObject selectedForSpeed = find(currentUri);
        if (selectedForSpeed != null)
            savedSpeed = (float) selectedForSpeed.optDouble("playbackSpeed", savedSpeed);
        speedIndex = 2;
        for (int i = 0; i < speedValues.length; i++)
            if (Math.abs(speedValues[i] - savedSpeed) < .001f) { speedIndex = i; break; }
        speed.setSelection(speedIndex);
        speed.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                // Spinner emits an initial/programmatic callback as well as user changes.
                // Recreating Appearance must not write the global default over a book's speed.
                if (selectingBookSpeed || position == speedIndex) return;
                if (position != speedIndex) {
                    if (!prefs.edit().putInt("speed_index", position)
                            .putFloat("speed_value", speedValues[position]).commit()) {
                        speed.setSelection(speedIndex);
                        message("Could not save playback speed");
                        return;
                    }
                    speedIndex = position;
                }
                JSONObject selected = find(currentUri);
                if (selected != null) try {
                    float previous = (float) selected.optDouble("playbackSpeed", 1.0);
                    selected.put("playbackSpeed", speedValues[position]);
                    if (!saveLibraryNow(false)) {
                        selected.put("playbackSpeed", previous);
                        message("Could not save this book's speed");
                    }
                } catch (JSONException ignored) { message("Could not save this book's speed"); }
                applySpeed();
            }
        });
        Button pitchButton = button("Voice pitch");
        pitchButton.setOnClickListener(v -> choosePitch());
        boostButton = button("Voice boost · Off");
        boostButton.setOnClickListener(v -> toggleVoiceBoost());
        silenceButton = button("Skip silence · Off");
        silenceButton.setOnClickListener(v -> toggleSkipSilence());
        Button smartRewind = button("Smart rewind · "
                + (prefs.getBoolean("smart_rewind_enabled", true) ? "On" : "Off"));
        smartRewind.setOnClickListener(v -> {
            boolean enabled = !prefs.getBoolean("smart_rewind_enabled", true);
            if (prefs.edit().putBoolean("smart_rewind_enabled", enabled).commit())
                smartRewind.setText("Smart rewind · " + (enabled ? "On" : "Off"));
            else message("Could not save smart rewind setting");
        });
        companionButton = button("Companion PDF");
        companionButton.setOnClickListener(v -> openCompanionPdf());
        Button driving = button("Large driving controls");
        driving.setOnClickListener(v -> showDrivingControls());
        playerCard.addView(speed);
        LinearLayout extras = new LinearLayout(this);
        Button bookmarks = button("Bookmarks");
        bookmarks.setOnClickListener(v -> showBookmarks());
        extras.addView(bookmarks, weightedControl());
        Button chapters = button("Chapters");
        chapters.setOnClickListener(v -> showChapters());
        extras.addView(chapters, weightedControl());
        Button queue = button("Queue");
        queue.setOnClickListener(v -> showQueue());
        playerCard.addView(extras);
        LinearLayout chapterNavigation = new LinearLayout(this);
        Button previousChapter = button("Previous chapter");
        previousChapter.setOnClickListener(v -> jumpChapter(false));
        chapterNavigation.addView(previousChapter, weightedControl());
        Button nextChapter = button("Next chapter");
        nextChapter.setOnClickListener(v -> jumpChapter(true));
        chapterNavigation.addView(nextChapter, weightedControl());
        advancedToggle = button(advancedOpen ? "Hide listening tools  ↑" : "More listening tools  ↓");
        advancedToggle.setOnClickListener(v -> {
            advancedOpen = !advancedOpen;
            advancedTools.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
            advancedToggle.setText(advancedOpen ? "Hide listening tools  ↑" : "More listening tools  ↓");
        });
        playerCard.addView(advancedToggle);
        advancedTools = new LinearLayout(this);
        advancedTools.setOrientation(LinearLayout.VERTICAL);
        advancedTools.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
        advancedTools.addView(skipHint);
        advancedTools.addView(seekTime);
        advancedTools.addView(recentPlaces);
        advancedTools.addView(timelineMode);
        advancedTools.addView(repeatButton);
        advancedTools.addView(pitchButton);
        advancedTools.addView(boostButton);
        advancedTools.addView(silenceButton);
        advancedTools.addView(smartRewind);
        advancedTools.addView(companionButton);
        advancedTools.addView(driving);
        advancedTools.addView(queue);
        advancedTools.addView(chapterNavigation);
        advancedTools.addView(outputRoute);
        playerCard.addView(advancedTools);
        TextView help = text("Offline player · On-device files only. Use a book's Actions button to edit or remove its listing.", 14);
        help.setTextColor(OUTSIDE);
        playerContent.addView(help);
        setContentView(root);
        applyInterfaceFont(root);
        showPlayer(showingPlayer);
    }

    private void showPlayer(boolean show) {
        showingPlayer = show;
        if (!show) {
            lastLocalSuggestionUrl = null;
            localSuggestionGeneration++;
        }
        syncBackCallback();
        if (!show && search != null) { syncSavedProgress(); renderLibrary(); }
        headerPanel.setVisibility(show ? View.GONE : View.VISIBLE);
        libraryPanel.setVisibility(show ? View.GONE : View.VISIBLE);
        playerPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshMiniPlayer();
        if (show && search != null) {
            search.clearFocus();
            playerPanel.setFocusableInTouchMode(true);
            playerPanel.requestFocus();
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
        playerShortcut.setText(show ? "← Library" : "Player →");
        playerShortcut.setOnClickListener(v -> showPlayer(!showingPlayer));
    }

    private void showAppearance(String page, int scrollY) {
        if (isFinishing() || isDestroyed()) return;
        if (appearanceDialog != null) appearanceDialog.dismissSilently();
        appearancePage = page;
        appearanceDialog = new AppearanceDialog(this, theme, libraryLayout, uiFont, page, scrollY,
                new AppearanceDialog.Listener() {
                    @Override public void navigate(String destination) { showAppearance(destination, 0); }
                    @Override public void layout(String id) { saveAppearance("library_layout", id); }
                    @Override public void colors(String id) { saveAppearance("theme_id", id); }
                    @Override public void icon(LauncherIcons.Icon icon) {
                        int position = appearanceDialog.scrollY();
                        try {
                            LauncherIcons.select(MainActivity.this, icon);
                            showAppearance("icons", position);
                        } catch (RuntimeException error) { message("Could not change app icon. Please try again."); }
                    }
                    @Override public void reset() { resetAppearance(); }
                    @Override public void closed() { appearancePage = null; appearanceDialog = null; }
                });
        appearanceDialog.show();
    }

    private void saveAppearance(String key, String value) {
        if (value.equals(prefs.getString(key, null))) return;
        if (!prefs.edit().putString(key, value).commit()) {
            message("Could not save appearance. Please try again."); return;
        }
        recreate();
    }

    private void resetAppearance() {
        new AlertDialog.Builder(this).setTitle("Reset appearance?")
                .setMessage("Use Split Shelf, Black & Tan colors, the system font and the original Arioso icon. Your books, listening places and playback settings are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset appearance", (dialog, which) -> {
                    LauncherIcons.Icon previous = LauncherIcons.current(this);
                    try { LauncherIcons.select(this, LauncherIcons.ALL[0]); }
                    catch (RuntimeException error) { message("Could not reset app icon. Appearance kept."); return; }
                    if (!prefs.edit().remove("library_layout").remove("compact_library")
                            .remove("theme_id").remove("ui_font").commit()) {
                        try { LauncherIcons.select(this, previous); }
                        catch (RuntimeException error) { message("Could not restore app icon. Choose it again in Appearance."); return; }
                        message("Could not save appearance. Please try again."); return;
                    }
                    recreate();
                }).show();
    }

    private String layoutName() { return "Layout: " + AppearanceDialog.layoutName(libraryLayout); }

    private void chooseLibraryLayout() { showAppearance("layouts", 0); }

    private void chooseInterfaceFont() {
        String[] names = {"System default", "Bookish serif", "Clean sans", "Compact sans"};
        String[] ids = {"system", "serif", "sans-serif", "sans-serif-condensed"};
        int selected = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(uiFont)) selected = i;
        ArrayAdapter<String> previews = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, names) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView label = (TextView) super.getView(position, recycled, parent);
                label.setTypeface("system".equals(ids[position])
                        ? android.graphics.Typeface.DEFAULT
                        : android.graphics.Typeface.create(ids[position], android.graphics.Typeface.NORMAL));
                return label;
            }
        };
        new AlertDialog.Builder(this).setTitle("Interface font")
                .setSingleChoiceItems(previews, selected, (dialog, which) -> {
                    dialog.dismiss();
                    if (ids[which].equals(uiFont)) return;
                    if (!prefs.edit().putString("ui_font", ids[which]).commit()) {
                        message("Could not save interface font"); return;
                    }
                    recreate();
                }).setNegativeButton("Cancel", null).show();
    }

    private Button libraryActionsButton() {
        Button actions = button("More");
        actions.setContentDescription("Library actions and interface font");
        actions.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Library actions")
                .setItems(extraLargeLibrary
                        ? new String[]{"Add on-device audio", "Organize library", "Library backup",
                        "Continue listening", "Playback privacy", "Interface font", "About & licenses"}
                        : new String[]{"Add on-device audio", "Organize library", "Library backup",
                        "Playback privacy", "Interface font", "About & licenses"},
                        (dialog, which) -> {
                            if (which == 0) new AlertDialog.Builder(this).setTitle("Add on-device audio")
                                    .setItems(new String[]{"Choose files", "Choose a folder"},
                                            (d, choice) -> { if (choice == 0) pickFiles(); else pickFolder(); })
                                    .setNegativeButton("Cancel", null).show();
                            else if (which == 1) showLibraryTools();
                            else if (which == 2) chooseBackupAction();
                            else if (extraLargeLibrary && which == 3) showContinueChoices();
                            else if (which == (extraLargeLibrary ? 4 : 3)) choosePlaybackPrivacy();
                            else if (which == (extraLargeLibrary ? 5 : 4)) chooseInterfaceFont();
                            else showAboutAndLicenses();
                        }).setNegativeButton("Cancel", null).show());
        return actions;
    }

    private void jumpToAuthor() {
        Map<String, Integer> letterPositions = new java.util.TreeMap<>();
        for (int i = 0; i < shelfItems.size(); i++) {
            ShelfItem entry = shelfItems.get(i);
            if (!pinnedGroups().contains(groupKey(entry)))
                letterPositions.putIfAbsent(LibraryPresentation.authorInitial(entry.author), i);
        }
        for (int i = 0; i < shelfItems.size(); i++) {
            ShelfItem entry = shelfItems.get(i);
            letterPositions.putIfAbsent(LibraryPresentation.authorInitial(entry.author), i);
        }
        if (letterPositions.isEmpty()) { message("No authors in this view"); return; }
        List<String> letters = new ArrayList<>(letterPositions.keySet());
        new AlertDialog.Builder(this).setTitle("Jump to author")
                .setItems(letters.toArray(new String[0]), (dialog, which) ->
                        shelf.setSelection(letterPositions.get(letters.get(which))))
                .setNegativeButton("Cancel", null).show();
    }

    private void choosePlaybackPrivacy() {
        boolean enabled = prefs.getBoolean("private_playback", false);
        new AlertDialog.Builder(this).setTitle("Playback privacy")
                .setMessage("When on, Android media controls and Arioso's playback notification show "
                        + "Arioso instead of the book title and author. The player and library still show "
                        + "your book details. Other system components may retain details shown before "
                        + "this setting was enabled.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(enabled ? "Show book details" : "Hide book details", (dialog, which) -> {
                    boolean next = !enabled;
                    if (!prefs.edit().putBoolean("private_playback", next).commit()) {
                        message("Could not save playback privacy"); return;
                    }
                    if (playback != null) playback.setPrivatePlayback(next);
                    message(next ? "Book details hidden in media controls"
                            : "Book details shown in media controls");
                }).show();
    }

    private void showAboutAndLicenses() {
        new AlertDialog.Builder(this).setTitle("About & licenses")
                .setItems(new String[]{"Arioso code and icon · MIT License",
                        "Components and content rights", "Privacy policy"}, (dialog, which) ->
                        showLicenseAsset(which == 0 ? "licenses/arioso-mit.txt"
                                        : which == 1 ? "licenses/components.txt" : "licenses/privacy.txt",
                                which == 0 ? "MIT License"
                                        : which == 1 ? "Components and content rights" : "Privacy policy"))
                .setNegativeButton("Close", null).show();
    }

    private void showLicenseAsset(String path, String heading) {
        try (InputStream stream = getAssets().open(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (output.size() + count > 16384) throw new IOException("Notice too large");
                output.write(chunk, 0, count);
            }
            new AlertDialog.Builder(this).setTitle(heading)
                    .setMessage(new String(output.toByteArray(), StandardCharsets.UTF_8))
                    .setPositiveButton("Close", null).show();
        } catch (IOException e) { message("This notice could not be opened"); }
    }

    private void chooseShelf() {
        List<String> choices = new ArrayList<>();
        choices.add("All books");
        choices.add("In progress");
        choices.add("Finished");
        choices.add("Favorites");
        choices.add("Listen later");
        choices.add("Archive");
        choices.add("Recent imports (30 days)");
        Set<String> seen = new HashSet<>();
        List<String> tags = new ArrayList<>();
        for (JSONObject item : books) {
            for (String part : metadata(item, "tags", "").split("[,;]")) {
                String tag = part.trim();
                if (!tag.isEmpty() && tag.length() <= 40
                        && seen.add(tag.toLowerCase(Locale.ROOT))) tags.add(tag);
            }
        }
        tags.sort(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < Math.min(30, tags.size()); i++) choices.add("Tag: " + tags.get(i));
        new AlertDialog.Builder(this).setTitle("Choose shelf")
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    selectedShelf = choices.get(which);
                    renderLibrary();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseSkipIntervals() {
        final int[] backs = {10, 15, 30, 60};
        final int[] forwards = {30, 30, 60, 120};
        String[] options = {"Back 10 s · Forward 30 s", "Back 15 s · Forward 30 s",
                "Back 30 s · Forward 60 s", "Back 60 s · Forward 120 s"};
        new AlertDialog.Builder(this).setTitle("Skip intervals")
                .setItems(options, (dialog, which) -> {
                    int nextBack = backs[which] * 1000;
                    int nextForward = forwards[which] * 1000;
                    if (!prefs.edit().putInt("skip_back_ms", nextBack)
                            .putInt("skip_forward_ms", nextForward).commit()) {
                        message("Could not save skip intervals"); return;
                    }
                    backMs = nextBack;
                    forwardMs = nextForward;
                    backButton.setText("−" + backs[which] + " s");
                    forwardButton.setText("+" + forwards[which] + " s");
                    backButton.setContentDescription("Skip back " + backs[which] + " seconds");
                    forwardButton.setContentDescription("Skip forward " + forwards[which] + " seconds");
                    if (playback != null) playback.setSkipIntervals(backMs, forwardMs);
                }).setNegativeButton("Cancel", null).show();
    }

    private void syncBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        boolean shouldRegister = showingPlayer || selectedSeries != null || selectedAuthor != null;
        if (shouldRegister && !nestedBackRegistered) {
            nestedBackCallback = Api33Back.register(this);
            nestedBackRegistered = true;
        } else if (!shouldRegister && nestedBackRegistered) {
            Api33Back.unregister(this, nestedBackCallback);
            nestedBackCallback = null;
            nestedBackRegistered = false;
        }
    }

    private void navigateBackWithinApp() {
        if (showingPlayer) showPlayer(false);
        else if (selectedSeries != null) {
            selectedSeries = null;
            libraryTab = 1;
            renderLibrary();
        } else if (selectedAuthor != null) {
            selectedAuthor = null;
            libraryTab = 0;
            renderLibrary();
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (showingPlayer || selectedSeries != null || selectedAuthor != null) {
            navigateBackWithinApp();
        } else super.onBackPressed();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, deviceStorageStart());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_AUDIO);
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, deviceStorageStart());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
    }

    private static Uri deviceStorageStart() {
        // The storage root is a navigation starting point; Android 11+ only grants a child folder.
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:");
    }

    private void scanFolder(Uri tree) {
        if (activeFolderScan != null) { message("A folder scan is already running"); return; }
        if (!LocalDocumentPolicy.accepts(tree.getScheme(), tree.getAuthority())) {
            message("Choose a folder stored on this device"); return;
        }
        try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException e) {
            message("Could not keep access to this folder"); return;
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        activeFolderScan = cancel;
        activeFolderTree = tree;
        TextView progress = text("Checking on-device folders…", 16);
        progress.setPadding(dp(18), dp(18), dp(18), dp(18));
        folderScanDialog = new AlertDialog.Builder(this).setTitle("Scanning audio")
                .setView(progress).setNegativeButton("Cancel", (dialog, which) -> cancel.set(true))
                .setOnCancelListener(dialog -> cancel.set(true)).create();
        folderScanDialog.show();
        Set<String> ignored = new HashSet<>();
        try {
            JSONObject rules = new JSONObject(prefs.getString("ignored_folders", "{}"));
            JSONArray ids = rules.optJSONArray(tree.toString());
            if (ids != null) for (int i = 0; i < ids.length(); i++) ignored.add(ids.optString(i));
        } catch (JSONException ignoredRules) { }
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            List<JSONObject> found = new ArrayList<>();
            boolean failed = false;
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                ArrayDeque<String[]> pending = new ArrayDeque<>();
                pending.add(new String[]{rootId, "0"});
                Set<String> visited = new HashSet<>();
                while (!pending.isEmpty() && visited.size() < 1000 && found.size() < 500
                        && !cancel.get()) {
                    String[] folder = pending.removeFirst();
                    if (!visited.add(folder[0])) continue;
                    if (ignored.contains(folder[0])) continue;
                    if (visited.size() % 20 == 0) {
                        int scanned = visited.size();
                        int files = found.size();
                        MAIN.post(() -> {
                            MainActivity activity = owner.get();
                            if (activity != null && activity.activeFolderScan == cancel && !activity.destroyed)
                                progress.setText("Checked " + scanned + " folders; found " + files + " audio files");
                        });
                    }
                    int depth = Integer.parseInt(folder[1]);
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder[0]);
                    List<JSONObject> folderAudio = new ArrayList<>();
                    String folderCover = null;
                    int coverRank = 99;
                    try (Cursor cursor = appContext.getContentResolver().query(children,
                            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                                    DocumentsContract.Document.COLUMN_SIZE,
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                        if (cursor == null) continue;
                        while (!cancel.get() && cursor.moveToNext() && found.size() < 500 && pending.size() < 1000) {
                            String id = cursor.getString(0);
                            String name = cursor.getString(1);
                            String mime = cursor.getString(2);
                            if (id == null || name == null) continue;
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                                if (depth < 8) pending.add(new String[]{id, Integer.toString(depth + 1)});
                                continue;
                            }
                            int rank = localCoverRank(name);
                            if (rank < coverRank && (mime == null || mime.startsWith("image/"))
                                    && (cursor.isNull(3) || cursor.getLong(3) <= 2L * 1024 * 1024)) {
                                folderCover = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString();
                                coverRank = rank;
                            }
                            if (!isAudioDocument(name, mime)) continue;
                            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                            JSONObject item = new JSONObject();
                            SeriesNumberSuggestion.Suggestion suggestion = SeriesNumberSuggestion.fromFilename(name);
                            item.put("uri", document.toString());
                            item.put("treeUri", tree.toString());
                            item.put("name", name);
                            item.put("title", suggestion == null ? titleFromName(name) : suggestion.title);
                            item.put("author", "");
                            item.put("series", suggestion == null ? "" : suggestion.series);
                            item.put("seriesNumber", suggestion == null ? "" : suggestion.number);
                            item.put("seriesSuggested", suggestion != null);
                            item.put("position", 0);
                            item.put("duration", 0);
                            item.put("finished", false);
            item.put("importedAt", System.currentTimeMillis());
                            if (cursor.getColumnCount() > 3 && !cursor.isNull(3))
                                item.put("sourceSize", cursor.getLong(3));
                            if (cursor.getColumnCount() > 4 && !cursor.isNull(4))
                                item.put("sourceModified", cursor.getLong(4));
                            found.add(item);
                            folderAudio.add(item);
                        }
                    }
                    if (folderCover != null) for (JSONObject item : folderAudio)
                        item.put("folderCoverUri", folderCover);
                }
            } catch (RuntimeException | JSONException e) { failed = true; }
            final boolean scanFailed = failed;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity != null && !activity.destroyed && activity.activeFolderScan == cancel) {
                    activity.activeFolderScan = null;
                    activity.activeFolderTree = null;
                    if (activity.folderScanDialog != null) activity.folderScanDialog.dismiss();
                    activity.folderScanDialog = null;
                    if (cancel.get()) {
                        activity.releaseTreeIfUnused(tree.toString());
                        activity.message("Folder scan canceled");
                    } else activity.confirmFolderImport(tree, found, scanFailed);
                }
            });
        });
    }

    private static boolean isAudioDocument(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".m4b") || lower.endsWith(".m4a") || lower.endsWith(".mp3")
                || lower.endsWith(".ogg") || lower.endsWith(".opus")
                || lower.endsWith(".flac") || lower.endsWith(".wav");
    }

    private static int localCoverRank(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                && !lower.endsWith(".png") && !lower.endsWith(".webp")) return 99;
        String stem = lower.substring(0, lower.lastIndexOf('.'));
        if (stem.equals("cover")) return 0;
        if (stem.equals("folder")) return 1;
        if (stem.equals("front")) return 2;
        return 99;
    }

    private void confirmFolderImport(Uri tree, List<JSONObject> found, boolean scanFailed) {
        List<JSONObject> additions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> foundUris = new HashSet<>();
        List<String> changed = new ArrayList<>();
        Map<JSONObject, String> coverUpdates = new LinkedHashMap<>();
        int duplicates = 0;
        for (JSONObject item : found) {
            String uri = item.optString("uri");
            foundUris.add(uri);
            JSONObject existing = find(uri);
            if (existing != null) {
                String suggestedCover = item.optString("folderCoverUri", "");
                if (!suggestedCover.isEmpty() && !suggestedCover.equals(
                        existing.optString("folderCoverUri", "")))
                    coverUpdates.put(existing, suggestedCover);
                long oldSize = existing.optLong("sourceSize", -1);
                long newSize = item.optLong("sourceSize", -1);
                long oldModified = existing.optLong("sourceModified", -1);
                long newModified = item.optLong("sourceModified", -1);
                if ((oldSize >= 0 && newSize >= 0 && oldSize != newSize)
                        || (oldModified > 0 && newModified > 0 && oldModified != newModified))
                    changed.add(bookTitle(existing));
            }
            if (uri.length() > 4096 || find(uri) != null || !seen.add(uri)
                    || books.size() + additions.size() >= 500) continue;
            for (JSONObject current : books)
                if (bookTitle(current).equalsIgnoreCase(bookTitle(item))) { duplicates++; break; }
            additions.add(item);
        }
        List<String> notSeen = new ArrayList<>();
        for (JSONObject current : books)
            if (tree.toString().equals(current.optString("treeUri"))
                    && !foundUris.contains(current.optString("uri"))) notSeen.add(bookTitle(current));
        StringBuilder detail = new StringBuilder();
        int newFolderCovers = 0;
        for (JSONObject item : additions)
            if (!item.optString("folderCoverUri", "").isEmpty()) newFolderCovers++;
        detail.append("New: ").append(additions.size())
                .append(" · Changed size/date: ").append(changed.size())
                .append(" · Folder covers: ").append(coverUpdates.size() + newFolderCovers)
                .append(" · Not seen: ").append(notSeen.size()).append("\n");
        int lines = 0;
        for (JSONObject item : additions) if (lines++ < 8)
            detail.append("+ ").append(bookTitle(item)).append("\n");
        for (String title : changed) if (lines++ < 12) detail.append("~ ").append(title).append("\n");
        for (String title : notSeen) if (lines++ < 16) detail.append("? ").append(title).append("\n");
        if (lines > 16) detail.append("…and more\n");
        if (duplicates > 0) detail.append(duplicates).append(" title(s) resemble existing books.\n");
        if (scanFailed) detail.append("Scan stopped early; not-seen listings may still exist.\n");
        detail.append("No existing listing or original audio will be removed.");
        String scanSummary = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date())
                + "\nNew: " + additions.size() + " · Changed: " + changed.size()
                + " · Not seen: " + notSeen.size()
                + (scanFailed ? "\nScan stopped early." : "\nScan completed.");
        if (!prefs.edit().putString("last_folder_scan", scanSummary).commit())
            message("Could not save folder scan history");
        if (additions.isEmpty() && coverUpdates.isEmpty()) {
            releaseTreeIfUnused(tree.toString());
            new AlertDialog.Builder(this).setTitle("Folder scan differences")
                    .setMessage(detail.toString()).setPositiveButton("Close", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Apply folder scan changes?")
                .setMessage(detail.toString())
                .setNegativeButton("Cancel", (dialog, which) -> releaseTreeIfUnused(tree.toString()))
                .setOnCancelListener(dialog -> releaseTreeIfUnused(tree.toString()))
                .setPositiveButton("Apply", (dialog, which) -> {
                    Map<JSONObject, String> priorCovers = new LinkedHashMap<>();
                    for (Map.Entry<JSONObject, String> update : coverUpdates.entrySet()) try {
                        priorCovers.put(update.getKey(), update.getKey().optString("folderCoverUri", ""));
                        update.getKey().put("folderCoverUri", update.getValue());
                    } catch (JSONException ignored) { }
                    long importedAt = System.currentTimeMillis();
                    for (JSONObject item : additions) try { item.put("importedAt", importedAt); }
                    catch (JSONException ignored) { }
                    int start = books.size();
                    books.addAll(additions);
                    if (!saveLibraryNow(false)) {
                        while (books.size() > start) books.remove(books.size() - 1);
                        for (Map.Entry<JSONObject, String> prior : priorCovers.entrySet()) try {
                            if (prior.getValue().isEmpty()) prior.getKey().remove("folderCoverUri");
                            else prior.getKey().put("folderCoverUri", prior.getValue());
                        } catch (JSONException ignored) { }
                        releaseTreeIfUnused(tree.toString());
                        message("Could not save folder listings"); return;
                    }
                    for (JSONObject updated : coverUpdates.keySet()) {
                        String source = updated.optString("uri");
                        absentArtwork.remove(source);
                        artwork.remove(source);
                    }
                    playerCoverUri = null;
                    renderLibrary();
                    if (!additions.isEmpty()) rememberLastImport(additions);
                    for (JSONObject item : additions) readImportedMetadata(item);
                    message("Added " + additions.size() + " files; updated "
                            + coverUpdates.size() + " local covers");
                }).show();
    }

    private void releaseTreeIfUnused(String treeUri) {
        for (JSONObject book : books)
            if (treeUri.equals(book.optString("treeUri"))) return;
        releaseReadGrant(Uri.parse(treeUri));
    }

    private void pickReplacement(JSONObject item) {
        relinkOriginalUri = item.optString("uri");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_RELINK);
    }

    private void showLibraryTools() {
        new AlertDialog.Builder(this).setTitle("Organize library")
                .setItems(new String[]{"Bulk edit selected books", "Preview filename titles",
                        "Series number conflicts", "Editions of one title",
                        "Missing files and saved access", "Database integrity", "Undo last import",
                        "Rescan imported folder", "Ignore subfolders", "Completion history",
                        "Verify file fingerprint", "Undo last metadata edit",
                        "Undo last backup restore", "Last folder scan", "Review missing details",
                        "Review matching file fingerprints", "Listening history",
                        "Listening statistics", "All bookmarks", "Android Auto access"},
                        (dialog, which) -> {
                    if (which == 0) chooseBulkBooks(false);
                    else if (which == 1) chooseBulkBooks(true);
                    else if (which == 2) showSeriesConflicts();
                    else if (which == 3) showEditions();
                    else if (which == 4) checkFileAccess();
                    else if (which == 5) checkDatabaseIntegrity();
                    else if (which == 6) undoLastImport();
                    else if (which == 7 || which == 8) chooseImportedFolder(which == 8);
                    else if (which == 9) showCompletionHistory();
                    else if (which == 10) chooseFingerprintBook();
                    else if (which == 11) undoLastMetadataEdit();
                    else if (which == 12) undoLastBackupRestore();
                    else if (which == 13) new AlertDialog.Builder(this)
                            .setTitle("Last folder scan")
                            .setMessage(prefs.getString("last_folder_scan", "No completed folder scan recorded"))
                            .setPositiveButton("Close", null).show();
                    else if (which == 14) reviewMissingDetails();
                    else if (which == 15) showMatchingFingerprints();
                    else if (which == 16) showListeningHistory();
                    else if (which == 17) showListeningStatistics();
                    else if (which == 18) showAllBookmarks("");
                    else configureAndroidAuto();
                }).setNegativeButton("Close", null).show();
    }

    private void showListeningHistory() {
        syncSavedProgress();
        List<JSONObject> recent = new ArrayList<>();
        for (JSONObject item : books) if (item.optLong("lastPlayedAt") > 0) recent.add(item);
        recent.sort((a, b) -> Long.compare(b.optLong("lastPlayedAt"), a.optLong("lastPlayedAt")));
        if (recent.isEmpty()) { message("No listening history yet"); return; }
        int count = Math.min(50, recent.size());
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            JSONObject item = recent.get(i);
            labels[i] = bookTitle(item) + " · " + java.text.DateFormat.getDateInstance()
                    .format(new java.util.Date(item.optLong("lastPlayedAt")));
        }
        new AlertDialog.Builder(this).setTitle("Listening history")
                .setItems(labels, (dialog, which) -> { open(recent.get(which)); showPlayer(true); })
                .setNeutralButton("Clear history", (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle("Clear listening history?")
                                .setMessage("Book progress and finished status will stay saved.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Clear", (confirm, button) -> clearListeningHistory())
                                .show())
                .setNegativeButton("Close", null).show();
    }

    private void clearListeningHistory() {
        savePosition();
        if (!LibraryStore.get(this).clearPlayedAt()) { message("Could not clear history"); return; }
        for (JSONObject item : books) item.remove("lastPlayedAt");
        renderLibrary();
        message("Listening history cleared");
    }

    private void showListeningStatistics() {
        boolean enabled = prefs.getBoolean("listening_stats_enabled", false);
        long minutes = prefs.getLong("listening_ms", 0) / 60000;
        int finished = 0;
        for (JSONObject item : books) if (item.optBoolean("finished")) finished++;
        new AlertDialog.Builder(this).setTitle("Private listening statistics")
                .setMessage((enabled ? "Tracking is on" : "Tracking is off")
                        + "\nTime listened since enabling: " + (minutes / 60) + " h "
                        + (minutes % 60) + " min\nFinished books on this device: " + finished
                        + "\n\nTime is counted only while playback runs. It stays in Arioso's private storage.")
                .setNeutralButton("Reset time", (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle("Reset listening time?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Reset", (confirm, button) -> {
                                    if (!prefs.edit().putLong("listening_ms", 0).commit())
                                        message("Could not reset listening time");
                                    else if (playback != null) playback.resetStatisticsCheckpoint();
                                }).show())
                .setPositiveButton(enabled ? "Turn off" : "Turn on", (dialog, which) -> {
                    if (!prefs.edit().putBoolean("listening_stats_enabled", !enabled).commit())
                        message("Could not save statistics setting");
                    else if (playback != null) playback.resetStatisticsCheckpoint();
                }).setNegativeButton("Close", null).show();
    }

    private void configureAndroidAuto() {
        boolean enabled = prefs.getBoolean("auto_browse_enabled", false);
        new AlertDialog.Builder(this).setTitle("Android Auto access")
                .setMessage("Allow Android Auto on this phone to browse local book titles"
                        + " and control playback? This shares titles with the car display."
                        + " Private playback hides the library. No network access is added to Arioso.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(enabled ? "Turn off" : "Turn on", (dialog, which) -> {
                    if (!prefs.edit().putBoolean("auto_browse_enabled", !enabled).commit())
                        message("Could not save Android Auto access");
                    else message(enabled ? "Android Auto access off" : "Android Auto access on");
                }).show();
    }

    private void showMatchingFingerprints() {
        Map<String, List<JSONObject>> byHash = new LinkedHashMap<>();
        int baselines = 0;
        for (JSONObject item : books) {
            String hash = item.optString("sourceSha256", "");
            if (!hash.matches("[0-9a-f]{64}")) continue;
            baselines++;
            byHash.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(item);
        }
        StringBuilder report = new StringBuilder(baselines + " book(s) have saved fingerprints."
                + " Matches below may be stale if a file changed since its last check.\n");
        int groups = 0;
        for (List<JSONObject> same : byHash.values()) {
            if (same.size() < 2) continue;
            groups++;
            if (groups > 15) continue;
            report.append("\nMatching content baseline:\n");
            for (int i = 0; i < Math.min(8, same.size()); i++)
                report.append("• ").append(bookTitle(same.get(i))).append("\n");
            if (same.size() > 8) report.append("…and ").append(same.size() - 8).append(" more\n");
        }
        if (groups == 0) report.append("\nNo matching saved fingerprints yet.");
        if (groups > 15) report.append("\n…and ").append(groups - 15).append(" more groups");
        new AlertDialog.Builder(this).setTitle("Possible duplicate audio")
                .setMessage(report.toString())
                .setNeutralButton("Check a file", (dialog, which) -> chooseFingerprintBook())
                .setPositiveButton("Close", null).show();
    }

    private void reviewMissingDetails() {
        List<JSONObject> needsReview = new ArrayList<>();
        for (JSONObject item : books) {
            if (metadata(item, "author", "").isEmpty() || bookTitle(item).isEmpty()
                    || item.optBoolean("seriesSuggested")) needsReview.add(item);
        }
        if (needsReview.isEmpty()) { message("No incomplete or suggested details to review"); return; }
        needsReview.sort((a, b) -> LibraryOrdering.compareNatural(bookTitle(a), bookTitle(b)));
        int count = Math.min(100, needsReview.size());
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            JSONObject item = needsReview.get(i);
            labels[i] = bookTitle(item) + " · " + (metadata(item, "author", "").isEmpty()
                    ? "missing author" : "suggested series");
        }
        new AlertDialog.Builder(this).setTitle("Review book details"
                        + (needsReview.size() > count ? " · first " + count : ""))
                .setItems(labels, (dialog, which) -> editBook(needsReview.get(which)))
                .setNegativeButton("Close", null).show();
    }

    private void rememberMetadataUndo(List<JSONObject> prior) {
        JSONArray entries = new JSONArray();
        try {
            for (JSONObject item : prior) {
                JSONObject limited = new JSONObject();
                limited.put("uri", item.optString("uri"));
                for (String field : UNDO_FIELDS) if (item.has(field))
                    limited.put(field, item.opt(field));
                entries.put(limited);
            }
        } catch (JSONException e) { message("Edit saved; undo record could not be prepared"); return; }
        String serialized = entries.toString();
        if (serialized.length() > 2 * 1024 * 1024) {
            message("Edit saved; undo record exceeded its limit"); return;
        }
        if (!prefs.edit().putString("last_metadata_edit", serialized).commit())
            message("Edit saved; could not save undo record");
    }

    private void undoLastMetadataEdit() {
        JSONArray prior;
        try { prior = new JSONArray(prefs.getString("last_metadata_edit", "[]")); }
        catch (JSONException e) { message("Metadata undo record is unavailable"); return; }
        if (prior.length() == 0) { message("No metadata edit to undo"); return; }
        new AlertDialog.Builder(this).setTitle("Undo last metadata edit?")
                .setMessage("Restore prior book details for " + prior.length()
                        + " listing(s)? Current listening progress, bookmarks, file links and covers stay in place.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo", (dialog, which) -> {
                    List<JSONObject> previous = new ArrayList<>(books);
                    List<JSONObject> updated = new ArrayList<>(books);
                    int restored = 0;
                    try {
                        for (int i = 0; i < prior.length(); i++) {
                            JSONObject old = prior.optJSONObject(i);
                            if (old == null) continue;
                            JSONObject live = find(old.optString("uri"));
                            if (live == null) continue;
                            JSONObject copy = new JSONObject(live.toString());
                            for (String field : UNDO_FIELDS) {
                                if (old.has(field)) copy.put(field, old.opt(field));
                                else copy.remove(field);
                            }
                            updated.set(updated.indexOf(live), copy);
                            restored++;
                        }
                    } catch (JSONException e) { message("Could not prepare metadata undo"); return; }
                    if (restored == 0) { message("Edited listings are no longer present"); return; }
                    books.clear(); books.addAll(updated);
                    if (!saveLibraryNow(false)) {
                        books.clear(); books.addAll(previous);
                        message("Could not undo metadata; current details were kept"); return;
                    }
                    if (!prefs.edit().remove("last_metadata_edit").commit())
                        message("Details restored, but the undo record could not be cleared");
                    renderLibrary();
                    message("Restored details for " + restored + " book(s)");
                }).show();
    }

    private void chooseFingerprintBook() {
        if (books.isEmpty()) { message("Add a book first"); return; }
        List<JSONObject> ordered = new ArrayList<>(books);
        ordered.sort((a, b) -> bookTitle(a).compareToIgnoreCase(bookTitle(b)));
        String[] labels = new String[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) labels[i] = bookTitle(ordered.get(i));
        new AlertDialog.Builder(this).setTitle("Verify one local audio file")
                .setMessage("A streaming SHA-256 check can detect same-size changes. It may take time for a large book and reads at most 2 GB.")
                .setPositiveButton("Choose book", (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle("Choose a book")
                                .setItems(labels, (d, index) -> checkFingerprint(ordered.get(index)))
                                .setNegativeButton("Cancel", null).show())
                .setNegativeButton("Cancel", null).show();
    }

    private void checkFingerprint(JSONObject item) {
        if (activeFingerprintCheck != null) { message("A fingerprint check is already running"); return; }
        String uri = item.optString("uri");
        if (!isOnDeviceUri(uri)) { message("Choose an on-device file"); return; }
        AtomicBoolean cancel = new AtomicBoolean(false);
        activeFingerprintCheck = cancel;
        AlertDialog waiting = new AlertDialog.Builder(this).setTitle("Checking file content")
                .setMessage("Reading the selected local audio file…")
                .setNegativeButton("Cancel", (dialog, which) -> cancel.set(true))
                .setOnCancelListener(dialog -> cancel.set(true)).create();
        waiting.show();
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            String hash = null;
            String problem = null;
            try (InputStream stream = appContext.getContentResolver().openInputStream(Uri.parse(uri))) {
                hash = FileFingerprint.sha256(stream, cancel::get);
            } catch (IOException | RuntimeException e) {
                problem = cancel.get() ? "Fingerprint check canceled" : "Could not read this file or it exceeds 2 GB";
            }
            String result = hash;
            String error = problem;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity == null || activity.destroyed || activity.activeFingerprintCheck != cancel) return;
                activity.activeFingerprintCheck = null;
                waiting.dismiss();
                if (error != null) { activity.message(error); return; }
                JSONObject live = activity.find(uri);
                if (live == null) { activity.message("Book listing changed during the check"); return; }
                String previous = live.optString("sourceSha256", "");
                if (previous.isEmpty()) activity.saveFingerprint(live, result, "Fingerprint baseline saved");
                else if (previous.equals(result)) activity.message("File content matches its saved fingerprint");
                else new AlertDialog.Builder(activity).setTitle("File content changed")
                        .setMessage("This book differs from its saved fingerprint even if its size is unchanged. Keep the old baseline until you verify the replacement.")
                        .setNegativeButton("Keep old baseline", null)
                        .setPositiveButton("Trust current file", (dialog, which) ->
                                activity.saveFingerprint(live, result, "New file fingerprint saved"))
                        .show();
            });
        });
    }

    private void saveFingerprint(JSONObject item, String hash, String success) {
        String previous = item.optString("sourceSha256", "");
        try {
            item.put("sourceSha256", hash);
            if (saveLibraryNow(false)) { message(success); return; }
            if (previous.isEmpty()) item.remove("sourceSha256");
            else item.put("sourceSha256", previous);
        } catch (JSONException ignored) { }
        message("Could not save the file fingerprint");
    }

    private void showCompletionHistory() {
        JSONArray history;
        try { history = new JSONArray(prefs.getString("completion_history", "[]")); }
        catch (JSONException e) { message("Completion history is unavailable"); return; }
        List<String> labels = new ArrayList<>();
        List<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < Math.min(100, history.length()); i++) {
            JSONObject event = history.optJSONObject(i);
            JSONObject item = event == null ? null : find(event.optString("uri"));
            if (item == null) continue;
            entries.add(item);
            labels.add(bookTitle(item) + " · " + android.text.format.DateFormat
                    .getDateFormat(this).format(new java.util.Date(event.optLong("at"))));
        }
        if (entries.isEmpty()) { message("No completed books recorded yet"); return; }
        new AlertDialog.Builder(this).setTitle("Private completion history")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    open(entries.get(which)); showPlayer(true);
                }).setNeutralButton("Clear history", (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle("Clear completion history?")
                                .setMessage("This clears the private history list. Books and their finished status stay in your library.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Clear", (confirm, action) -> {
                                    if (!prefs.edit().remove("completion_history").commit())
                                        message("Could not clear completion history");
                                    else message("Completion history cleared");
                                }).show())
                .setNegativeButton("Close", null).show();
    }

    private void chooseImportedFolder(boolean ignoredFolders) {
        Set<String> trees = new HashSet<>();
        for (JSONObject item : books) {
            String tree = item.optString("treeUri", "");
            if (!tree.isEmpty()) trees.add(tree);
        }
        if (trees.isEmpty()) { message("No imported folders are saved"); return; }
        List<String> sorted = new ArrayList<>(trees);
        Collections.sort(sorted);
        String[] labels = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            try { labels[i] = DocumentsContract.getTreeDocumentId(Uri.parse(sorted.get(i))); }
            catch (RuntimeException e) { labels[i] = "Folder " + (i + 1); }
        }
        new AlertDialog.Builder(this).setTitle(ignoredFolders ? "Choose folder to set exclusions" : "Rescan folder")
                .setItems(labels, (dialog, which) -> {
                    Uri tree = Uri.parse(sorted.get(which));
                    if (ignoredFolders) showIgnoredSubfolders(tree);
                    else scanFolder(tree);
                }).setNegativeButton("Cancel", null).show();
    }

    private void showIgnoredSubfolders(Uri tree) {
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            List<String> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                ArrayDeque<String[]> pending = new ArrayDeque<>();
                Set<String> visited = new HashSet<>();
                pending.add(new String[]{rootId, "", "0"});
                while (!pending.isEmpty() && visited.size() < 1000 && ids.size() < 100) {
                    String[] folder = pending.removeFirst();
                    if (!visited.add(folder[0])) continue;
                    int depth = Integer.parseInt(folder[2]);
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder[0]);
                    try (Cursor cursor = appContext.getContentResolver().query(children,
                            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                        if (cursor != null) while (cursor.moveToNext() && ids.size() < 100) {
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                                String id = cursor.getString(0);
                                String name = cursor.getString(1);
                                if (id == null || name == null) continue;
                                String path = folder[1].isEmpty() ? name : folder[1] + "/" + name;
                                ids.add(id);
                                names.add(path);
                                if (depth < 7 && pending.size() < 1000)
                                    pending.add(new String[]{id, path, Integer.toString(depth + 1)});
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) { }
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity == null || activity.destroyed) return;
                if (ids.isEmpty()) { activity.message("No readable subfolders found"); return; }
                JSONObject rules;
                try { rules = new JSONObject(activity.prefs.getString("ignored_folders", "{}")); }
                catch (JSONException e) { rules = new JSONObject(); }
                Set<String> prior = new HashSet<>();
                JSONArray saved = rules.optJSONArray(tree.toString());
                if (saved != null) for (int i = 0; i < saved.length(); i++) prior.add(saved.optString(i));
                boolean[] selected = new boolean[ids.size()];
                for (int i = 0; i < selected.length; i++) selected[i] = prior.contains(ids.get(i));
                JSONObject finalRules = rules;
                new AlertDialog.Builder(activity).setTitle("Skip these subfolders on rescan")
                        .setMultiChoiceItems(names.toArray(new String[0]), selected,
                                (dialog, which, value) -> selected[which] = value)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", (dialog, which) -> {
                            Set<String> retained = new HashSet<>(prior);
                            for (int i = 0; i < ids.size(); i++) {
                                if (selected[i]) retained.add(ids.get(i));
                                else retained.remove(ids.get(i));
                            }
                            JSONArray excluded = new JSONArray();
                            for (String id : retained) excluded.put(id);
                            try {
                                finalRules.put(tree.toString(), excluded);
                                if (!activity.prefs.edit().putString("ignored_folders", finalRules.toString()).commit())
                                    activity.message("Could not save excluded folders");
                                else activity.message("Folder exclusions saved");
                            } catch (JSONException e) { activity.message("Could not save excluded folders"); }
                        }).show();
            });
        });
    }

    private void rememberLastImport(List<JSONObject> added) {
        JSONArray uris = new JSONArray();
        for (JSONObject item : added) uris.put(item.optString("uri"));
        if (!prefs.edit().putString("last_import_uris", uris.toString()).commit())
            message("Could not save the undo record");
    }

    private void undoLastImport() {
        JSONArray saved;
        try { saved = new JSONArray(prefs.getString("last_import_uris", "[]")); }
        catch (JSONException e) { message("Last import record is unavailable"); return; }
        if (saved.length() == 0) { message("No import to undo"); return; }
        Set<String> remove = new HashSet<>();
        for (int i = 0; i < saved.length(); i++) remove.add(saved.optString(i));
        List<JSONObject> removed = new ArrayList<>();
        for (JSONObject item : books) if (remove.contains(item.optString("uri"))) removed.add(item);
        if (removed.isEmpty()) { message("Those imported books are no longer listed"); return; }
        new AlertDialog.Builder(this).setTitle("Undo last import?")
                .setMessage("Remove " + removed.size() + " listing(s) from Arioso? Original audio files stay untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo", (dialog, which) -> {
                    boolean selected = remove.contains(currentUri);
                    if (selected && playback != null) { playback.pause(); playback.saveNow(); playback.clear(); }
                    List<JSONObject> previous = new ArrayList<>(books);
                    books.removeAll(removed);
                    if (!saveLibraryNow(false)) {
                        books.clear(); books.addAll(previous);
                        message("Could not undo the import; listings were kept"); return;
                    }
                    if (selected) {
                        currentUri = null;
                        if (!prefs.edit().remove("selected_uri").commit())
                            message("Import undone, but the player selection could not be cleared");
                        refreshPlayerUi();
                    }
                    for (JSONObject item : removed) {
                        releaseReadGrant(Uri.parse(item.optString("uri")));
                        String tree = item.optString("treeUri", "");
                        if (!tree.isEmpty()) releaseTreeIfUnused(tree);
                    }
                    if (!prefs.edit().remove("last_import_uris").commit())
                        message("Import undone, but the undo record could not be cleared");
                    renderLibrary();
                    message("Last import undone; audio files kept");
                }).show();
    }

    private void chooseBulkBooks(boolean filenameTitles) {
        if (books.isEmpty()) { message("Add audio files first"); return; }
        List<JSONObject> ordered = new ArrayList<>(books);
        ordered.sort((a, b) -> LibraryOrdering.compare(
                metadata(a, "author", UNKNOWN_AUTHOR), metadata(a, "series", STANDALONE),
                metadata(a, "seriesNumber", ""), bookTitle(a), a.optString("uri"),
                metadata(b, "author", UNKNOWN_AUTHOR), metadata(b, "series", STANDALONE),
                metadata(b, "seriesNumber", ""), bookTitle(b), b.optString("uri")));
        String[] labels = new String[ordered.size()];
        boolean[] checked = new boolean[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            JSONObject item = ordered.get(i);
            labels[i] = metadata(item, "author", UNKNOWN_AUTHOR) + " · " + bookTitle(item);
        }
        new AlertDialog.Builder(this).setTitle(filenameTitles ? "Preview filename titles" : "Choose books to edit")
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> checked[which] = selected)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (dialog, which) -> {
                    List<String> uris = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++)
                        if (checked[i]) uris.add(ordered.get(i).optString("uri"));
                    if (uris.isEmpty()) { message("Select at least one book"); return; }
                    if (filenameTitles) previewFilenameTitles(uris);
                    else chooseBulkField(uris);
                }).show();
    }

    private void chooseBulkField(List<String> uris) {
        String[] labels = {"Author / merge pen name", "Author sort name", "Author aliases",
                "Series / merge or move", "Series order", "Narrator", "Edition", "Shelf tags",
                "Multi-part title / split or merge", "Part number"};
        String[] keys = {"author", "authorSort", "authorAliases", "series", "seriesNumber",
                "narrator", "edition", "tags", "partGroup", "partOrder"};
        new AlertDialog.Builder(this).setTitle("Edit " + uris.size() + " books")
                .setItems(labels, (dialog, which) -> {
                    EditText value = new EditText(this);
                    value.setSingleLine(true);
                    value.setHint("New value; leave blank to clear");
                    value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(180)});
                    new AlertDialog.Builder(this).setTitle(labels[which])
                            .setView(value).setNegativeButton("Cancel", null)
                            .setPositiveButton("Preview", (d, button) -> {
                                String entered = value.getText().toString().trim();
                                if (("seriesNumber".equals(keys[which]) || "partOrder".equals(keys[which]))
                                        && !entered.isEmpty() && LibraryOrdering.parseOrder(entered) == null) {
                                    message("Use a number between -9999 and 9999 with up to 2 decimals"); return;
                                }
                                String preview = uris.size() + " book(s) will have " + labels[which]
                                        + " set to " + (entered.isEmpty() ? "blank" : entered)
                                        + ("series".equals(keys[which]) && !entered.isEmpty()
                                        ? ". Unnumbered books receive distinct numbers after existing books" : "")
                                        + ". Titles, file links, and listening progress stay in place.";
                                StringBuilder examples = new StringBuilder(preview);
                                int shown = 0;
                                for (String uri : uris) {
                                    JSONObject book = find(uri);
                                    if (book == null || shown++ >= 8) continue;
                                    examples.append("\n").append(bookTitle(book)).append(": ")
                                            .append(shortPreview(metadata(book, keys[which], "")))
                                            .append(" → ").append(shortPreview(entered));
                                }
                                if (uris.size() > 8) examples.append("\n…and ")
                                        .append(uris.size() - 8).append(" more books");
                                new AlertDialog.Builder(this).setTitle("Apply bulk edit?")
                                        .setMessage(examples.toString()).setNegativeButton("Cancel", null)
                                        .setPositiveButton("Apply", (confirm, action) ->
                                                applyBulkField(uris, keys[which], entered)).show();
                            }).show();
                }).show();
    }

    private void applyBulkField(List<String> uris, String key, String value) {
        Set<String> chosen = new HashSet<>(uris);
        if ("partOrder".equals(key) && !value.isEmpty() && chosen.size() > 1) {
            message("Set each part number separately so parts do not share one order"); return;
        }
        Map<String, String> assignedPartOrder = new HashMap<>();
        Map<String, String> assignedSeriesOrder = new HashMap<>();
        if ("series".equals(key) && !value.isEmpty()) {
            Map<String, BigDecimal> highest = new HashMap<>();
            for (JSONObject item : books) {
                if (!value.equalsIgnoreCase(metadata(item, "series", ""))) continue;
                String author = metadata(item, "author", "").toLowerCase(Locale.ROOT);
                BigDecimal number = orderOf(item);
                if (number != null && number.compareTo(highest.getOrDefault(author, BigDecimal.ZERO)) > 0)
                    highest.put(author, number);
            }
            List<JSONObject> unnumbered = new ArrayList<>();
            for (JSONObject item : books) if (chosen.contains(item.optString("uri"))
                    && orderOf(item) == null) unnumbered.add(item);
            unnumbered.sort((a, b) -> bookTitle(a).compareToIgnoreCase(bookTitle(b)));
            for (JSONObject item : unnumbered) {
                String author = metadata(item, "author", "").toLowerCase(Locale.ROOT);
                BigDecimal number = highest.getOrDefault(author, BigDecimal.ZERO)
                        .setScale(0, java.math.RoundingMode.UP).add(BigDecimal.ONE);
                if (number.compareTo(new BigDecimal("9999")) > 0) {
                    message("Series number limit reached"); return;
                }
                highest.put(author, number);
                assignedSeriesOrder.put(item.optString("uri"), number.toPlainString());
            }
        }
        if ("partGroup".equals(key) && !value.isEmpty()) {
            Map<String, BigDecimal> next = new HashMap<>();
            for (JSONObject item : books) {
                String group = metadata(item, "author", "") + "\u0000"
                        + metadata(item, "series", "") + "\u0000" + value;
                if (!chosen.contains(item.optString("uri"))
                        && value.equalsIgnoreCase(metadata(item, "partGroup", ""))) {
                    BigDecimal existing = LibraryOrdering.parseOrder(metadata(item, "partOrder", ""));
                    if (existing != null && existing.signum() > 0
                            && existing.compareTo(next.getOrDefault(group, BigDecimal.ZERO)) > 0)
                        next.put(group, existing);
                }
            }
            List<JSONObject> ordered = new ArrayList<>();
            for (JSONObject item : books) if (chosen.contains(item.optString("uri"))) ordered.add(item);
            ordered.sort((a, b) -> LibraryOrdering.compare(
                    metadata(a, "author", ""), metadata(a, "series", ""),
                    metadata(a, "seriesNumber", ""), bookTitle(a), a.optString("uri"),
                    metadata(b, "author", ""), metadata(b, "series", ""),
                    metadata(b, "seriesNumber", ""), bookTitle(b), b.optString("uri")));
            for (JSONObject item : ordered) {
                String group = metadata(item, "author", "") + "\u0000"
                        + metadata(item, "series", "") + "\u0000" + value;
                BigDecimal number = next.getOrDefault(group, BigDecimal.ZERO).setScale(0,
                        java.math.RoundingMode.UP).add(BigDecimal.ONE);
                if (number.compareTo(new BigDecimal("9999")) > 0) {
                    message("Too many parts in this title"); return;
                }
                next.put(group, number);
                assignedPartOrder.put(item.optString("uri"), number.toPlainString());
            }
        }
        List<JSONObject> previous = new ArrayList<>(books);
        List<JSONObject> updated = new ArrayList<>(books.size());
        try {
            for (JSONObject item : books) {
                JSONObject copy = new JSONObject(item.toString());
                if (chosen.contains(copy.optString("uri"))) {
                    copy.put(key, value);
                    copy.put("manualEdited", true);
                    if ("seriesNumber".equals(key)) copy.put("seriesSuggested", false);
                    if ("series".equals(key) && value.isEmpty()) copy.put("seriesNumber", "");
                    else if ("series".equals(key)
                            && assignedSeriesOrder.containsKey(copy.optString("uri")))
                        copy.put("seriesNumber", assignedSeriesOrder.get(copy.optString("uri")));
                    if ("partGroup".equals(key) && value.isEmpty()) copy.put("partOrder", "");
                    else if ("partGroup".equals(key))
                        copy.put("partOrder", assignedPartOrder.get(copy.optString("uri")));
                }
                updated.add(copy);
            }
        } catch (JSONException e) { message("Could not prepare bulk edit"); return; }
        books.clear(); books.addAll(updated);
        if (!saveLibraryNow(false)) {
            books.clear(); books.addAll(previous);
            message("Could not save bulk edit; the library was kept"); return;
        }
        List<JSONObject> undo = new ArrayList<>();
        for (JSONObject old : previous) if (chosen.contains(old.optString("uri"))) undo.add(old);
        rememberMetadataUndo(undo);
        renderLibrary();
        message("Updated " + uris.size() + " books");
    }

    private void previewFilenameTitles(List<String> uris) {
        StringBuilder preview = new StringBuilder();
        int changes = 0;
        for (String uri : uris) {
            JSONObject item = find(uri);
            if (item == null) continue;
            String proposed = titleFromName(metadata(item, "name", bookTitle(item)));
            if (proposed.equals(bookTitle(item))) continue;
            if (changes < 12) preview.append(bookTitle(item)).append(" → ").append(proposed).append('\n');
            changes++;
        }
        if (changes == 0) { message("Selected titles already match their filenames"); return; }
        if (changes > 12) preview.append("…and ").append(changes - 12).append(" more");
        final int count = changes;
        new AlertDialog.Builder(this).setTitle("Apply " + count + " filename title(s)?")
                .setMessage(preview.toString()).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    Set<String> chosen = new HashSet<>(uris);
                    List<JSONObject> previous = new ArrayList<>(books);
                    List<JSONObject> updated = new ArrayList<>();
                    try {
                        for (JSONObject item : books) {
                            JSONObject copy = new JSONObject(item.toString());
                            if (chosen.contains(copy.optString("uri")))
                                copy.put("title", titleFromName(metadata(copy, "name", bookTitle(copy))));
                            updated.add(copy);
                        }
                    } catch (JSONException e) { message("Could not update titles"); return; }
                    books.clear(); books.addAll(updated);
                    if (!saveLibraryNow(false)) {
                        books.clear(); books.addAll(previous);
                        message("Could not save titles; the library was kept"); return;
                    }
                    List<JSONObject> undo = new ArrayList<>();
                    for (JSONObject old : previous) if (chosen.contains(old.optString("uri"))) undo.add(old);
                    rememberMetadataUndo(undo);
                    renderLibrary();
                    message("Updated " + count + " titles");
                }).show();
    }

    private void showSeriesConflicts() {
        Map<String, List<String>> positions = new LinkedHashMap<>();
        Map<String, Set<Integer>> wholeNumbers = new LinkedHashMap<>();
        for (JSONObject item : books) {
            String author = metadata(item, "author", UNKNOWN_AUTHOR);
            String series = metadata(item, "series", "");
            BigDecimal order = orderOf(item);
            if (series.isEmpty() || order == null) continue;
            String key = author + " · " + series + " #" + order.stripTrailingZeros().toPlainString();
            positions.computeIfAbsent(key, unused -> new ArrayList<>()).add(bookTitle(item));
            if (order.stripTrailingZeros().scale() <= 0 && order.signum() > 0 && order.intValue() <= 9999)
                wholeNumbers.computeIfAbsent(author + " · " + series,
                        unused -> new java.util.TreeSet<>()).add(order.intValue());
        }
        StringBuilder report = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, List<String>> entry : positions.entrySet())
            if (entry.getValue().size() > 1) {
                if (count < 20) report.append(entry.getKey()).append(": ")
                        .append(String.join(", ", entry.getValue())).append("\n\n");
                count++;
            }
        if (count == 0) report.append("No repeated series numbers found.\n");
        else if (count > 20) report.append("…and ").append(count - 20).append(" more conflicts");
        int gaps = 0;
        for (Map.Entry<String, Set<Integer>> entry : wholeNumbers.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            int previous = 0;
            for (int number : entry.getValue()) {
                if (previous > 0 && number > previous + 1) {
                    if (gaps < 20) report.append("\nGap: ").append(entry.getKey())
                            .append(" #").append(previous + 1)
                            .append(number > previous + 2 ? "–" + (number - 1) : "");
                    gaps++;
                }
                previous = number;
            }
        }
        if (gaps > 20) report.append("\n…and ").append(gaps - 20).append(" more gaps");
        if (gaps == 0) report.append("\nNo internal whole-number gaps found.");
        new AlertDialog.Builder(this).setTitle("Series order · " + count + " repeats, " + gaps + " gaps")
                .setMessage(report.toString()).setPositiveButton("Close", null).show();
    }

    private void showEditions() {
        Map<String, List<String>> titles = new LinkedHashMap<>();
        for (JSONObject item : books) {
            String key = metadata(item, "author", UNKNOWN_AUTHOR) + " · " + bookTitle(item);
            titles.computeIfAbsent(key.toLowerCase(Locale.ROOT), unused -> new ArrayList<>())
                    .add(key + " — " + metadata(item, "edition", "Unlabeled edition")
                            + " / " + metadata(item, "narrator", "Unknown narrator"));
        }
        List<String> groups = new ArrayList<>();
        for (List<String> entries : titles.values()) if (entries.size() > 1) groups.addAll(entries);
        String report = groups.isEmpty() ? "No titles with multiple editions found."
                : String.join("\n", groups.subList(0, Math.min(40, groups.size())));
        if (groups.size() > 40) report += "\n…and " + (groups.size() - 40) + " more";
        new AlertDialog.Builder(this).setTitle("Editions under one title")
                .setMessage(report).setPositiveButton("Close", null).show();
    }

    private void checkDatabaseIntegrity() {
        artworkWorker.execute(() -> {
            String report = LibraryStore.get(getApplicationContext()).integrityReport();
            MAIN.post(() -> { if (!destroyed) new AlertDialog.Builder(this)
                    .setTitle("Local database integrity").setMessage(report)
                    .setPositiveButton("Close", null).show(); });
        });
    }

    private void checkFileAccess() {
        List<JSONObject> snapshot = new ArrayList<>();
        for (JSONObject item : books) try { snapshot.add(new JSONObject(item.toString())); }
        catch (JSONException ignored) { }
        Set<String> grants = new HashSet<>();
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions())
            if (permission.isReadPermission()) grants.add(permission.getUri().toString());
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            List<String> missing = new ArrayList<>();
            List<String> missingUris = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            List<String> noSavedGrant = new ArrayList<>();
            int accessible = 0;
            int savedGrants = 0;
            for (JSONObject item : snapshot) {
                String uri = item.optString("uri");
                String tree = item.optString("treeUri");
                if (grants.contains(uri) || (!tree.isEmpty() && grants.contains(tree))) savedGrants++;
                else noSavedGrant.add(bookTitle(item));
                try (ParcelFileDescriptor descriptor = appContext.getContentResolver()
                        .openFileDescriptor(Uri.parse(uri), "r")) {
                    if (descriptor == null) throw new IOException("No file descriptor");
                    accessible++;
                    long size = descriptor.getStatSize();
                    long previousSize = item.optLong("sourceSize", -1);
                    if (size >= 0 && previousSize >= 0 && size != previousSize)
                        changed.add(bookTitle(item));
                    if (size >= 0 && previousSize < 0) item.put("sourceSize", size);
                    try (Cursor details = appContext.getContentResolver().query(Uri.parse(uri),
                            new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                            null, null, null)) {
                        if (details != null && details.moveToFirst() && details.getColumnCount() > 0
                                && !details.isNull(0)) {
                            long modified = details.getLong(0);
                            long previous = item.optLong("sourceModified", -1);
                            if (previous >= 0 && modified > 0 && modified != previous
                                    && !changed.contains(bookTitle(item))) changed.add(bookTitle(item));
                            if (previous < 0 && modified > 0) item.put("sourceModified", modified);
                        }
                    } catch (RuntimeException ignored) { }
                } catch (IOException | RuntimeException | JSONException e) {
                    missing.add(bookTitle(item));
                    missingUris.add(uri);
                }
            }
            int accessibleCount = accessible;
            int savedGrantCount = savedGrants;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity == null || activity.destroyed) return;
                // Baselines are derived metadata and never change progress or file contents.
                for (JSONObject inspected : snapshot) {
                    JSONObject live = activity.find(inspected.optString("uri"));
                    if (live != null && !live.has("sourceSize") && inspected.has("sourceSize"))
                        try { live.put("sourceSize", inspected.optLong("sourceSize")); }
                        catch (JSONException ignored) { }
                    if (live != null && !live.has("sourceModified") && inspected.has("sourceModified"))
                        try { live.put("sourceModified", inspected.optLong("sourceModified")); }
                        catch (JSONException ignored) { }
                }
                activity.saveLibraryNow(false);
                StringBuilder report = new StringBuilder();
                report.append("Accessible files: ").append(accessibleCount).append(" / ")
                        .append(snapshot.size()).append("\nSaved read grants: ")
                        .append(savedGrantCount).append(" / ").append(snapshot.size());
                for (int i = 0; i < Math.min(20, noSavedGrant.size()); i++)
                    report.append("\n• No saved grant: ").append(noSavedGrant.get(i));
                if (noSavedGrant.size() > 20)
                    report.append("\n…and ").append(noSavedGrant.size() - 20).append(" more without grants");
                report.append("\n\nMissing or inaccessible: ").append(missing.size());
                for (int i = 0; i < Math.min(20, missing.size()); i++)
                    report.append("\n• ").append(missing.get(i));
                if (missing.size() > 20) report.append("\n…and ").append(missing.size() - 20).append(" more");
                report.append("\n\nChanged size or date since import/check: ").append(changed.size());
                for (int i = 0; i < Math.min(20, changed.size()); i++)
                    report.append("\n• ").append(changed.get(i));
                if (changed.size() > 20) report.append("\n…and ").append(changed.size() - 20).append(" more");
                new AlertDialog.Builder(activity).setTitle("File access and changes")
                        .setMessage(report.toString())
                        .setNeutralButton(missingUris.isEmpty() ? null : "Relink missing…",
                                missingUris.isEmpty() ? null : (dialog, which) ->
                                        activity.chooseMissingRelink(missingUris))
                        .setPositiveButton("Close", null).show();
            });
        });
    }

    private void chooseMissingRelink(List<String> missingUris) {
        List<JSONObject> available = new ArrayList<>();
        for (String uri : missingUris) {
            JSONObject item = find(uri);
            if (item != null) available.add(item);
        }
        if (available.isEmpty()) { message("No missing listing remains to relink"); return; }
        String[] labels = new String[available.size()];
        for (int i = 0; i < available.size(); i++) labels[i] = bookTitle(available.get(i));
        new AlertDialog.Builder(this).setTitle("Relink a missing book")
                .setItems(labels, (dialog, which) -> pickReplacement(available.get(which)))
                .setPositiveButton("Relink all…", (dialog, which) -> startRelinkBatch(missingUris))
                .setNegativeButton("Cancel", null).show();
    }

    private void startRelinkBatch(List<String> missingUris) {
        relinkBatchQueue.clear();
        Set<String> seen = new HashSet<>();
        for (String uri : missingUris) if (find(uri) != null && seen.add(uri))
            relinkBatchQueue.add(uri);
        showNextRelink();
    }

    private void showNextRelink() {
        while (!relinkBatchQueue.isEmpty() && find(relinkBatchQueue.peek()) == null)
            relinkBatchQueue.remove();
        if (relinkBatchQueue.isEmpty()) { message("Relink review finished"); return; }
        JSONObject item = find(relinkBatchQueue.peek());
        new AlertDialog.Builder(this).setTitle("Relink " + bookTitle(item))
                .setMessage(relinkBatchQueue.size() + " listing(s) left. Choose the matching local file."
                        + " Skipping keeps this listing for later review.")
                .setPositiveButton("Choose file", (dialog, which) -> pickReplacement(item))
                .setNeutralButton("Skip", (dialog, which) -> {
                    relinkBatchQueue.remove(); showNextRelink();
                }).setNegativeButton("Stop", (dialog, which) -> relinkBatchQueue.clear())
                .setOnCancelListener(dialog -> relinkBatchQueue.clear()).show();
    }

    private void chooseBackupAction() {
        new AlertDialog.Builder(this).setTitle("Local library backup · no audio")
                .setItems(new String[]{"Export metadata", "Import metadata",
                        "Export password-encrypted metadata", "Import encrypted metadata",
                        "Export catalog only (no listening history or notes)"}, (dialog, which) -> {
                    if (which == 2) { requestBackupPassword(null, true); return; }
                    launchBackupPicker(which);
                }).setNegativeButton("Cancel", null).show();
    }

    private void launchBackupPicker(int which) {
                    boolean exporting = which == 0 || which == 2 || which == 4;
                    Intent intent = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT
                            : Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType(which == 2 || which == 3
                            ? "application/octet-stream" : "application/json");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                    if (exporting) intent.putExtra(Intent.EXTRA_TITLE,
                            which == 2 ? "arioso-library.ariosobackup"
                                    : which == 4 ? "arioso-catalog.json" : "arioso-library.json");
                    intent.addFlags(exporting ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION
                            : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    int[] requests = {PICK_EXPORT, PICK_IMPORT, PICK_EXPORT_ENCRYPTED,
                            PICK_IMPORT_ENCRYPTED, PICK_EXPORT_CATALOG};
                    startActivityForResult(intent, requests[which]);
    }

    private void requestBackupPassword(Uri destination, boolean exporting) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(exporting ? "At least 8 characters" : "Backup password");
        AlertDialog prompt = new AlertDialog.Builder(this)
                .setTitle(exporting ? "Encrypt local backup" : "Unlock local backup")
                .setMessage("Arioso does not save this password. A lost password cannot be recovered.")
                .setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton(exporting ? "Export" : "Import", (dialog, which) -> {
                    int count = input.length();
                    char[] password = new char[count];
                    for (int i = 0; i < count; i++) password[i] = input.getText().charAt(i);
                    input.setText("");
                    if (exporting && count < 8) {
                        Arrays.fill(password, '\0');
                        message("Use at least 8 password characters"); return;
                    }
                    if (count == 0) { message("Enter a password"); return; }
                    if (exporting) {
                        if (pendingExportPassword != null) Arrays.fill(pendingExportPassword, '\0');
                        pendingExportPassword = password;
                        launchBackupPicker(2);
                    }
                    else importMetadata(destination, password);
                }).create();
        prompt.show();
        prompt.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void pickCover(JSONObject item) {
        coverBookUri = item.optString("uri");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_COVER);
    }

    private void finishCover(String bookUri, Uri chosen) {
        JSONObject item = find(bookUri);
        if (item == null) { message("That book is no longer listed"); return; }
        if (!LocalDocumentPolicy.accepts(chosen.getScheme(), chosen.getAuthority())
                || chosen.toString().length() > 4096) {
            message("Choose an image stored on this device"); return;
        }
        try (Cursor cursor = getContentResolver().query(chosen,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)
                    && cursor.getLong(0) > 2 * 1024 * 1024) {
                message("Choose an image under 2 MB"); return;
            }
        } catch (RuntimeException ignored) { /* The bounded decoder checks actual bytes. */ }
        try { getContentResolver().takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException e) {
            message("Could not keep access to this image"); return;
        }
        String previous = item.optString("coverUri", "");
        try { item.put("coverUri", chosen.toString()); }
        catch (JSONException e) { releaseReadGrant(chosen); message("Could not use this image"); return; }
        if (!saveLibraryNow(false)) {
            try { item.put("coverUri", previous); } catch (JSONException ignored) { }
            releaseReadGrant(chosen);
            message("Could not save the cover choice");
            return;
        }
        if (!previous.isEmpty() && !previous.equals(chosen.toString())) releaseCoverIfUnused(previous);
        absentArtwork.remove(chosen.toString());
        renderLibrary();
        message("Local cover selected");
    }

    private void useEmbeddedCover(JSONObject item) {
        String old = item.optString("coverUri", "");
        if (old.isEmpty()) return;
        try { item.put("coverUri", ""); }
        catch (JSONException ignored) { return; }
        if (!saveLibraryNow(false)) {
            try { item.put("coverUri", old); } catch (JSONException ignored) { }
            message("Could not change the cover"); return;
        }
        releaseCoverIfUnused(old);
        renderLibrary();
    }

    private void releaseCoverIfUnused(String oldUri) {
        if (oldUri == null || oldUri.isEmpty()) return;
        for (JSONObject book : books)
            if (oldUri.equals(book.optString("coverUri"))) return;
        releaseReadGrant(Uri.parse(oldUri));
        artwork.remove(oldUri);
        absentArtwork.remove(oldUri);
    }

    private boolean finishRelink(String oldUri, Uri replacement) {
        if (!libraryReadyForWrite()) return false;
        JSONObject item = find(oldUri);
        if (item == null) { message("The original listing is no longer available"); return false; }
        if (!LocalDocumentPolicy.accepts(replacement.getScheme(), replacement.getAuthority())) {
            message("Choose a file stored on this device"); return false;
        }
        String newUri = replacement.toString();
        if (newUri.length() > 4096 || (find(newUri) != null && !newUri.equals(oldUri))) {
            message("That file is already listed or its address is too long"); return false;
        }
        if (newUri.equals(oldUri)) { message("This listing already uses that file"); return false; }
        try {
            getContentResolver().takePersistableUriPermission(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException e) {
            message("Could not keep access to the replacement file"); return false;
        }
        boolean selected = oldUri.equals(currentUri);
        if (selected && playback != null) { playback.pause(); playback.saveNow(); playback.clear(); }
        if (!libraryReadyForWrite()) { releaseReadGrant(replacement); return false; }
        String oldName = item.optString("name", "");
        String oldTree = item.optString("treeUri", "");
        try {
            item.put("uri", newUri);
            item.put("name", displayName(replacement));
            item.remove("treeUri");
        } catch (JSONException e) {
            releaseReadGrant(replacement);
            message("Could not update the listing"); return false;
        }
        boolean saved = LibraryStore.get(this).replaceAll(books);
        if (saved && selected) saved = prefs.edit().putString("selected_uri", newUri).commit();
        if (!saved) {
            try {
                item.put("uri", oldUri);
                item.put("name", oldName);
                if (!oldTree.isEmpty()) item.put("treeUri", oldTree);
            }
            catch (JSONException ignored) { }
            LibraryStore.get(this).replaceAll(books);
            releaseReadGrant(replacement);
            if (selected && playback != null) loadSelected(item);
            message("Could not save the replacement. Original listing kept.");
            return false;
        }
        releaseReadGrant(Uri.parse(oldUri));
        if (!oldTree.isEmpty()) releaseTreeIfUnused(oldTree);
        chapterCache.remove(oldUri);
        combinedChapterCache.remove(oldUri);
        artwork.remove(oldUri);
        absentArtwork.remove(oldUri);
        if (selected) {
            currentUri = newUri;
            if (playback != null) loadSelected(item);
            refreshPlayerUi();
        }
        renderLibrary();
        message("File relinked; title and progress kept");
        return true;
    }

    private void releaseReadGrant(Uri uri) {
        try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException ignored) { }
    }

    private void exportMetadata(Uri destination) { exportMetadata(destination, null, false); }

    private void exportMetadata(Uri destination, char[] password) {
        exportMetadata(destination, password, false);
    }

    private void exportMetadata(Uri destination, char[] password, boolean catalogOnly) {
        savePosition();
        if (!libraryReadyForWrite()) {
            if (password != null) Arrays.fill(password, '\0');
            return;
        }
        final byte[] payload;
        try {
            JSONObject backup = new JSONObject();
            JSONArray exportedBooks = new JSONArray(libraryJson());
            if (catalogOnly) for (int i = 0; i < exportedBooks.length(); i++) {
                JSONObject book = exportedBooks.getJSONObject(i);
                for (String field : new String[]{"position", "duration", "finished", "lastPlayedAt",
                        "bookmarks", "note", "characters", "playbackSpeed", "playbackPitch", "sourceSha256"})
                    book.remove(field);
            }
            backup.put("format", BACKUP_FORMAT);
            backup.put("scope", catalogOnly ? "catalog-only" : "full");
            backup.put("exportedAt", System.currentTimeMillis());
            backup.put("bookCount", exportedBooks.length());
            backup.put("catalogSha256", BackupIntegrity.catalogSha256(exportedBooks.toString()));
            backup.put("books", exportedBooks);
            payload = backup.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > 4 * 1024 * 1024) {
                if (password != null) Arrays.fill(password, '\0');
                Arrays.fill(payload, (byte) 0);
                message("Library metadata is too large to export"); return;
            }
        } catch (JSONException e) {
            if (password != null) Arrays.fill(password, '\0');
            message("Could not prepare the backup"); return;
        }
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            int result = 0;
            byte[] output = payload;
            try {
                if (password != null) output = EncryptedBackup.encrypt(payload, password);
                try (OutputStream stream = appContext.getContentResolver().openOutputStream(destination, "wt")) {
                    if (stream == null) throw new IOException("No output stream");
                    stream.write(output);
                    stream.flush();
                }
                result = verifyWrittenBackup(appContext, destination, output);
            } catch (IOException | RuntimeException | GeneralSecurityException ignored) { }
            finally {
                if (password != null) Arrays.fill(password, '\0');
                Arrays.fill(payload, (byte) 0);
                if (output != payload) Arrays.fill(output, (byte) 0);
            }
            final int completed = result;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity == null || activity.destroyed) return;
                if (completed == 2) activity.message("Metadata backup saved and verified on this device");
                else if (completed == 1) activity.message(
                        "Backup saved, but this location did not allow verification");
                else activity.showIncompleteBackup(destination, completed == 3);
            });
        });
    }

    private void showIncompleteBackup(Uri destination, boolean mismatched) {
        new AlertDialog.Builder(this).setTitle("Export was not verified")
                .setMessage(mismatched
                        ? "The saved file differed when read back. Review or delete it in Files."
                        : "Arioso could not finish writing this file. The new file may be incomplete.")
                .setNegativeButton("Keep file", null)
                .setPositiveButton("Delete incomplete file", (dialog, which) -> {
                    try {
                        boolean removed = DocumentsContract.deleteDocument(getContentResolver(), destination);
                        message(removed ? "Incomplete export file removed"
                                : "Could not remove it; check Files before restoring");
                    } catch (RuntimeException | java.io.FileNotFoundException e) {
                        message("Could not remove it; check Files before restoring");
                    }
                }).show();
    }

    private static int verifyWrittenBackup(Context context, Uri destination, byte[] expected) {
        try (InputStream stream = context.getContentResolver().openInputStream(destination)) {
            if (stream == null) return 1;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[8192];
            int count;
            long length = 0;
            while ((count = stream.read(chunk)) != -1) {
                length += count;
                if (length > expected.length) return 3;
                digest.update(chunk, 0, count);
            }
            byte[] actualHash = digest.digest();
            byte[] expectedHash = digest.digest(expected);
            return length == expected.length
                    && MessageDigest.isEqual(expectedHash, actualHash) ? 2 : 3;
        } catch (IOException | RuntimeException | GeneralSecurityException e) { return 1; }
    }

    private void importMetadata(Uri source) { importMetadata(source, null); }

    private void importMetadata(Uri source, char[] password) {
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            List<JSONObject> candidates = new ArrayList<>();
            boolean valid = false;
            String scope = "full";
            int skippedNonLocal = 0;
            boolean checksumPresent = false;
            boolean previousIdentity = false;
            try (InputStream stream = appContext.getContentResolver().openInputStream(source)) {
                if (stream == null) throw new IOException("No input stream");
                WipeableBytes bytes = new WipeableBytes();
                byte[] raw = null;
                byte[] decoded = null;
                try {
                    byte[] chunk = new byte[8192];
                    int count;
                    try {
                        while ((count = stream.read(chunk)) != -1) {
                            if (bytes.size() + count > 4 * 1024 * 1024 + 64)
                                throw new IOException("Backup exceeds limit");
                            bytes.write(chunk, 0, count);
                        }
                    } finally { Arrays.fill(chunk, (byte) 0); }
                    raw = bytes.toByteArray();
                    decoded = password == null ? raw : EncryptedBackup.decrypt(raw, password);
                    JSONObject backup = new JSONObject(BackupIntegrity.decodeUtf8Strict(decoded));
                    JSONArray sourceBooks = backup.optJSONArray("books");
                    boolean legacyScope = !backup.has("scope");
                    scope = legacyScope ? "legacy full" : backup.optString("scope", "");
                    checksumPresent = backup.has("catalogSha256");
                    previousIdentity = BackupFormat.isPrevious(backup.optString("format"));
                    if (!legacyScope && !"full".equals(scope) && !"catalog-only".equals(scope))
                        throw new JSONException("Unsupported backup scope");
                    if (!BackupFormat.supports(backup.optString("format"))
                            || sourceBooks == null || sourceBooks.length() > 500
                            || (backup.has("bookCount")
                            && backup.optInt("bookCount", -1) != sourceBooks.length())
                            || (checksumPresent && !BackupIntegrity.matches(
                            backup.optString("catalogSha256"), sourceBooks.toString())))
                        throw new JSONException("Unsupported backup format");
                    for (int i = 0; i < sourceBooks.length(); i++) {
                        JSONObject item = sourceBooks.optJSONObject(i);
                        if (item == null || item.toString().length() > 20000
                                || item.optString("uri").isEmpty())
                            throw new JSONException("Malformed book listing");
                        String uri = item.optString("uri");
                        if (!isOnDeviceUri(uri)) { skippedNonLocal++; continue; }
                        JSONObject copy = new JSONObject(item.toString());
                        copy.remove("chapters");
                        candidates.add(copy);
                    }
                    valid = true;
                } finally {
                    if (decoded != null && decoded != raw) Arrays.fill(decoded, (byte) 0);
                    if (raw != null) Arrays.fill(raw, (byte) 0);
                    bytes.wipe();
                }
            } catch (IOException | RuntimeException | JSONException | GeneralSecurityException ignored) { }
            finally { if (password != null) Arrays.fill(password, '\0'); }
            final boolean accepted = valid;
            final String backupScope = scope;
            final int skipped = skippedNonLocal;
            final boolean checked = checksumPresent;
            final boolean fromPreviousIdentity = previousIdentity;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity != null && !activity.destroyed) {
                    if (accepted) activity.confirmMetadataImport(candidates, backupScope, skipped,
                            checked, fromPreviousIdentity);
                    else activity.message("This local metadata backup is invalid or too large");
                }
            });
        });
    }

    private void confirmMetadataImport(List<JSONObject> candidates, String scope,
                                       int skippedNonLocal, boolean checksumPresent,
                                       boolean previousIdentity) {
        int newCount = 0;
        int changedCount = 0;
        Set<String> seen = new HashSet<>();
        for (JSONObject item : candidates) {
            String uri = item.optString("uri");
            if (!seen.add(uri)) continue;
            JSONObject current = find(uri);
            if (current == null) newCount++;
            else if (backupMetadataDiffers(current, item)) changedCount++;
        }
        final int additions = newCount;
        final int differences = changedCount;
        String contents = "catalog-only".equals(scope) ? "No saved listening data or notes."
                : "New books may include saved progress, bookmarks, and notes.";
        new AlertDialog.Builder(this).setTitle("Backup differences")
                .setMessage("Backup: " + scope + ". " + contents + "\n"
                        + (checksumPresent ? "Checksum matches; source not authenticated. "
                        : "No checksum; contents not verified. ")
                        + candidates.size() + " local, " + skippedNonLocal + " unsupported skipped.\n"
                        + additions + " new, " + differences + " existing metadata changes."
                        + " No audio included. Existing progress and file links stay."
                        + (previousIdentity ? "\n\nThis backup is from the earlier app identity."
                        + " After restoring, choose More > Organize library > Missing files and saved access"
                        + " > Relink missing to grant Arioso access to the files."
                        + " The earlier app remains untouched." : ""))
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Add new", (dialog, which) ->
                        previewRestore(candidates, false, null, null))
                .setPositiveButton("Restore…", (dialog, which) -> chooseRestoreOption(candidates))
                .show();
    }

    private void chooseRestoreOption(List<JSONObject> candidates) {
        new AlertDialog.Builder(this).setTitle("Restore metadata")
                .setItems(new String[]{"Individual books", "One author", "One series", "All books"},
                        (dialog, which) -> {
                            if (which == 0) chooseRestoreBooks(candidates);
                            else if (which == 3) previewRestore(candidates, true, null, null);
                            else chooseRestoreGroup(candidates, which == 1);
                        }).setNegativeButton("Cancel", null).show();
    }

    private void chooseRestoreBooks(List<JSONObject> candidates) {
        if (candidates.isEmpty()) { message("This backup has no local book listings"); return; }
        String[] labels = new String[candidates.size()];
        boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++)
            labels[i] = metadata(candidates.get(i), "author", UNKNOWN_AUTHOR)
                    + " · " + bookTitle(candidates.get(i));
        new AlertDialog.Builder(this).setTitle("Select books to restore")
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> checked[which] = selected)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Preview", (dialog, which) -> {
                    List<JSONObject> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(candidates.get(i));
                    if (chosen.isEmpty()) { message("Select at least one book"); return; }
                    previewRestoreSelection(chosen, true);
                }).show();
    }

    private void chooseRestoreGroup(List<JSONObject> candidates, boolean byAuthor) {
        Set<String> groups = new HashSet<>();
        for (JSONObject item : candidates) {
            String value = metadata(item, byAuthor ? "author" : "series", "");
            if (!value.isEmpty()) groups.add(value);
        }
        if (groups.isEmpty()) { message("No matching groups in the backup"); return; }
        List<String> names = new ArrayList<>(groups);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        new AlertDialog.Builder(this).setTitle(byAuthor ? "Restore author" : "Restore series")
                .setItems(names.toArray(new String[0]), (dialog, which) ->
                        previewRestore(candidates, true, byAuthor ? names.get(which) : null,
                                byAuthor ? null : names.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void previewRestore(List<JSONObject> candidates, boolean updateExisting,
                                String authorFilter, String seriesFilter) {
        List<JSONObject> chosen = new ArrayList<>();
        for (JSONObject item : candidates) {
            if (authorFilter != null && !authorFilter.equalsIgnoreCase(metadata(item, "author", ""))) continue;
            if (seriesFilter != null && !seriesFilter.equalsIgnoreCase(metadata(item, "series", ""))) continue;
            chosen.add(item);
        }
        previewRestoreSelection(chosen, updateExisting);
    }

    private void previewRestoreSelection(List<JSONObject> chosen, boolean updateExisting) {
        int newCount = 0;
        int changed = 0;
        StringBuilder preview = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (JSONObject item : chosen) {
            if (!seen.add(item.optString("uri"))) continue;
            JSONObject current = find(item.optString("uri"));
            if (current == null) {
                if (newCount < 10) preview.append("New: ").append(bookTitle(item)).append("\n");
                newCount++;
            }
            else if (updateExisting && backupMetadataDiffers(current, item)) {
                if (changed < 8) {
                    preview.append(bookTitle(current)).append(":\n");
                    for (String field : BACKUP_FIELDS) if (item.has(field)
                            && !String.valueOf(current.opt(field)).equals(String.valueOf(item.opt(field))))
                        preview.append("  ").append(field).append(": ")
                                .append(shortPreview(String.valueOf(current.opt(field))))
                                .append(" → ").append(shortPreview(String.valueOf(item.opt(field))))
                                .append("\n");
                }
                changed++;
            }
        }
        if (newCount == 0 && changed == 0) { message("No applicable backup differences"); return; }
        String detail = newCount + " new, " + changed + " existing metadata update(s).\n\n"
                + preview + (changed > 8 ? "…and " + (changed - 8) + " more changed books\n" : "")
                + "\nExisting listings keep current progress, bookmarks, file references, and covers."
                + " Newly added listings use metadata saved in this backup.";
        new AlertDialog.Builder(this).setTitle("Apply backup metadata?")
                .setMessage(detail).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> applyRestore(chosen, updateExisting)).show();
    }

    private static String shortPreview(String raw) {
        if (raw == null || "null".equals(raw)) return "blank";
        String compact = raw.replace('\n', ' ').replace('\r', ' ');
        return compact.length() > 64 ? compact.substring(0, 64) + "…" : compact;
    }

    private boolean backupMetadataDiffers(JSONObject current, JSONObject saved) {
        for (String field : BACKUP_FIELDS) if (saved.has(field)
                && !String.valueOf(current.opt(field)).equals(String.valueOf(saved.opt(field))))
            return true;
        return false;
    }

    private void applyRestore(List<JSONObject> candidates, boolean updateExisting) {
        savePosition();
        if (!libraryReadyForWrite()) return;
        List<JSONObject> previous = new ArrayList<>(books);
        List<JSONObject> updated = new ArrayList<>(books);
        Set<String> seen = new HashSet<>();
        try {
            for (JSONObject candidate : candidates) {
                String uri = candidate.optString("uri");
                if (!seen.add(uri)) continue;
                JSONObject current = find(uri);
                if (current == null) {
                    if (updated.size() < 500) updated.add(new JSONObject(candidate.toString()));
                } else if (updateExisting) {
                    JSONObject copy = new JSONObject(current.toString());
                    for (String field : BACKUP_FIELDS) if (candidate.has(field))
                        copy.put(field, candidate.opt(field));
                    int index = updated.indexOf(current);
                    if (index >= 0) updated.set(index, copy);
                }
            }
        } catch (JSONException e) { message("Could not prepare backup restore"); return; }
        String checkpoint = libraryJson();
        if (checkpoint.length() > 4 * 1024 * 1024) {
            message("The library is too large for a safe restore checkpoint"); return;
        }
        String priorCheckpoint = prefs.getString("last_backup_restore", null);
        if (!prefs.edit().putString("last_backup_restore", checkpoint).commit()) {
            message("Could not save a restore checkpoint; library kept"); return;
        }
        books.clear(); books.addAll(updated);
        if (!saveLibraryNow(false)) {
            books.clear(); books.addAll(previous);
            SharedPreferences.Editor rollback = prefs.edit();
            if (priorCheckpoint == null) rollback.remove("last_backup_restore");
            else rollback.putString("last_backup_restore", priorCheckpoint);
            boolean markerRestored = rollback.commit();
            message(markerRestored ? "Could not restore backup metadata; library kept"
                    : "Library kept, but the prior undo checkpoint could not be restored");
            return;
        }
        renderLibrary();
        message("Backup metadata applied. Undo is in Organize library.");
    }

    private void undoLastBackupRestore() {
        String checkpoint = prefs.getString("last_backup_restore", null);
        if (checkpoint == null) { message("No backup restore to undo"); return; }
        new AlertDialog.Builder(this).setTitle("Undo last backup restore?")
                .setMessage("Return library listings and book details to before the restore. "
                        + "Listening progress, bookmarks, file links, and covers stay current for books "
                        + "that still have the same file link. "
                        + "Books added by that restore will be removed from Arioso, not from device storage.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo", (dialog, which) -> {
                    savePosition();
                    if (!libraryReadyForWrite()) return;
                    List<JSONObject> restored = new ArrayList<>();
                    try {
                        JSONArray entries = new JSONArray(checkpoint);
                        if (entries.length() > 500) throw new JSONException("Too many books");
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject prior = new JSONObject(entries.getJSONObject(i).toString());
                            JSONObject live = find(prior.optString("uri"));
                            if (live != null) for (String field : new String[]{"position", "duration",
                                    "finished", "lastPlayedAt", "bookmarks", "coverUri", "name",
                                    "treeUri", "sourceSize", "sourceModified", "sourceSha256"}) {
                                if (live.has(field)) prior.put(field, live.opt(field));
                                else prior.remove(field);
                            }
                            restored.add(prior);
                        }
                    } catch (JSONException e) { message("Restore checkpoint is invalid; library kept"); return; }
                    List<JSONObject> old = new ArrayList<>(books);
                    books.clear(); books.addAll(restored);
                    if (!saveLibraryNow(false)) {
                        books.clear(); books.addAll(old);
                        message("Could not undo restore; library kept"); return;
                    }
                    boolean cleared = prefs.edit().remove("last_backup_restore").commit();
                    renderLibrary();
                    message(cleared ? "Backup restore undone; listening progress kept"
                            : "Restore undone, but its undo marker could not be cleared");
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                scanFolder(data.getData());
            return;
        }
        if (requestCode == PICK_COVER) {
            String bookUri = coverBookUri;
            coverBookUri = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                finishCover(bookUri, data.getData());
            return;
        }
        if (requestCode == PICK_COMPANION_PDF) {
            String bookUri = companionBookUri;
            companionBookUri = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                finishCompanionPdf(bookUri, data.getData());
            return;
        }
        if (requestCode == PICK_BOOKMARK_EXPORT) {
            String bookUri = bookmarkExportBookUri;
            bookmarkExportBookUri = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri destination = data.getData();
                if (!LocalDocumentPolicy.accepts(destination.getScheme(), destination.getAuthority()))
                    message("Choose an on-device location in Files or Downloads");
                else exportBookmarks(bookUri, destination);
            }
            return;
        }
        if (requestCode == PICK_EXPORT || requestCode == PICK_IMPORT
                || requestCode == PICK_EXPORT_CATALOG
                || requestCode == PICK_EXPORT_ENCRYPTED || requestCode == PICK_IMPORT_ENCRYPTED) {
            char[] password = null;
            if (requestCode == PICK_EXPORT_ENCRYPTED) {
                password = pendingExportPassword;
                pendingExportPassword = null;
            }
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri chosen = data.getData();
                if (!LocalDocumentPolicy.accepts(chosen.getScheme(), chosen.getAuthority())) {
                    if (password != null) Arrays.fill(password, '\0');
                    message("Choose an on-device location in Files or Downloads");
                } else if (requestCode == PICK_EXPORT) exportMetadata(chosen);
                else if (requestCode == PICK_EXPORT_CATALOG) exportMetadata(chosen, null, true);
                else if (requestCode == PICK_IMPORT) importMetadata(chosen);
                else if (requestCode == PICK_EXPORT_ENCRYPTED) {
                    if (password != null) exportMetadata(chosen, password);
                    else message("Export canceled after the app was restarted; choose a new backup destination");
                }
                else requestBackupPassword(chosen, false);
            }
            else if (password != null) Arrays.fill(password, '\0');
            return;
        }
        if (requestCode == PICK_RELINK) {
            String oldUri = relinkOriginalUri;
            relinkOriginalUri = null;
            boolean linked = resultCode == RESULT_OK && data != null && data.getData() != null
                    && finishRelink(oldUri, data.getData());
            if (!relinkBatchQueue.isEmpty() && oldUri != null
                    && oldUri.equals(relinkBatchQueue.peek())) {
                if (linked) relinkBatchQueue.remove();
                showNextRelink();
            }
            return;
        }
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null) return;
        books.clear();
        readLibrary();
        if (!libraryLoaded) return;
        List<Uri> selected = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < Math.min(clip.getItemCount(), 500); i++) selected.add(clip.getItemAt(i).getUri());
            if (clip.getItemCount() > 500) message("Only the first 500 selected files can be added");
        }
        else if (data.getData() != null) selected.add(data.getData());
        int possibleDuplicates = 0;
        StringBuilder duplicateDetails = new StringBuilder();
        for (Uri uri : selected) {
            if (uri == null || !LocalDocumentPolicy.accepts(uri.getScheme(), uri.getAuthority())
                    || find(uri.toString()) != null) continue;
            String candidate = titleFromName(displayName(uri));
            for (JSONObject book : books) {
                if (candidate.equalsIgnoreCase(bookTitle(book))) {
                    possibleDuplicates++;
                    if (possibleDuplicates <= 12) duplicateDetails.append("\n• ")
                            .append(displayName(uri)).append(" matches ")
                            .append(bookTitle(book)).append(" (existing file ")
                            .append(book.optLong("sourceSize", -1) >= 0
                                    ? book.optLong("sourceSize") + " bytes" : "size unknown")
                            .append(")");
                    break;
                }
            }
        }
        if (possibleDuplicates > 0) {
            new AlertDialog.Builder(this).setTitle("Possible duplicate titles")
                    .setMessage(possibleDuplicates + " selected file(s) have a title already in Arioso. "
                            + "Different editions can share a title. Check the files before adding:"
                            + duplicateDetails
                            + (possibleDuplicates > 12 ? "\n…and " + (possibleDuplicates - 12) + " more" : ""))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add files", (dialog, which) -> addSelectedFiles(selected))
                    .show();
        } else addSelectedFiles(selected);
    }

    private void addSelectedFiles(List<Uri> selected) {
        int added = 0;
        int accessKept = 0;
        int skippedNonlocal = 0;
        int previousSize = books.size();
        for (Uri uri : selected) {
            if (uri == null || !LocalDocumentPolicy.accepts(uri.getScheme(), uri.getAuthority())) {
                skippedNonlocal++;
                continue;
            }
            if (uri.toString().length() > 4096) continue;
            if (find(uri.toString()) != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    accessKept++;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    message("Could not keep access to a selected file");
                }
                continue;
            }
            if (books.size() >= 500) { message("Library limit is 500 files"); break; }
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                JSONObject item = new JSONObject();
                item.put("uri", uri.toString());
                String name = displayName(uri);
                SeriesNumberSuggestion.Suggestion suggestion = SeriesNumberSuggestion.fromFilename(name);
                item.put("name", name);
                item.put("title", suggestion == null ? titleFromName(name) : suggestion.title);
                item.put("author", "");
                item.put("series", suggestion == null ? "" : suggestion.series);
                item.put("seriesNumber", suggestion == null ? "" : suggestion.number);
                item.put("seriesSuggested", suggestion != null);
                item.put("position", 0);
                item.put("duration", 0);
                item.put("finished", false);
                item.put("importedAt", System.currentTimeMillis());
                try (Cursor details = getContentResolver().query(uri,
                        new String[]{OpenableColumns.SIZE,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    if (details != null && details.moveToFirst()) {
                        if (details.getColumnCount() > 0 && !details.isNull(0))
                            item.put("sourceSize", details.getLong(0));
                        if (details.getColumnCount() > 1 && !details.isNull(1))
                            item.put("sourceModified", details.getLong(1));
                    }
                } catch (RuntimeException ignored) { }
                books.add(item);
                added++;
            } catch (SecurityException | IllegalArgumentException | JSONException e) {
                message("Could not keep access to a selected file");
            }
        }
        if (added > 0) {
            if (saveLibraryNow(false)) {
                renderLibrary();
                rememberLastImport(new ArrayList<>(books.subList(previousSize, books.size())));
                message("Added " + added + " on-device file(s)"
                        + (skippedNonlocal > 0 ? "; skipped " + skippedNonlocal + " nonlocal file(s)" : ""));
                for (int i = previousSize; i < books.size(); i++) readImportedMetadata(books.get(i));
            } else {
                while (books.size() > previousSize) {
                    JSONObject item = books.remove(books.size() - 1);
                    try { getContentResolver().releasePersistableUriPermission(
                            Uri.parse(item.optString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
                message("Could not save the library. No files were added.");
            }
        } else if (skippedNonlocal > 0) {
            message("Choose audio stored on this device using Files or Downloads");
        } else if (accessKept > 0) {
            renderLibrary();
            message("Access kept for " + accessKept + " existing file(s)");
        }
    }

    private void readImportedMetadata(JSONObject item) {
        String uri = item.optString("uri");
        String originalTitle = item.optString("title");
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            String embeddedTitle = null;
            String embeddedAuthor = null;
            String embeddedSeries = null;
            String embeddedNumber = null;
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(appContext, Uri.parse(uri));
                embeddedTitle = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                embeddedAuthor = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR));
                if (embeddedAuthor == null)
                    embeddedAuthor = cleanTag(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            } catch (RuntimeException | OutOfMemoryError ignored) { /* Filename and manual details remain available. */ }
            finally { if (retriever != null) try { retriever.release(); } catch (Exception ignored) { } }
            String lowerName = item.optString("name").toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".m4a") || lowerName.endsWith(".m4b")
                    || lowerName.endsWith(".mp4")) {
                try (ParcelFileDescriptor descriptor = appContext.getContentResolver()
                        .openFileDescriptor(Uri.parse(uri), "r")) {
                    if (descriptor != null) try (FileInputStream stream = new FileInputStream(
                            descriptor.getFileDescriptor())) {
                        Mp4SeriesMetadata.Details details = Mp4SeriesMetadata.read(stream.getChannel());
                        embeddedSeries = details.series;
                        embeddedNumber = details.number;
                    }
                } catch (IOException | RuntimeException ignored) { /* Manual details stay available. */ }
            }
            final String foundTitle = embeddedTitle;
            final String foundAuthor = embeddedAuthor;
            final String foundSeries = embeddedSeries;
            final String foundNumber = embeddedNumber;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity != null) activity.applyImportedMetadata(item, uri, originalTitle,
                        foundTitle, foundAuthor, foundSeries, foundNumber);
            });
        });
    }

    private void applyImportedMetadata(JSONObject item, String uri, String originalTitle,
                                       String foundTitle, String foundAuthor,
                                       String foundSeries, String foundNumber) {
        if (destroyed) return;
        item = find(uri);
        if (item == null) return;
        if (item.optBoolean("manualEdited")) return;
        String beforeTitle = item.optString("title");
        String beforeAuthor = item.optString("author");
        String beforeSeries = item.optString("series");
        String beforeNumber = item.optString("seriesNumber");
        boolean titleChange = foundTitle != null && originalTitle.equals(beforeTitle);
        boolean authorChange = foundAuthor != null && beforeAuthor.isEmpty();
        boolean suggested = item.optBoolean("seriesSuggested");
        boolean seriesChange = foundSeries != null && (beforeSeries.isEmpty() || suggested);
        boolean numberChange = foundNumber != null && (beforeNumber.isEmpty() || suggested)
                && (seriesChange || !beforeSeries.isEmpty());
        if (!titleChange && !authorChange && !seriesChange && !numberChange) return;
        try {
            if (titleChange) item.put("title", foundTitle);
            if (authorChange) item.put("author", foundAuthor);
            if (seriesChange) item.put("series", foundSeries);
            if (numberChange) item.put("seriesNumber", foundNumber);
            if (seriesChange || numberChange) item.put("seriesSuggested", false);
            if (!saveLibraryNow(false)) {
                item.put("title", beforeTitle);
                item.put("author", beforeAuthor);
                item.put("series", beforeSeries);
                item.put("seriesNumber", beforeNumber);
                item.put("seriesSuggested", suggested);
                message("Could not save embedded book details");
            } else {
                if (uri.equals(currentUri)) {
                    title.setText(bookTitle(item));
                    if (playback != null) playback.updateDetails(bookTitle(item),
                            metadata(item, "author", ""));
                }
                renderLibrary();
            }
        } catch (JSONException ignored) { }
    }

    private static String cleanTag(String value) {
        if (value == null) return null;
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > 180)
            value = value.substring(0, value.offsetByCodePoints(0, 180));
        return value;
    }

    private void scanChapters(JSONObject item) {
        String name = item.optString("name").toLowerCase(Locale.ROOT);
        String uri = item.optString("uri");
        if (!isOnDeviceUri(uri) || !(name.endsWith(".m4b") || name.endsWith(".m4a") || name.endsWith(".mp4"))
                || chapterCache.containsKey(uri) || !chapterScanInFlight.add(uri)) return;
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            List<Mp4Chapters.Chapter> chapters;
            try (ParcelFileDescriptor descriptor = appContext.getContentResolver().openFileDescriptor(Uri.parse(uri), "r")) {
                if (descriptor == null) throw new IOException("No file descriptor");
                try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
                    chapters = Mp4Chapters.read(stream.getChannel());
                    if (chapters.isEmpty()) chapters = QuickTimeChapters.read(stream.getChannel());
                }
            } catch (IOException | RuntimeException ignored) {
                MAIN.post(() -> {
                    MainActivity activity = owner.get();
                    if (activity != null) activity.chapterScanInFlight.remove(uri);
                });
                return;
            }
            final List<Mp4Chapters.Chapter> found = chapters;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity != null) activity.deliverChapters(item, uri, found);
            });
        });
    }

    private void deliverChapters(JSONObject item, String uri, List<Mp4Chapters.Chapter> chapters) {
        chapterScanInFlight.remove(uri);
        if (!destroyed && find(uri) != null) {
            chapterCache.put(uri, chapters);
            combinedChapterCache.remove(uri);
            if (uri.equals(currentUri)) refreshPlayerUi();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.trim().isEmpty()) {
                    if (value.length() > 512) value = value.substring(0, 512);
                    value = value.replace('\n', ' ').replace('\r', ' ').trim();
                    if (value.codePointCount(0, value.length()) > 180)
                        value = value.substring(0, value.offsetByCodePoints(0, 180));
                    return value;
                }
            }
        } catch (RuntimeException ignored) { /* Provider may not supply a name. */ }
        return "Untitled audio";
    }

    private static String titleFromName(String name) {
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        return title.isEmpty() ? "Untitled audio" : title;
    }

    private String metadata(JSONObject item, String key, String fallback) {
        String value = item.optString(key, "").replace('\n', ' ').replace('\r', ' ').trim();
        if (value.length() > 512) value = value.substring(0, 512).trim();
        if (value.codePointCount(0, value.length()) > 180)
            value = value.substring(0, value.offsetByCodePoints(0, 180));
        return value.isEmpty() ? fallback : value;
    }

    private String bookTitle(JSONObject item) {
        return metadata(item, "title", titleFromName(metadata(item, "name", "Untitled audio")));
    }

    private BigDecimal orderOf(JSONObject item) {
        return LibraryOrdering.parseOrder(metadata(item, "seriesNumber", ""));
    }

    private void renderLibrary() {
        syncBackCallback();
        if (libraryHeading != null) {
            libraryHeading.setText(libraryTab == 0 ? "Authors"
                    : libraryTab == 1 ? "Series" : "Books");
            libraryEyebrow.setText(libraryTab == 0 ? "YOUR AUTHORS"
                    : libraryTab == 1 ? "YOUR SERIES" : "ALL YOUR BOOKS");
            playerShortcut.setVisibility(find(currentUri) == null ? View.GONE : View.VISIBLE);
        }
        if (libraryCount != null) libraryCount.setText(books.isEmpty()
                ? "Your books stay on this device"
                : books.size() + (books.size() == 1 ? " book" : " books") + " · Offline library");
        renderContinueListening();
        List<JSONObject> ordered = new ArrayList<>(books);
        Collections.sort(ordered, (a, b) -> LibraryOrdering.compare(
                metadata(a, "authorSort", metadata(a, "author", UNKNOWN_AUTHOR)),
                metadata(a, "series", STANDALONE),
                metadata(a, "seriesNumber", ""), bookTitle(a), a.optString("uri"),
                metadata(b, "authorSort", metadata(b, "author", UNKNOWN_AUTHOR)),
                metadata(b, "series", STANDALONE),
                metadata(b, "seriesNumber", ""), bookTitle(b), b.optString("uri")));
        shelfItems.clear();
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        Map<String, ShelfItem> groups = new LinkedHashMap<>();
        for (JSONObject item : ordered) {
            String author = metadata(item, "author", UNKNOWN_AUTHOR);
            String series = metadata(item, "series", STANDALONE);
            if (item.optBoolean("archived") != "Archive".equals(selectedShelf)) continue;
            if ("Favorites".equals(selectedShelf) && !item.optBoolean("favorite")) continue;
            if ("Listen later".equals(selectedShelf) && !item.optBoolean("listenLater")) continue;
            if ("Finished".equals(selectedShelf) && !item.optBoolean("finished")) continue;
            if ("In progress".equals(selectedShelf) && (item.optBoolean("finished")
                    || item.optLong("position") <= 0)) continue;
            if ("Recent imports (30 days)".equals(selectedShelf)
                    && item.optLong("importedAt") < System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
                continue;
            if (selectedShelf.startsWith("Tag: ")
                    && !BookFilters.hasTag(metadata(item, "tags", ""), selectedShelf.substring(5))) continue;
            if (selectedAuthor != null && !selectedAuthor.equalsIgnoreCase(author)) continue;
            if (selectedSeries != null && !selectedSeries.equalsIgnoreCase(series)) continue;
            if (!query.isEmpty() && !author.toLowerCase(Locale.ROOT).contains(query)
                    && !series.toLowerCase(Locale.ROOT).contains(query)
                    && !bookTitle(item).toLowerCase(Locale.ROOT).contains(query)
                    && !metadata(item, "authorAliases", "").toLowerCase(Locale.ROOT).contains(query)
                    && !metadata(item, "narrator", "").toLowerCase(Locale.ROOT).contains(query)
                    && !metadata(item, "edition", "").toLowerCase(Locale.ROOT).contains(query)
                    && !metadata(item, "tags", "").toLowerCase(Locale.ROOT).contains(query)) continue;
            if (libraryTab == 2) {
                String partGroup = metadata(item, "partGroup", "");
                String groupKey = LibraryOrdering.bookGroupKey(author, series, bookTitle(item),
                        partGroup, metadata(item, "seriesNumber", ""));
                ShelfItem entry = groups.get(groupKey);
                if (entry == null) {
                    entry = new ShelfItem();
                    entry.book = item;
                    entry.author = author;
                    entry.series = series;
                    entry.label = partGroup.isEmpty() ? bookTitle(item) : partGroup;
                    groups.put(groupKey, entry);
                    shelfItems.add(entry);
                }
                entry.covers.add(item);
                if (!partGroup.isEmpty()) entry.parts.add(item);
                if (entry.parts.isEmpty()) {
                    String order = metadata(item, "seriesNumber", "");
                    entry.subtitle = STANDALONE.equals(series) ? author
                            : series + (orderOf(item) == null ? "" : " · " + order);
                    if (item.optBoolean("finished")) entry.subtitle += " · Finished";
                    else {
                        int percent = ListeningTools.progressPercent(item.optLong("position"),
                                item.optLong("duration"));
                        if (percent > 0) entry.subtitle += " · " + percent + "%";
                    }
                }
            } else {
                String key = libraryTab == 0 ? author.toLowerCase(Locale.ROOT)
                        : author.toLowerCase(Locale.ROOT) + "\u0000" + series.toLowerCase(Locale.ROOT);
                ShelfItem entry = groups.get(key);
                if (entry == null) {
                    entry = new ShelfItem();
                    entry.author = author;
                    entry.series = series;
                    entry.label = libraryTab == 0 ? author : series;
                    groups.put(key, entry);
                }
                entry.covers.add(item);
            }
        }
        if (libraryTab != 2) {
            for (ShelfItem entry : groups.values()) {
                int count = entry.covers.size();
                int finished = 0;
                for (JSONObject book : entry.covers) if (book.optBoolean("finished")) finished++;
                entry.subtitle = libraryTab == 0 ? count + (count == 1 ? " book" : " books")
                        : entry.author + " · " + count + (count == 1 ? " book" : " books")
                        + " · " + finished + " finished";
                if (pinnedGroups().contains(groupKey(entry))) entry.subtitle = "Pinned · " + entry.subtitle;
                placeLeadCoverFirst(entry);
                shelfItems.add(entry);
            }
            if (LibraryPresentation.SPLIT.equals(libraryLayout) && libraryTab == 0)
                shelfItems.sort((a, b) -> {
                    int pinnedOrder = Boolean.compare(pinnedGroups().contains(groupKey(b)),
                            pinnedGroups().contains(groupKey(a)));
                    return pinnedOrder != 0 ? pinnedOrder : a.author.compareToIgnoreCase(b.author);
                });
            else shelfItems.sort((a, b) -> Boolean.compare(
                    pinnedGroups().contains(groupKey(b)), pinnedGroups().contains(groupKey(a))));
        }
        else for (ShelfItem entry : shelfItems) {
            if (entry.parts.isEmpty()) {
                if (entry.covers.size() > 1) entry.subtitle = entry.covers.size()
                        + " editions · " + entry.author;
                continue;
            }
            entry.parts.sort((a, b) -> {
                BigDecimal left = LibraryOrdering.parseOrder(metadata(a, "partOrder", ""));
                BigDecimal right = LibraryOrdering.parseOrder(metadata(b, "partOrder", ""));
                if (left != null && right != null) return left.compareTo(right);
                if (left != null) return -1;
                if (right != null) return 1;
                return bookTitle(a).compareToIgnoreCase(bookTitle(b));
            });
            entry.book = entry.parts.get(0);
            entry.subtitle = entry.parts.size() + (entry.parts.size() == 1 ? " part" : " parts")
                    + " · " + entry.author;
        }
        for (int i = 0; i < tabButtons.length; i++) {
            if (LibraryPresentation.SPLIT.equals(libraryLayout)) {
                tabButtons[i].setBackground(control(i == libraryTab ? PAPER : STONE,
                        i == libraryTab ? PAPER : STONE, PAPER));
                tabButtons[i].setTextColor(INK);
            } else {
                tabButtons[i].setBackground(control(i == libraryTab ? PLUM : PAPER, OUTLINE, BLUSH));
                tabButtons[i].setTextColor(i == libraryTab ? ON_ACTION : INK);
            }
            tabButtons[i].setSelected(i == libraryTab);
        }
        boolean scoped = selectedAuthor != null || selectedSeries != null;
        if (emptyShelfTitle != null) {
            emptyShelfTitle.setText(books.isEmpty() ? R.string.empty_library_title : R.string.empty_view_title);
            emptyShelfDetail.setText(books.isEmpty()
                    ? R.string.empty_library_detail
                    : query.isEmpty() ? R.string.empty_view_detail : R.string.empty_search_detail);
        }
        shelfFilter.setText("Shelf: " + selectedShelf);
        scopeButton.setVisibility(scoped ? View.VISIBLE : View.GONE);
        indexBar.setVisibility(LibraryPresentation.SPLIT.equals(libraryLayout)
                && libraryTab == 0 && selectedAuthor == null ? View.VISIBLE : View.GONE);
        if (scoped) scopeButton.setText(selectedSeries == null ? "← All authors" : "← " + selectedAuthor + " series");
        shelfAdapter.notifyDataSetChanged();
    }

    private void renderContinueListening() {
        if (continuePanel == null || continueBooks == null) return;
        List<JSONObject> candidates = new ArrayList<>();
        for (JSONObject item : books)
            if (!item.optBoolean("finished") && !item.optBoolean("archived")
                    && item.optLong("position") > 0) candidates.add(item);
        candidates.sort((a, b) -> Long.compare(b.optLong("lastPlayedAt"), a.optLong("lastPlayedAt")));
        boolean limitedHeight = getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density < 820f;
        continuePanel.setVisibility(LibraryPresentation.SPLIT.equals(libraryLayout)
                || extraLargeLibrary || candidates.isEmpty()
                || (limitedHeight && find(currentUri) != null) ? View.GONE : View.VISIBLE);
        continueBooks.removeAllViews();
        if (extraLargeLibrary) return;
        for (int i = 0; i < Math.min(8, candidates.size()); i++) {
            JSONObject item = candidates.get(i);
            int percent = ListeningTools.progressPercent(item.optLong("position"),
                    item.optLong("duration"));
            LinearLayout feature = new LinearLayout(this);
            feature.setGravity(Gravity.CENTER_VERTICAL);
            feature.setPadding(dp(8), dp(8), dp(10), dp(8));
            feature.setBackground(control(PAPER, OUTLINE, PLUM));
            feature.setContentDescription("Continue " + bookTitle(item) + ", " + percent + " percent");
            feature.setFocusable(true);
            feature.setOnClickListener(v -> { open(item); showPlayer(true); });
            FrameLayout cover = coverView(item);
            feature.addView(cover, new LinearLayout.LayoutParams(dp(52), dp(70)));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(9), 0, 0, 0);
            TextView small = text("CONTINUE", 10);
            small.setTextColor(INK);
            small.setLetterSpacing(0.08f);
            small.setPadding(0, 0, 0, 0);
            words.addView(small);
            TextView name = text(bookTitle(item), 14);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, 0, 0, dp(3));
            words.addView(name);
            words.addView(progressLine(percent, dp(154)));
            feature.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            applyInterfaceFont(feature);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(240), -2);
            params.rightMargin = dp(8);
            continueBooks.addView(feature, params);
        }
    }

    private void showContinueChoices() {
        List<JSONObject> candidates = new ArrayList<>();
        for (JSONObject item : books)
            if (!item.optBoolean("finished") && item.optLong("position") > 0) candidates.add(item);
        candidates.sort((a, b) -> Long.compare(b.optLong("lastPlayedAt"), a.optLong("lastPlayedAt")));
        if (candidates.isEmpty()) { message("No unfinished books to continue"); return; }
        int count = Math.min(8, candidates.size());
        String[] choices = new String[count];
        for (int i = 0; i < count; i++) choices[i] = bookTitle(candidates.get(i));
        new AlertDialog.Builder(this).setTitle("Continue listening")
                .setItems(choices, (dialog, which) -> {
                    open(candidates.get(which));
                    showPlayer(true);
                }).setNegativeButton("Cancel", null).show();
    }

    private View makeTile(ShelfItem entry, int position) {
        if (LibraryPresentation.SPLIT.equals(libraryLayout)) return makeSplitTile(entry, position);
        if (compactLibrary) return makeCompactTile(entry);
        int columns = Math.max(2, shelf.getNumColumns());
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(40)) / columns;
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(10), dp(10), dp(10), dp(9));
        tile.setBackground(decoratedCard());
        tile.setElevation(dp(2));
        tile.setMinimumHeight(dp(292));
        FrameLayout stack = new FrameLayout(this);
        tile.addView(stack, new LinearLayout.LayoutParams(-1, dp(158)));
        int layers = Math.min(3, entry.covers.size());
        int coverWidth = Math.max(dp(62), cell - dp(39));
        for (int i = layers - 1; i >= 0; i--) {
            JSONObject book = entry.covers.get(i);
            FrameLayout cover = coverView(book);
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(coverWidth, dp(146));
            place.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            place.leftMargin = dp(i * 6);
            place.topMargin = dp(i * 5);
            stack.addView(cover, place);
        }
        TextView category = text(libraryTab == 0 ? "AUTHOR" : libraryTab == 1 ? "SERIES" : "BOOK", 10);
        category.setTextColor(INK);
        category.setLetterSpacing(0.1f);
        category.setPadding(0, dp(3), 0, 0);
        tile.addView(category);
        TextView label = text(entry.label, 16);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, dp(2), 0, 0);
        tile.addView(label);
        TextView subtitle = text(entry.subtitle, 12);
        subtitle.setTextColor(INK);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setPadding(0, 0, 0, dp(4));
        tile.addView(subtitle);
        if (entry.book != null) {
            int percent = entry.book.optBoolean("finished") ? 100
                    : ListeningTools.progressPercent(entry.book.optLong("position"),
                    entry.book.optLong("duration"));
            if (percent > 0) tile.addView(progressLine(percent, -1));
        }
        Button entryActions = button("Actions");
        entryActions.setTextSize(12);
        entryActions.setMinHeight(dp(48));
        entryActions.setContentDescription("Actions for " + entry.label);
        LinearLayout.LayoutParams actionPlace = new LinearLayout.LayoutParams(-1, dp(48));
        actionPlace.topMargin = dp(7);
        tile.addView(entryActions, actionPlace);
        entryActions.setOnClickListener(v -> showEntryActions(entry));
        tile.setContentDescription(entry.label + ", " + entry.subtitle);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> {
            if (libraryTab == 0) {
                selectedAuthor = entry.author;
                selectedSeries = null;
                libraryTab = 1;
                renderLibrary();
            } else if (libraryTab == 1) {
                selectedAuthor = entry.author;
                selectedSeries = entry.series;
                libraryTab = 2;
                renderLibrary();
            } else if (entry.book != null) {
                if (!entry.parts.isEmpty() && entry.parts.size() > 1) showParts(entry, false);
                else if (entry.parts.isEmpty() && entry.covers.size() > 1)
                    showEditionChoices(entry, false);
                else { open(entry.book); showPlayer(true); }
            }
        });
        tile.setOnLongClickListener(v -> { showEntryActions(entry); return true; });
        applyInterfaceFont(tile);
        return tile;
    }

    private View makeSplitTile(ShelfItem entry, int position) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        if (libraryTab == 0) {
            String initial = LibraryPresentation.authorInitial(entry.author);
            boolean pinned = pinnedGroups().contains(groupKey(entry));
            boolean previousPinned = position > 0
                    && pinnedGroups().contains(groupKey(shelfItems.get(position - 1)));
            String previous = position == 0 || previousPinned ? null
                    : LibraryPresentation.authorInitial(shelfItems.get(position - 1).author);
            if ((pinned && !previousPinned) || (!pinned && !initial.equals(previous))) {
                TextView letter = text(pinned ? "PINNED" : initial, pinned ? 11 : 22);
                letter.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL));
                letter.setPadding(dp(2), dp(8), 0, dp(2));
                if (android.os.Build.VERSION.SDK_INT >= 28) letter.setAccessibilityHeading(true);
                item.addView(letter);
            }
        } else if (libraryTab == 2) {
            String section = LibraryPresentation.bookSection(entry.author, entry.series);
            String previous = position == 0 ? null : LibraryPresentation.bookSection(
                    shelfItems.get(position - 1).author, shelfItems.get(position - 1).series);
            if (!section.equals(previous)) {
                TextView group = text(STANDALONE.equals(entry.series) ? entry.author : entry.series, 19);
                group.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
                group.setPadding(dp(2), dp(10), 0, 0);
                if (android.os.Build.VERSION.SDK_INT >= 28) group.setAccessibilityHeading(true);
                item.addView(group);
                if (!STANDALONE.equals(entry.series)) {
                    TextView byline = text(entry.author + " · in series order", 11);
                    byline.setPadding(dp(2), 0, 0, dp(2));
                    item.addView(byline);
                }
            }
        }

        if (libraryTab == 1) {
            LinearLayout seriesCard = new LinearLayout(this);
            seriesCard.setOrientation(LinearLayout.VERTICAL);
            seriesCard.setPadding(dp(12), dp(12), dp(12), dp(8));
            seriesCard.setBackground(decoratedCard());
            LinearLayout headline = new LinearLayout(this);
            headline.setGravity(Gravity.CENTER_VERTICAL);
            headline.addView(splitCoverStack(entry, dp(104), dp(132)),
                    new LinearLayout.LayoutParams(dp(116), dp(132)));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(10), 0, 0, 0);
            boolean standalone = STANDALONE.equals(entry.series);
            TextView name = text(standalone ? entry.author : entry.label, 18);
            name.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
            name.setMaxLines(3);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(name);
            TextView details = text(standalone ? entry.covers.size()
                    + (entry.covers.size() == 1 ? " standalone book" : " standalone books")
                    : entry.subtitle, 12);
            details.setMaxLines(3);
            details.setPadding(0, dp(4), 0, dp(8));
            words.addView(details);
            TextView open = text(standalone ? "Open books  →" : "Open series  →", 12);
            words.addView(open);
            headline.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            Button actions = button("⋯");
            actions.setTextSize(18);
            actions.setContentDescription("Actions for " + entry.label);
            actions.setBackground(control(PAPER, PAPER, STONE));
            actions.setOnClickListener(v -> showEntryActions(entry));
            headline.addView(actions, new LinearLayout.LayoutParams(dp(44), dp(44)));
            seriesCard.addView(headline);
            seriesCard.setContentDescription(entry.label + ", " + entry.subtitle
                    + (standalone ? ". Open books" : ". Open series"));
            seriesCard.setFocusable(true);
            seriesCard.setOnClickListener(v -> openShelfEntry(entry));
            seriesCard.setOnLongClickListener(v -> { showEntryActions(entry); return true; });
            List<JSONObject> ordered = new ArrayList<>(entry.covers);
            ordered.sort((a, b) -> LibraryOrdering.compare(entry.author, entry.series,
                    metadata(a, "seriesNumber", ""), bookTitle(a), a.optString("uri"),
                    entry.author, entry.series, metadata(b, "seriesNumber", ""),
                    bookTitle(b), b.optString("uri")));
            for (int i = 0; i < Math.min(3, ordered.size()); i++) {
                JSONObject book = ordered.get(i);
                String number = metadata(book, "seriesNumber", "");
                String status = book.optBoolean("finished") ? " · Finished"
                        : book.optLong("position") > 0 ? " · Listening" : "";
                TextView line = text((number.isEmpty() ? "•" : number) + "   "
                        + bookTitle(book) + status, 12);
                line.setSingleLine(true);
                line.setEllipsize(android.text.TextUtils.TruncateAt.END);
                line.setMinHeight(dp(44));
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setContentDescription("Open " + bookTitle(book) + status);
                line.setOnClickListener(v -> { open(book); showPlayer(true); });
                seriesCard.addView(line);
            }
            item.addView(seriesCard);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(5), dp(3), dp(5));
            JSONObject lead = entry.covers.isEmpty() ? entry.book : entry.covers.get(0);
            if (libraryTab == 0 && !entry.covers.isEmpty())
                row.addView(splitCoverStack(entry, dp(54), dp(70)),
                        new LinearLayout.LayoutParams(dp(64), dp(70)));
            else if (libraryTab == 2 && lead != null)
                row.addView(coverView(lead), new LinearLayout.LayoutParams(dp(44), dp(60)));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(10), 0, dp(3), 0);
            if (libraryTab == 2) {
                String number = entry.book == null ? "" : metadata(entry.book, "seriesNumber", "");
                TextView status = text((number.isEmpty() ? "BOOK" : "BOOK " + number)
                        + (entry.book != null && entry.book.optBoolean("finished") ? " · FINISHED" : ""), 10);
                status.setPadding(0, 0, 0, 0);
                words.addView(status);
            }
            TextView name = text(entry.label, libraryTab == 0 ? 18 : 16);
            name.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
            name.setMaxLines(libraryTab == 0 || libraryTab == 2 ? 2 : 1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, 0, 0, 0);
            words.addView(name);
            TextView detail = text(entry.subtitle, 11);
            detail.setMaxLines(2);
            detail.setPadding(0, 0, 0, 0);
            words.addView(detail);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            Button actions = button("⋯");
            actions.setContentDescription("Actions for " + entry.label);
            actions.setTextSize(18);
            actions.setBackground(control(STONE, STONE, PAPER));
            actions.setOnClickListener(v -> showEntryActions(entry));
            row.addView(actions, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.setContentDescription(entry.label + ", " + entry.subtitle);
            row.setFocusable(true);
            row.setOnClickListener(v -> openShelfEntry(entry));
            row.setOnLongClickListener(v -> { showEntryActions(entry); return true; });
            item.addView(row);
            View divider = new View(this);
            divider.setBackgroundColor(OUTLINE);
            item.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        applyInterfaceFont(item);
        return item;
    }

    private FrameLayout splitCoverStack(ShelfItem entry, int width, int height) {
        FrameLayout stack = new FrameLayout(this);
        int count = Math.min(3, entry.covers.size());
        for (int i = count - 1; i >= 0; i--) {
            FrameLayout cover = coverView(entry.covers.get(i));
            if (cover.getChildCount() > 0 && cover.getChildAt(0) instanceof TextView) {
                TextView fallback = (TextView) cover.getChildAt(0);
                String label = bookTitle(entry.covers.get(i));
                fallback.setText(label.isEmpty() ? "D" : label.substring(0,
                        label.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
                fallback.setTextSize(28);
            }
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(width - dp(24), height - dp(14));
            place.leftMargin = dp(i * 9);
            place.topMargin = dp(i * 5);
            stack.addView(cover, place);
        }
        return stack;
    }

    private View makeCompactTile(ShelfItem entry) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(decoratedCard());
        JSONObject lead = entry.covers.isEmpty() ? entry.book : entry.covers.get(0);
        if (lead != null) {
            FrameLayout smallCover = coverView(lead);
            TextView fallback = (TextView) smallCover.getChildAt(0);
            String name = bookTitle(lead);
            fallback.setText(name.isEmpty() ? "D" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            fallback.setTextSize(24);
            row.addView(smallCover, new LinearLayout.LayoutParams(dp(52), dp(68)));
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10), 0, dp(6), 0);
        TextView label = text(entry.label, 16);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(label);
        TextView detail = text(entry.subtitle, 12);
        detail.setTextColor(INK);
        detail.setMaxLines(2);
        words.addView(detail);
        if (entry.book != null) {
            int percent = entry.book.optBoolean("finished") ? 100
                    : ListeningTools.progressPercent(entry.book.optLong("position"), entry.book.optLong("duration"));
            if (percent > 0) words.addView(progressLine(percent, -1));
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Button actions = button("Actions");
        actions.setContentDescription("Actions for " + entry.label);
        actions.setOnClickListener(v -> showEntryActions(entry));
        row.addView(actions, new LinearLayout.LayoutParams(dp(92), dp(48)));
        row.setContentDescription(entry.label + ", " + entry.subtitle);
        row.setFocusable(true);
        row.setOnClickListener(v -> openShelfEntry(entry));
        applyInterfaceFont(row);
        return row;
    }

    private void openShelfEntry(ShelfItem entry) {
        if (libraryTab == 0) {
            selectedAuthor = entry.author;
            selectedSeries = null;
            libraryTab = 1;
            renderLibrary();
        } else if (libraryTab == 1) {
            selectedAuthor = entry.author;
            selectedSeries = entry.series;
            libraryTab = 2;
            renderLibrary();
        } else if (entry.book != null) {
            if (!entry.parts.isEmpty() && entry.parts.size() > 1) showParts(entry, false);
            else if (entry.parts.isEmpty() && entry.covers.size() > 1)
                showEditionChoices(entry, false);
            else { open(entry.book); showPlayer(true); }
        }
    }

    private void showEntryActions(ShelfItem entry) {
        if (entry.book == null) showGroupActions(entry);
        else if (!entry.parts.isEmpty() && entry.parts.size() > 1) showParts(entry, true);
        else if (entry.parts.isEmpty() && entry.covers.size() > 1)
            showEditionChoices(entry, true);
        else showBookActions(entry.book);
    }

    private String groupKey(ShelfItem entry) {
        String author = entry.author.toLowerCase(Locale.ROOT);
        return libraryTab == 0 ? "author:" + author
                : "series:" + author + "\u0000" + entry.series.toLowerCase(Locale.ROOT);
    }

    private Set<String> pinnedGroups() {
        return prefs.getStringSet("pinned_groups", Collections.emptySet());
    }

    private void placeLeadCoverFirst(ShelfItem entry) {
        try {
            String preferred = new JSONObject(prefs.getString("lead_covers", "{}"))
                    .optString(groupKey(entry));
            if (preferred.isEmpty()) return;
            for (int i = 1; i < entry.covers.size(); i++)
                if (preferred.equals(entry.covers.get(i).optString("uri"))) {
                    JSONObject selected = entry.covers.remove(i);
                    entry.covers.add(0, selected);
                    return;
                }
        } catch (JSONException ignored) { }
    }

    private void showGroupActions(ShelfItem entry) {
        boolean pinned = pinnedGroups().contains(groupKey(entry));
        String[] actions = libraryTab == 0
                ? new String[]{pinned ? "Unpin author" : "Pin author", "Choose lead cover"}
                : new String[]{pinned ? "Unpin series" : "Pin series", "Reading order",
                "Choose lead cover"};
        new AlertDialog.Builder(this).setTitle(entry.label)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        Set<String> next = new HashSet<>(pinnedGroups());
                        if (pinned) next.remove(groupKey(entry)); else next.add(groupKey(entry));
                        if (!prefs.edit().putStringSet("pinned_groups", next).commit())
                            message("Could not save pinned group");
                        else renderLibrary();
                    } else if (libraryTab == 1 && which == 1) showSeriesReadingOrder(entry);
                    else chooseLeadCover(entry);
                }).setNegativeButton("Cancel", null).show();
    }

    private void showSeriesReadingOrder(ShelfItem entry) {
        List<JSONObject> ordered = new ArrayList<>(entry.covers);
        ordered.sort((a, b) -> LibraryOrdering.compare(
                entry.author, entry.series, metadata(a, "seriesNumber", ""), bookTitle(a), a.optString("uri"),
                entry.author, entry.series, metadata(b, "seriesNumber", ""), bookTitle(b), b.optString("uri")));
        String[] labels = new String[ordered.size()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject book = ordered.get(i);
            String order = metadata(book, "seriesNumber", "");
            labels[i] = (book.optBoolean("finished") ? "✓ " : "○ ")
                    + (order.isEmpty() ? "Unnumbered" : "#" + order) + " · " + bookTitle(book);
        }
        new AlertDialog.Builder(this).setTitle(entry.series + " · reading order")
                .setItems(labels, (dialog, which) -> {
                    open(ordered.get(which)); showPlayer(true);
                }).setNegativeButton("Close", null).show();
    }

    private void chooseLeadCover(ShelfItem entry) {
        String[] labels = new String[entry.covers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = bookTitle(entry.covers.get(i));
        new AlertDialog.Builder(this).setTitle("Lead cover for " + entry.label)
                .setItems(labels, (dialog, which) -> {
                    try {
                        JSONObject choices = new JSONObject(prefs.getString("lead_covers", "{}"));
                        if (choices.length() >= 200 && !choices.has(groupKey(entry))) {
                            message("Too many custom covers"); return;
                        }
                        choices.put(groupKey(entry), entry.covers.get(which).optString("uri"));
                        if (!prefs.edit().putString("lead_covers", choices.toString()).commit())
                            message("Could not save lead cover");
                        else renderLibrary();
                    } catch (JSONException e) { message("Could not save lead cover"); }
                }).setNegativeButton("Cancel", null).show();
    }

    private FrameLayout coverView(JSONObject book) {
        FrameLayout cover = new FrameLayout(this);
        boolean useAction = (bookTitle(book).hashCode() & 1) == 0;
        int color = useAction ? PLUM : STONE;
        GradientDrawable paper = new GradientDrawable();
        paper.setColor(color);
        paper.setCornerRadius(dp(6));
        cover.setBackground(paper);
        TextView fallback = text(bookTitle(book), 12);
        fallback.setTextColor(useAction ? ON_ACTION : OUTSIDE);
        fallback.setTypeface(null, android.graphics.Typeface.BOLD);
        fallback.setGravity(Gravity.CENTER);
        fallback.setMaxLines(4);
        fallback.setPadding(dp(5), dp(5), dp(5), dp(5));
        cover.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(View.GONE);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        String coverUri = book.optString("coverUri", "");
        String uri = coverUri.isEmpty() ? book.optString("uri") : coverUri;
        image.setTag(uri);
        requestArtwork(uri, image, !coverUri.isEmpty(), book.optString("folderCoverUri", ""));
        return cover;
    }

    private void requestArtwork(String uri, ImageView image, boolean standalone, String folderCover) {
        Bitmap found = artwork.get(uri);
        if (found != null) { image.setImageBitmap(found); image.setVisibility(View.VISIBLE); return; }
        if (absentArtwork.contains(uri)) return;
        List<WeakReference<ImageView>> waiting = waitingArtwork.get(uri);
        if (waiting != null) { addArtworkWaiter(waiting, image); return; }
        waiting = new ArrayList<>();
        addArtworkWaiter(waiting, image);
        waitingArtwork.put(uri, waiting);
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            Bitmap result = standalone ? decodeStandaloneImage(appContext, uri)
                    : decodeArtwork(appContext, uri);
            if (result == null && !standalone && isOnDeviceUri(folderCover))
                result = decodeStandaloneImage(appContext, folderCover);
            Bitmap completed = result;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity != null) activity.deliverArtwork(uri, completed);
            });
        });
    }

    private static void addArtworkWaiter(List<WeakReference<ImageView>> waiting, ImageView image) {
        for (Iterator<WeakReference<ImageView>> iterator = waiting.iterator(); iterator.hasNext();) {
            ImageView target = iterator.next().get();
            if (target == image) return;
            if (target == null) iterator.remove();
        }
        if (waiting.size() >= MAX_ARTWORK_WAITERS) waiting.remove(0);
        waiting.add(new WeakReference<>(image));
    }

    private static Bitmap decodeArtwork(Context appContext, String uri) {
        if (!isOnDeviceUri(uri)) return null;
        try (ParcelFileDescriptor descriptor = appContext.getContentResolver()
                .openFileDescriptor(Uri.parse(uri), "r")) {
            if (descriptor == null) return null;
            byte[] bytes;
            try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
                bytes = EmbeddedArtwork.read(stream.getChannel());
            }
            if (bytes == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 10000 || bounds.outHeight > 10000) return null;
            BitmapFactory.Options sized = new BitmapFactory.Options();
            sized.inSampleSize = 1;
            while (bounds.outWidth / sized.inSampleSize > 240
                    || bounds.outHeight / sized.inSampleSize > 240) sized.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sized);
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) { return null; }
    }

    private static Bitmap decodeStandaloneImage(Context appContext, String uri) {
        if (!isOnDeviceUri(uri)) return null;
        try (InputStream stream = appContext.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (stream == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (bytes.size() + count > 2 * 1024 * 1024) return null;
                bytes.write(chunk, 0, count);
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 10000 || bounds.outHeight > 10000) return null;
            BitmapFactory.Options scaled = new BitmapFactory.Options();
            scaled.inSampleSize = 1;
            while (bounds.outWidth / scaled.inSampleSize > 240
                    || bounds.outHeight / scaled.inSampleSize > 240) scaled.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, scaled);
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) { return null; }
    }

    private void deliverArtwork(String uri, Bitmap result) {
        if (destroyed) return;
        List<WeakReference<ImageView>> targets = waitingArtwork.remove(uri);
        if (result != null) artwork.put(uri, result);
        else absentArtwork.add(uri);
        JSONObject selected = find(currentUri);
        if (selected != null && uri.equals(selected.optString("coverUri", "").isEmpty()
                ? selected.optString("uri") : selected.optString("coverUri")))
            updatePlayerAccent(result);
        if (targets != null && result != null) for (WeakReference<ImageView> ref : targets) {
            ImageView target = ref.get();
            if (target != null && uri.equals(target.getTag())) {
                target.setImageBitmap(result);
                target.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showBookActions(JSONObject item) {
        new AlertDialog.Builder(this)
                .setTitle(bookTitle(item))
                .setItems(new String[]{"Edit book details", "Relink on-device file",
                        "Choose local cover", "Use embedded cover", "Listen next",
                        item.optBoolean("favorite") ? "Remove favorite" : "Add to favorites",
                        item.optBoolean("listenLater") ? "Remove from Listen later" : "Add to Listen later",
                        item.optBoolean("archived") ? "Restore from archive" : "Archive book",
                        "Characters and places", "File source and details", "Remove listing"}, (dialog, which) -> {
                    if (which == 0) editBook(item);
                    else if (which == 1) pickReplacement(item);
                    else if (which == 2) pickCover(item);
                    else if (which == 3) useEmbeddedCover(item);
                    else if (which == 4) queueBook(item);
                    else if (which == 5) toggleBookFlag(item, "favorite");
                    else if (which == 6) toggleBookFlag(item, "listenLater");
                    else if (which == 7) toggleBookFlag(item, "archived");
                    else if (which == 8) showCharacterNotes(item);
                    else if (which == 9) showFileSource(item);
                    else new AlertDialog.Builder(this)
                            .setTitle("Remove from Arioso?")
                            .setMessage("The original audio file will stay on your device.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove listing", (confirm, button) -> remove(item))
                            .show();
                }).show();
    }

    private void showCharacterNotes(JSONObject book) {
        JSONArray saved = book.optJSONArray("characters");
        int count = saved == null ? 0 : Math.min(saved.length(), 100);
        String[] entries = new String[count + 1];
        entries[0] = "Add character or place";
        for (int i = 0; i < count; i++) {
            JSONObject item = saved.optJSONObject(i);
            entries[i + 1] = item == null ? "(Invalid entry)"
                    : item.optString("name") + " · " + item.optString("note");
        }
        new AlertDialog.Builder(this).setTitle("Characters and places · " + bookTitle(book))
                .setItems(entries, (dialog, which) -> {
                    if (which == 0) editCharacterNote(book, -1);
                    else editCharacterNote(book, which - 1);
                }).setNegativeButton("Close", null).show();
    }

    private void editCharacterNote(JSONObject book, int index) {
        JSONArray saved = book.optJSONArray("characters");
        if (index < 0 && saved != null && saved.length() >= 100) {
            message("This book has 100 entries already"); return;
        }
        JSONObject prior = index >= 0 && saved != null ? saved.optJSONObject(index) : null;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(8), dp(18), 0);
        EditText name = new EditText(this);
        name.setHint("Name");
        name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setText(prior == null ? "" : prior.optString("name"));
        fields.addView(name);
        EditText note = new EditText(this);
        note.setHint("Private note");
        note.setMinLines(3);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        note.setText(prior == null ? "" : prior.optString("note"));
        fields.addView(note);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? "Add character or place" : "Edit character or place")
                .setView(fields).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (ignored, which) -> {
                    String label = name.getText().toString().trim();
                    if (label.isEmpty()) { message("Enter a name"); return; }
                    JSONArray next = new JSONArray();
                    JSONArray current = book.optJSONArray("characters");
                    if (current != null) for (int i = 0; i < current.length(); i++) {
                        if (i == index) continue;
                        JSONObject item = current.optJSONObject(i);
                        if (item != null) next.put(item);
                    }
                    JSONObject entry = new JSONObject();
                    try {
                        entry.put("name", label);
                        entry.put("note", note.getText().toString().trim());
                        next.put(entry);
                        book.put("characters", next);
                        if (!saveLibraryNow(false)) {
                            if (current == null) book.remove("characters");
                            else book.put("characters", current);
                            message("Could not save this note");
                        }
                    } catch (JSONException e) { message("Could not save this note"); }
                });
        if (index >= 0) dialog.setNeutralButton("Delete", (ignored, which) -> {
            JSONArray current = book.optJSONArray("characters");
            if (current == null) return;
            JSONArray next = new JSONArray();
            for (int i = 0; i < current.length(); i++) if (i != index) next.put(current.opt(i));
            try {
                book.put("characters", next);
                if (!saveLibraryNow(false)) {
                    book.put("characters", current);
                    message("Could not remove this note");
                }
            } catch (JSONException e) { message("Could not remove this note"); }
        });
        dialog.show();
    }

    private void toggleBookFlag(JSONObject item, String field) {
        boolean old = item.optBoolean(field);
        try {
            item.put(field, !old);
            if (!saveLibraryNow(false)) {
                item.put(field, old);
                message("Could not save this book's shelf");
                return;
            }
            renderLibrary();
        } catch (JSONException e) { message("Could not save this book's shelf"); }
    }

    private void showFileSource(JSONObject item) {
        String tree = item.optString("treeUri", "");
        String source = tree.isEmpty() ? "Selected file" : "Imported folder";
        String size = item.optLong("sourceSize", -1) >= 0
                ? item.optLong("sourceSize") + " bytes" : "Unknown";
        long importedAt = item.optLong("importedAt");
        String imported = importedAt > 0
                ? java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(importedAt))
                : "Before import dates were recorded";
        new AlertDialog.Builder(this).setTitle("On-device file source")
                .setMessage("Source: " + source + "\nName: " + metadata(item, "name", "Unknown")
                        + "\nSize: " + size + "\nAdded: " + imported
                        + "\n\nOriginal audio stays with Android's file provider.")
                .setPositiveButton("Close", null).show();
    }

    private void showEditionChoices(ShelfItem group, boolean manage) {
        String[] options = new String[group.covers.size()];
        for (int i = 0; i < group.covers.size(); i++) {
            JSONObject item = group.covers.get(i);
            options[i] = metadata(item, "edition", "Edition " + (i + 1))
                    + " · " + metadata(item, "narrator", "Unknown narrator")
                    + " · " + editionFormat(item)
                    + " · " + (item.optLong("duration") > 0
                    ? time((int) Math.min(Integer.MAX_VALUE, item.optLong("duration")))
                    : "duration unknown");
        }
        new AlertDialog.Builder(this).setTitle(group.label + (manage ? " · manage editions" : " · editions"))
                .setItems(options, (dialog, which) -> {
                    JSONObject selected = group.covers.get(which);
                    if (manage) showBookActions(selected);
                    else { open(selected); showPlayer(true); }
                }).setNegativeButton("Close", null).show();
    }

    private String editionFormat(JSONObject item) {
        String name = metadata(item, "name", "");
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "format unknown";
    }

    private void showParts(ShelfItem group, boolean manage) {
        String[] options = new String[group.parts.size() + (manage ? 0 : 1)];
        if (!manage) options[0] = "Continue multi-part title";
        for (int i = 0; i < group.parts.size(); i++) {
            JSONObject part = group.parts.get(i);
            options[i + (manage ? 0 : 1)] = "Part " + metadata(part, "partOrder", "?")
                    + " · " + bookTitle(part);
        }
        new AlertDialog.Builder(this).setTitle(group.label + (manage ? " · manage parts" : ""))
                .setItems(options, (dialog, which) -> {
                    if (!manage && which == 0) {
                        JSONObject next = group.parts.get(0);
                        for (JSONObject part : group.parts) if (!part.optBoolean("finished")) {
                            next = part; break;
                        }
                        open(next); showPlayer(true);
                    } else {
                        JSONObject part = group.parts.get(which - (manage ? 0 : 1));
                        if (manage) showBookActions(part);
                        else { open(part); showPlayer(true); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText detailField(String label, String value, LinearLayout parent) {
        EditText field = new EditText(this);
        field.setHint(label);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(180)});
        field.setText(value);
        parent.addView(field);
        return field;
    }

    private void editBook(JSONObject item) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(8), dp(18), dp(4));
        EditText titleField = detailField("Title", bookTitle(item), fields);
        EditText authorField = detailField("Author", metadata(item, "author", ""), fields);
        EditText sortField = detailField("Author sort name (optional)", metadata(item, "authorSort", ""), fields);
        EditText aliasesField = detailField("Author aliases (optional)", metadata(item, "authorAliases", ""), fields);
        EditText seriesField = detailField("Series (leave blank for standalone)", metadata(item, "series", ""), fields);
        EditText numberField = detailField("Series order, e.g. 1 or 1.5", metadata(item, "seriesNumber", ""), fields);
        numberField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        numberField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        EditText narratorField = detailField("Narrator (optional)", metadata(item, "narrator", ""), fields);
        EditText editionField = detailField("Edition (optional)", metadata(item, "edition", ""), fields);
        EditText tagsField = detailField("Shelves, separated by commas", metadata(item, "tags", ""), fields);
        EditText noteField = detailField("Private book note (optional)", metadata(item, "note", ""), fields);
        noteField.setSingleLine(false);
        noteField.setMinLines(2);
        noteField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        EditText groupField = detailField("Multi-part title (optional)", metadata(item, "partGroup", ""), fields);
        EditText partField = detailField("Part number within that title", metadata(item, "partOrder", ""), fields);
        partField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        partField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        ScrollView scroller = new ScrollView(this);
        scroller.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Book details")
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String titleValue = titleField.getText().toString().trim();
            String authorValue = authorField.getText().toString().trim();
            String sortValue = sortField.getText().toString().trim();
            String aliasesValue = aliasesField.getText().toString().trim();
            String seriesValue = seriesField.getText().toString().trim();
            String numberValue = numberField.getText().toString().trim();
            String narratorValue = narratorField.getText().toString().trim();
            String editionValue = editionField.getText().toString().trim();
            String tagsValue = tagsField.getText().toString().trim();
            String noteValue = noteField.getText().toString().trim();
            String groupValue = groupField.getText().toString().trim();
            String partValue = partField.getText().toString().trim();
            if (titleValue.isEmpty()) { titleField.setError("Enter a title"); return; }
            if (!numberValue.isEmpty() && LibraryOrdering.parseOrder(numberValue) == null) {
                numberField.setError("Use -9999 to 9999 with up to 2 decimals"); return;
            }
            if (seriesValue.isEmpty()) numberValue = "";
            BigDecimal partNumber = LibraryOrdering.parseOrder(partValue);
            if (!groupValue.isEmpty() && (partNumber == null
                    || partNumber.signum() <= 0)) {
                partField.setError("Enter a positive part number up to 9999.99"); return;
            }
            if (groupValue.isEmpty()) partValue = "";
            if (!groupValue.isEmpty()) for (JSONObject other : books) {
                if (other == item || !groupValue.equalsIgnoreCase(other.optString("partGroup"))
                        || !authorValue.equalsIgnoreCase(other.optString("author"))
                        || !seriesValue.equalsIgnoreCase(other.optString("series"))) continue;
                BigDecimal otherNumber = LibraryOrdering.parseOrder(other.optString("partOrder"));
                if (otherNumber != null && partNumber.compareTo(otherNumber) == 0) {
                    partField.setError("This title already has that part number"); return;
                }
            }
            String previousTitle = item.optString("title", "");
            String previousAuthor = item.optString("author", "");
            String previousSort = item.optString("authorSort", "");
            String previousAliases = item.optString("authorAliases", "");
            String previousSeries = item.optString("series", "");
            String previousNumber = item.optString("seriesNumber", "");
            String previousNarrator = item.optString("narrator", "");
            String previousEdition = item.optString("edition", "");
            String previousTags = item.optString("tags", "");
            String previousNote = item.optString("note", "");
            String previousGroup = item.optString("partGroup", "");
            String previousPart = item.optString("partOrder", "");
            boolean previousSuggestion = item.optBoolean("seriesSuggested");
            boolean previousManual = item.optBoolean("manualEdited");
            try {
                JSONObject undoCopy = new JSONObject(item.toString());
                item.put("title", titleValue);
                item.put("author", authorValue);
                item.put("authorSort", sortValue);
                item.put("authorAliases", aliasesValue);
                item.put("series", seriesValue);
                item.put("seriesNumber", numberValue);
                item.put("seriesSuggested", false);
                item.put("narrator", narratorValue);
                item.put("edition", editionValue);
                item.put("tags", tagsValue);
                item.put("note", noteValue);
                item.put("partGroup", groupValue);
                item.put("partOrder", partValue);
                item.put("manualEdited", true);
                if (!saveLibraryNow(false)) {
                    item.put("title", previousTitle);
                    item.put("author", previousAuthor);
                    item.put("authorSort", previousSort);
                    item.put("authorAliases", previousAliases);
                    item.put("series", previousSeries);
                    item.put("seriesNumber", previousNumber);
                    item.put("seriesSuggested", previousSuggestion);
                    item.put("narrator", previousNarrator);
                    item.put("edition", previousEdition);
                    item.put("tags", previousTags);
                    item.put("note", previousNote);
                    item.put("partGroup", previousGroup);
                    item.put("partOrder", previousPart);
                    item.put("manualEdited", previousManual);
                    message("Could not save book details");
                    return;
                }
                if (item.optString("uri").equals(currentUri)) {
                    title.setText(titleValue);
                    if (playback != null) playback.updateDetails(titleValue, authorValue);
                }
                rememberMetadataUndo(Collections.singletonList(undoCopy));
                renderLibrary();
                dialog.dismiss();
            } catch (JSONException e) { message("Could not save book details"); }
        }));
        dialog.show();
    }

    private void open(JSONObject item) {
        if (!isOnDeviceUri(item.optString("uri"))) {
            message("This listing needs an on-device file. Remove it and add the local copy.");
            return;
        }
        if (sourceChanged(item)) {
            new AlertDialog.Builder(this).setTitle("This audio file changed")
                    .setMessage("Its size or modified date differs from the file originally added. Check that it is the same audiobook before continuing; saved progress may point to a different place.")
                    .setNegativeButton("Cancel", (dialog, which) -> showPlayer(false))
                    .setNeutralButton("Trust change", (dialog, which) -> {
                        if (updateSourceBaseline(item)) {
                            openVerified(item);
                            showPlayer(true);
                            offerLocalSync(item);
                        }
                    })
                    .setPositiveButton("Open anyway", (dialog, which) -> {
                        openVerified(item);
                        showPlayer(true);
                        offerLocalSync(item);
                    })
                    .show();
            return;
        }
        openVerified(item);
        offerLocalSync(item);
    }

    private void offerLocalSync(JSONObject item) {
        String url = item.optString("uri", "");
        if (url.isEmpty() || url.length() > 4096 || url.equals(lastLocalSuggestionUrl)) return;
        lastLocalSuggestionUrl = url;
        int generation = localSuggestionGeneration;
        localSyncWorker.execute(() -> {
            try {
                android.content.pm.ProviderInfo provider = getPackageManager()
                    .resolveContentProvider("app.petallibrary.localsync", 0);
                if (provider == null || !"app.petallibrary".equals(provider.packageName)
                        || !AriosoPositionProvider.certificateMatches(getPackageManager())) return;
                Uri request = new Uri.Builder().scheme("content")
                    .authority("app.petallibrary.localsync").appendPath("suggestion")
                    .appendQueryParameter("side", "audio")
                    .appendQueryParameter("bookUrl", url).build();
                try (Cursor rows = getContentResolver().query(request, null, null, null, null)) {
                    if (rows == null || !rows.moveToFirst()) return;
                    String pair = rows.getString(rows.getColumnIndexOrThrow("pairId"));
                    String sourceTitle = rows.getString(rows.getColumnIndexOrThrow("sourceTitle"));
                    double source = rows.getDouble(rows.getColumnIndexOrThrow("sourceProgress"));
                    double target = rows.getDouble(rows.getColumnIndexOrThrow("targetProgress"));
                    if (pair == null || pair.length() > 128 || sourceTitle == null
                            || sourceTitle.length() > 512 || !Double.isFinite(source)
                            || !Double.isFinite(target)) return;
                    runOnUiThread(() -> {
                        if (destroyed || !showingPlayer || generation != localSuggestionGeneration
                                || !url.equals(currentUri) || isFinishing()) return;
                        new AlertDialog.Builder(this).setTitle("Reader has another saved place")
                            .setMessage("Reading “" + sourceTitle + "” · "
                                + Math.round(source * 100) + "%\nArioso · "
                                + Math.round(target * 100) + "%\n\nReview both places in Reader Sync before changing either one.")
                            .setNegativeButton("Keep Arioso place", null)
                            .setPositiveButton("Review in Reader Sync", (dialog, which) -> {
                                Intent review = new Intent();
                                review.setClassName("app.petallibrary", "app.petallibrary.MainActivity");
                                review.putExtra("catalogPairId", pair);
                                try { startActivity(review); }
                                catch (RuntimeException failure) { message("Reader Sync is unavailable"); }
                            }).show();
                    });
                }
            } catch (RuntimeException ignored) { }
        });
    }

    private boolean sourceChanged(JSONObject item) {
        if (!item.has("sourceSize") && !item.has("sourceModified")) return false;
        try (Cursor details = getContentResolver().query(Uri.parse(item.optString("uri")),
                new String[]{OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (details == null || !details.moveToFirst()) return false;
            if (item.has("sourceSize") && details.getColumnCount() > 0 && !details.isNull(0)
                    && item.optLong("sourceSize") != details.getLong(0)) return true;
            return item.has("sourceModified") && details.getColumnCount() > 1 && !details.isNull(1)
                    && item.optLong("sourceModified") > 0 && details.getLong(1) > 0
                    && item.optLong("sourceModified") != details.getLong(1);
        } catch (RuntimeException ignored) { return false; }
    }

    private boolean updateSourceBaseline(JSONObject item) {
        long previousSize = item.optLong("sourceSize", -1);
        long previousModified = item.optLong("sourceModified", -1);
        try (Cursor details = getContentResolver().query(Uri.parse(item.optString("uri")),
                new String[]{OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (details == null || !details.moveToFirst()) { message("Could not check changed file"); return false; }
            if (details.getColumnCount() > 0 && !details.isNull(0))
                item.put("sourceSize", details.getLong(0));
            if (details.getColumnCount() > 1 && !details.isNull(1))
                item.put("sourceModified", details.getLong(1));
            if (saveLibraryNow(false)) return true;
        } catch (RuntimeException | JSONException ignored) { }
        try {
            if (previousSize < 0) item.remove("sourceSize");
            else item.put("sourceSize", previousSize);
            if (previousModified < 0) item.remove("sourceModified");
            else item.put("sourceModified", previousModified);
        } catch (JSONException ignored) { }
        message("Could not save the changed file details");
        return false;
    }

    private void openVerified(JSONObject item) {
        if (pendingJumpUri != null && !pendingJumpUri.equals(item.optString("uri")))
            pendingJumpUri = null;
        scanChapters(item);
        savePosition();
        currentUri = item.optString("uri");
        if (!prefs.edit().putString("selected_uri", currentUri).commit())
            message("Could not save the selected title for restart");
        if (playback != null) loadSelected(item);
        refreshPlayerUi();
    }

    private void loadSelected(JSONObject item) {
        if (!isOnDeviceUri(item.optString("uri"))) return;
        float bookSpeed = (float) item.optDouble("playbackSpeed", speedValues[speedIndex]);
        int chosenSpeed = speedIndex;
        for (int i = 0; i < speedValues.length; i++)
            if (Math.abs(speedValues[i] - bookSpeed) < .001f) { chosenSpeed = i; break; }
        selectingBookSpeed = true;
        speedIndex = chosenSpeed;
        speed.setSelection(chosenSpeed);
        selectingBookSpeed = false;
        playback.load(item.optString("uri"), bookTitle(item),
                metadata(item, "author", ""), item.optInt("position"), item.optBoolean("finished"));
        playback.setSpeed(speedValues[speedIndex]);
        playback.setPitch((float) item.optDouble("playbackPitch", 1.0));
        playback.setVoiceBoost(item.optBoolean("voiceBoost", false));
        playback.setSkipSilence(item.optBoolean("skipSilence", false));
    }

    private void refreshPlayerUi() {
        if (title == null || play == null || seek == null
                || outputRoute == null || repeatButton == null) return;
        if (playback != null && playback.currentUri() != null
                && !playback.currentUri().equals(currentUri)
                && find(playback.currentUri()) != null) {
            currentUri = playback.currentUri();
            scanChapters(find(currentUri));
        }
        JSONObject selected = currentUri == null ? null : find(currentUri);
        title.setText(selected == null ? "No audio selected" : bookTitle(selected));
        if (boostButton != null) boostButton.setText(selected != null
                && selected.optBoolean("voiceBoost") ? "Voice boost · On" : "Voice boost · Off");
        if (silenceButton != null) silenceButton.setText(selected != null
                && selected.optBoolean("skipSilence")
                ? playback != null && playback.isSilenceUnavailable()
                ? "Skip silence · Unavailable for this file" : "Skip silence · On"
                : "Skip silence · Off");
        if (companionButton != null) companionButton.setText(selected != null
                && !selected.optString("companionPdfUri").isEmpty()
                ? "Open companion PDF" : "Choose companion PDF");
        playerByline.setText(selected == null ? "Your audio stays on this device"
                : metadata(selected, "author", UNKNOWN_AUTHOR)
                + (STANDALONE.equals(metadata(selected, "series", STANDALONE)) ? ""
                : "  ·  " + metadata(selected, "series", STANDALONE)));
        updatePlayerCover(selected);
        boolean currentReady = playback != null && currentUri != null
                && currentUri.equals(playback.currentUri()) && playback.isReady();
        if (currentReady && pendingJumpUri != null && pendingJumpUri.equals(currentUri)) {
            int target = pendingJumpPosition;
            pendingJumpUri = null;
            playback.seekTo(target);
        }
        play.setEnabled(currentReady);
        play.setAlpha(currentReady ? 1f : 0.55f);
        seek.setEnabled(currentReady);
        play.setText(currentReady && playback.isPlaying() ? "Pause" : "Play");
        if (selected == null) { clock.setText("0:00 / 0:00"); timeRemaining.setText(""); }
        else if (playback == null || !currentUri.equals(playback.currentUri()))
            { clock.setText("Connecting…"); timeRemaining.setText(""); }
        else if (playback.isUnavailable())
            { clock.setText(playback.failureReason()); timeRemaining.setText(""); }
        else if (!currentReady) { clock.setText("Loading…"); timeRemaining.setText(""); }
        else {
            clock.setText(time(playback.position()) + " / " + time(playback.duration()));
            timeRemaining.setText("Time left  ·  "
                    + time(Math.max(0, playback.duration() - playback.position())));
        }
        renderChapterProgress(currentReady ? playback.position() : 0,
                currentReady ? playback.duration() : 0);
        outputRoute.setText(getString(R.string.output_route,
                currentReady ? playback.outputRoute() : "Unknown"));
        if (playback == null || playback.repeatStart() < 0)
            repeatButton.setText(R.string.repeat_off);
        else if (playback.repeatEnd() < 0)
            repeatButton.setText(getString(R.string.repeat_a, time(playback.repeatStart())));
        else repeatButton.setText(getString(R.string.repeat_ab,
                time(playback.repeatStart()), time(playback.repeatEnd())));
        long remaining = playback == null ? 0 : playback.sleepRemaining();
        sleepButton.setText(playback != null && playback.isChapterStopSet()
                ? "Sleep · " + playback.chapterStopLabel()
                : remaining == 0 ? "Sleep timer"
                : "Sleep · " + Math.max(1, (remaining + 59999) / 60000) + " min");
        refreshMiniPlayer();
        if (drivingDialog != null && drivingDialog.isShowing()) {
            drivingTitle.setText(selected == null ? "No book selected" : bookTitle(selected));
            drivingPlay.setText(playback != null && playback.isPlaying() ? "Pause" : "Play");
        }
        handler.removeCallbacks(tick);
        if (currentReady && playback.isPlaying()) handler.postDelayed(tick, 1000);
        // Rebinding the grid during playback interrupts long-press editing and focus.
        // Progress is refreshed on entering the library and after playback stops.
        if (!showingPlayer && search != null && (playback == null || !playback.isPlaying())) {
            syncSavedProgress();
            renderLibrary();
        }
    }

    private void refreshMiniPlayer() {
        if (miniPlayer == null || miniTitle == null || miniPlay == null) return;
        JSONObject selected = find(currentUri);
        boolean limitedLandscape = getResources().getConfiguration().screenHeightDp < 500
                && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean visible = selected != null && !showingPlayer && !limitedLandscape;
        miniPlayer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        miniTitle.setText(bookTitle(selected));
        boolean ready = playback != null && playback.isReady()
                && currentUri.equals(playback.currentUri());
        miniPlay.setEnabled(ready);
        miniPlay.setText(ready && playback.isPlaying() ? "Pause" : "Play");
        int position = ready ? playback.position() : selected.optInt("position");
        int duration = ready ? playback.duration() : selected.optInt("duration");
        miniProgress.setText((playback != null && playback.isPlaying() ? "Playing · " : "Resume · ")
                + time(position) + " / " + time(duration));
        miniPlayer.setContentDescription("Current book: " + bookTitle(selected)
                + ", " + miniProgress.getText());
    }

    private void showDrivingControls() {
        if (drivingDialog != null && drivingDialog.isShowing()) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        drivingTitle = text("", 22);
        drivingTitle.setGravity(Gravity.CENTER);
        drivingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(drivingTitle);
        drivingPlay = button("Play");
        drivingPlay.setTextSize(24);
        drivingPlay.setBackground(control(PLUM, PLUM, PAPER));
        drivingPlay.setTextColor(ON_ACTION);
        drivingPlay.setOnClickListener(v -> toggle());
        panel.addView(drivingPlay, new LinearLayout.LayoutParams(-1, dp(88)));
        LinearLayout skips = new LinearLayout(this);
        Button back = button("Back " + backMs / 1000 + " seconds");
        back.setOnClickListener(v -> skip(-backMs));
        skips.addView(back, weightedControl());
        Button forward = button("Forward " + forwardMs / 1000 + " seconds");
        forward.setOnClickListener(v -> skip(forwardMs));
        skips.addView(forward, weightedControl());
        panel.addView(skips, new LinearLayout.LayoutParams(-1, dp(72)));
        drivingDialog = new AlertDialog.Builder(this).setTitle("Large driving controls")
                .setView(panel).setNegativeButton("Close", null).create();
        drivingDialog.setOnDismissListener(dialog -> {
            drivingDialog = null;
            drivingPlay = null;
            drivingTitle = null;
        });
        drivingDialog.show();
        JSONObject selected = find(currentUri);
        drivingTitle.setText(selected == null ? "No book selected" : bookTitle(selected));
        drivingPlay.setText(playback != null && playback.isPlaying() ? "Pause" : "Play");
    }

    private void updatePlayerCover(JSONObject selected) {
        if (playerCoverHolder == null) return;
        String key = selected == null ? "" : selected.optString("uri") + "|"
                + selected.optString("coverUri") + "|"
                + selected.optString("folderCoverUri") + "|" + bookTitle(selected);
        if (key.equals(playerCoverUri)) return;
        playerCoverUri = key;
        String artUri = selected == null ? "" : selected.optString("coverUri", "").isEmpty()
                ? selected.optString("uri") : selected.optString("coverUri");
        updatePlayerAccent(artwork.get(artUri));
        playerCoverHolder.removeAllViews();
        if (selected == null) {
            TextView fallback = text("ARIOSO", 32);
            fallback.setTypeface(null, android.graphics.Typeface.BOLD);
            fallback.setTextColor(ON_ACTION);
            fallback.setGravity(Gravity.CENTER);
            playerCoverHolder.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        } else playerCoverHolder.addView(coverView(selected), new FrameLayout.LayoutParams(-1, -1));
    }

    private void updatePlayerAccent(Bitmap bitmap) {
        if (playerAccent == null) return;
        int accent = PLUM;
        if (bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            long red = 0, green = 0, blue = 0;
            int count = 0;
            for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
                int pixel = bitmap.getPixel(Math.min(bitmap.getWidth() - 1,
                        x * bitmap.getWidth() / 6), Math.min(bitmap.getHeight() - 1,
                        y * bitmap.getHeight() / 6));
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
                count++;
            }
            accent = blend(PLUM, Color.rgb((int) (red / count),
                    (int) (green / count), (int) (blue / count)), .65f);
        }
        GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{PLUM, accent});
        bar.setCornerRadius(dp(5));
        playerAccent.setBackground(bar);
    }

    private void renderChapterProgress(int position, int duration) {
        if (chapterProgress == null) return;
        List<Mp4Chapters.Chapter> chapters = chaptersFor(find(currentUri), duration);
        int currentIndex = chapters == null ? -1 : ChapterNavigation.currentIndex(chapters, position);
        timelineStart = chapterTimeline && currentIndex >= 0
                ? chapters.get(currentIndex).positionMs : 0;
        timelineEnd = chapterTimeline && currentIndex >= 0
                ? currentIndex + 1 < chapters.size()
                ? chapters.get(currentIndex + 1).positionMs : duration : duration;
        if (timelineEnd <= timelineStart) { timelineStart = 0; timelineEnd = duration; }
        int range = Math.max(1, timelineEnd - timelineStart);
        if (seek.getMax() != range) seek.setMax(range);
        if (!dragging) seek.setProgress(Math.max(0, Math.min(range, position - timelineStart)));
        List<Integer> chapterPoints = new ArrayList<>();
        if (chapters != null) for (int i = 0; i < Math.min(256, chapters.size()); i++) {
            int at = chapters.get(i).positionMs;
            if (at >= timelineStart && at <= timelineEnd) chapterPoints.add(at - timelineStart);
        }
        List<Integer> bookmarkPoints = new ArrayList<>();
        JSONObject current = find(currentUri);
        JSONArray savedMarks = current == null ? null : current.optJSONArray("bookmarks");
        if (savedMarks != null) for (int i = 0; i < Math.min(100, savedMarks.length()); i++) {
            JSONObject mark = savedMarks.optJSONObject(i);
            if (mark != null && mark.optInt("position") >= timelineStart
                    && mark.optInt("position") <= timelineEnd)
                bookmarkPoints.add(mark.optInt("position") - timelineStart);
        }
        if (timelineMarkers != null) timelineMarkers.setMarkers(range, chapterPoints, bookmarkPoints);
        if (chapters == null || chapters.isEmpty() || duration <= 0) {
            chapterProgress.setVisibility(View.GONE); return;
        }
        int index = ChapterNavigation.currentIndex(chapters, position);
        if (index < 0) {
            chapterProgress.setVisibility(View.GONE); return;
        }
        int end = index + 1 < chapters.size() ? chapters.get(index + 1).positionMs : duration;
        chapterProgress.setText(getString(R.string.chapter_progress, index + 1,
                chapters.size(), time(Math.max(0, end - position))));
        chapterProgress.setVisibility(View.VISIBLE);
    }

    private void toggle() {
        if (playback == null || !playback.isReady()) return;
        playback.toggle();
        refreshPlayerUi();
    }

    private void toggleVoiceBoost() {
        JSONObject item = find(currentUri);
        if (item == null) { message("Choose a book first"); return; }
        boolean previous = item.optBoolean("voiceBoost", false);
        try {
            item.put("voiceBoost", !previous);
            if (!saveLibraryNow(false)) {
                item.put("voiceBoost", previous);
                message("Could not save voice boost"); return;
            }
            if (playback != null) playback.setVoiceBoost(!previous);
            refreshPlayerUi();
        } catch (JSONException e) { message("Could not save voice boost"); }
    }

    private void toggleSkipSilence() {
        JSONObject item = find(currentUri);
        if (item == null) { message("Choose a book first"); return; }
        boolean previous = item.optBoolean("skipSilence", false);
        try {
            item.put("skipSilence", !previous);
            if (!saveLibraryNow(false)) {
                item.put("skipSilence", previous);
                message("Could not save skip silence"); return;
            }
            if (playback != null) playback.setSkipSilence(!previous);
            refreshPlayerUi();
        } catch (JSONException e) { message("Could not save skip silence"); }
    }

    private void openCompanionPdf() {
        JSONObject item = find(currentUri);
        if (item == null) { message("Choose a book first"); return; }
        String saved = item.optString("companionPdfUri", "");
        if (saved.isEmpty()) { pickCompanionPdf(item); return; }
        new AlertDialog.Builder(this).setTitle("Companion PDF")
                .setItems(new String[]{"Open local PDF", "Choose a different PDF", "Unlink PDF"},
                        (dialog, which) -> {
                            if (which == 0) showCompanionPdf(saved);
                            else if (which == 1) pickCompanionPdf(item);
                            else saveCompanionPdf(item, "");
                        }).setNegativeButton("Cancel", null).show();
    }

    private void pickCompanionPdf(JSONObject item) {
        companionBookUri = item.optString("uri");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_COMPANION_PDF);
    }

    private void finishCompanionPdf(String bookUri, Uri document) {
        JSONObject item = find(bookUri);
        if (item == null || !LocalDocumentPolicy.accepts(document.getScheme(),
                document.getAuthority())) { message("Choose a PDF stored on this device"); return; }
        try {
            getContentResolver().takePersistableUriPermission(document,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            saveCompanionPdf(item, document.toString());
        } catch (SecurityException | IllegalArgumentException e) {
            message("Could not keep access to this PDF");
        }
    }

    private void saveCompanionPdf(JSONObject item, String uri) {
        String previous = item.optString("companionPdfUri", "");
        try {
            if (uri.isEmpty()) item.remove("companionPdfUri");
            else item.put("companionPdfUri", uri);
            if (!saveLibraryNow(false)) {
                if (previous.isEmpty()) item.remove("companionPdfUri");
                else item.put("companionPdfUri", previous);
                message("Could not save PDF link");
            } else refreshPlayerUi();
        } catch (JSONException e) { message("Could not save PDF link"); }
    }

    private void showCompanionPdf(String saved) {
        Uri uri = Uri.parse(saved);
        if (!LocalDocumentPolicy.accepts(uri.getScheme(), uri.getAuthority())) {
            message("This PDF is not on-device"); return;
        }
        try {
            ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) { message("PDF is unavailable; choose it again"); return; }
            long bytes = descriptor.getStatSize();
            if (bytes > 100L * 1024 * 1024) {
                descriptor.close(); message("PDF exceeds the 100 MB viewer limit"); return;
            }
            PdfRenderer renderer;
            try { renderer = new PdfRenderer(descriptor); }
            catch (Exception e) { descriptor.close(); throw e; }
            if (renderer.getPageCount() < 1 || renderer.getPageCount() > 500) {
                renderer.close(); descriptor.close();
                message("PDF needs between 1 and 500 pages"); return;
            }
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            ImageView pageImage = new ImageView(this);
            pageImage.setAdjustViewBounds(true);
            panel.addView(pageImage, new LinearLayout.LayoutParams(-1, -2));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(panel);
            int[] page = {0};
            Bitmap[] rendered = {null};
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Companion PDF · 1 / " + renderer.getPageCount())
                    .setView(scroll)
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Previous", null)
                    .setPositiveButton("Next", null).create();
            dialog.setOnDismissListener(ignored -> {
                pageImage.setImageDrawable(null);
                if (rendered[0] != null) rendered[0].recycle();
                renderer.close();
                try { descriptor.close(); } catch (IOException ignoredClose) { }
            });
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (page[0] > 0) renderPdfPage(renderer, pageImage, rendered, --page[0], dialog);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (page[0] + 1 < renderer.getPageCount())
                    renderPdfPage(renderer, pageImage, rendered, ++page[0], dialog);
            });
            renderPdfPage(renderer, pageImage, rendered, 0, dialog);
        } catch (Exception e) { message("PDF could not be opened; choose a local PDF"); }
    }

    private void renderPdfPage(PdfRenderer renderer, ImageView image,
                               Bitmap[] previous, int index, AlertDialog dialog) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int width = Math.min(1200, Math.max(480, getResources().getDisplayMetrics().widthPixels - dp(32)));
            int height = Math.max(1, Math.min(1800,
                    Math.round(width * page.getHeight() / (float) page.getWidth())));
            Bitmap next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            next.eraseColor(Color.WHITE);
            page.render(next, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            image.setImageBitmap(next);
            if (previous[0] != null) previous[0].recycle();
            previous[0] = next;
            dialog.setTitle("Companion PDF · " + (index + 1) + " / " + renderer.getPageCount());
        } catch (RuntimeException e) { message("This PDF page could not be displayed"); }
    }

    private void skip(int offset) {
        if (playback != null && playback.isReady()) playback.skip(offset);
    }

    private void seekToAndSave(int position) {
        if (playback != null && playback.isReady()) playback.seekTo(position);
    }

    private void showRecentPlaces() {
        if (playback == null || !playback.isReady()) {
            message("Choose a playable book first"); return;
        }
        List<Integer> places = playback.recentPlaces();
        if (places.isEmpty()) { message("No recent jumps in this book"); return; }
        String[] labels = new String[places.size()];
        for (int i = 0; i < places.size(); i++) labels[i] = "Return to " + time(places.get(i));
        String source = currentUri;
        JSONObject current = find(source);
        if (current == null) { message("This book is no longer listed"); return; }
        new AlertDialog.Builder(this).setTitle("Recent places · " + bookTitle(current))
                .setItems(labels, (dialog, which) -> {
                    if (playback == null || !playback.isReady()
                            || !source.equals(playback.currentUri())) return;
                    seekToAndSave(places.get(which));
                    refreshPlayerUi();
                })
                .setNeutralButton("Clear", (dialog, which) -> playback.clearRecentPlaces())
                .setNegativeButton("Close", null).show();
    }

    private void promptSeekTime() {
        if (playback == null || !playback.isReady()) {
            message("Choose a playable book first"); return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_DATETIME);
        input.setHint("mm:ss or hh:mm:ss");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        String targetBook = currentUri;
        new AlertDialog.Builder(this).setTitle("Go to time")
                .setMessage("Enter a time within this book.")
                .setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Go", (dialog, which) -> {
                    long target = ListeningTools.parseTimeMillis(input.getText().toString().trim());
                    if (target < 0 || playback == null || !playback.isReady()
                            || !targetBook.equals(playback.currentUri())
                            || target > playback.duration()) {
                        message("Enter a valid time within this book"); return;
                    }
                    seekToAndSave((int) target);
                    refreshPlayerUi();
                }).show();
    }

    private void showRepeatControls() {
        if (playback == null || !playback.isReady()) {
            message("Choose a playable book first"); return;
        }
        String state = playback.repeatStart() < 0 ? "No repeat points set"
                : playback.repeatEnd() < 0 ? "A at " + time(playback.repeatStart())
                : "Repeating " + time(playback.repeatStart()) + " to " + time(playback.repeatEnd());
        new AlertDialog.Builder(this).setTitle("A–B repeat · " + state)
                .setItems(new String[]{"Mark A at current time", "Mark B at current time",
                        "Clear repeat"}, (dialog, which) -> {
                    if (which == 0 && !playback.markRepeatStart())
                        message("Choose A at least four seconds before the end");
                    else if (which == 1 && !playback.markRepeatEnd())
                        message("Choose B at least two seconds after A and before the file end");
                    else if (which == 2) playback.clearRepeat();
                    refreshPlayerUi();
                }).setNegativeButton("Cancel", null).show();
    }

    private void applySpeed() {
        if (playback != null) playback.setSpeed(speedValues[speed.getSelectedItemPosition()]);
    }

    private void choosePitch() {
        JSONObject item = find(currentUri);
        if (item == null) { message("Choose a book first"); return; }
        String[] labels = {"Lower · 0.9×", "Original · 1.0×", "Higher · 1.1×"};
        float[] values = {.9f, 1f, 1.1f};
        new AlertDialog.Builder(this).setTitle("Voice pitch for " + bookTitle(item))
                .setItems(labels, (dialog, which) -> {
                    double previous = item.optDouble("playbackPitch", 1.0);
                    try {
                        item.put("playbackPitch", values[which]);
                        if (!saveLibraryNow(false)) {
                            item.put("playbackPitch", previous);
                            message("Could not save voice pitch");
                        } else if (playback != null) playback.setPitch(values[which]);
                    } catch (JSONException ignored) { message("Could not save voice pitch"); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void showBookmarks() { showBookmarks(""); }

    private void showAllBookmarks(String query) {
        List<JSONObject> owners = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
        for (JSONObject book : books) {
            JSONArray marks = book.optJSONArray("bookmarks");
            if (marks == null) continue;
            for (int i = 0; i < Math.min(marks.length(), ListeningTools.MAX_BOOKMARKS); i++) {
                JSONObject mark = marks.optJSONObject(i);
                if (mark == null) continue;
                String label = metadata(mark, "label", "Bookmark");
                if (!bookTitle(book).toLowerCase(Locale.ROOT).contains(needle)
                        && !label.toLowerCase(Locale.ROOT).contains(needle)) continue;
                owners.add(book);
                positions.add(Math.max(0, mark.optInt("position")));
                labels.add(bookTitle(book) + " · " + time(mark.optInt("position")) + " · " + label);
                if (owners.size() >= 100) break;
            }
            if (owners.size() >= 100) break;
        }
        String[] entries = new String[labels.size() + 1];
        entries[0] = query.isEmpty() ? "Search all bookmarks…" : "Search: " + query;
        for (int i = 0; i < labels.size(); i++) entries[i + 1] = labels.get(i);
        new AlertDialog.Builder(this).setTitle("All bookmarks · " + labels.size()
                        + (labels.size() == 100 ? " shown" : ""))
                .setItems(entries, (dialog, which) -> {
                    if (which == 0) {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
                        input.setText(query);
                        new AlertDialog.Builder(this).setTitle("Search all bookmarks")
                                .setView(input).setNegativeButton("Cancel", null)
                                .setPositiveButton("Search", (d, w) ->
                                        showAllBookmarks(input.getText().toString().trim()))
                                .show();
                    } else jumpToBookPosition(owners.get(which - 1), positions.get(which - 1));
                }).setNegativeButton("Close", null).show();
    }

    private void jumpToBookPosition(JSONObject book, int position) {
        if (sourceChanged(book)) {
            new AlertDialog.Builder(this).setTitle("This audio file changed")
                    .setMessage("Check this file before opening a saved bookmark.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open anyway", (dialog, which) -> jumpToVerifiedBook(book, position))
                    .show();
        } else jumpToVerifiedBook(book, position);
    }

    private void jumpToVerifiedBook(JSONObject book, int position) {
        if (!isOnDeviceUri(book.optString("uri"))) {
            message("This book needs a local file"); return;
        }
        pendingJumpUri = book.optString("uri");
        pendingJumpPosition = position;
        openVerified(book);
        showPlayer(true);
        refreshPlayerUi();
    }

    private void handleReaderSyncContinue(Intent intent) {
        if (intent == null || !"app.petallibrary.CONTINUE".equals(intent.getAction())) return;
        if (!"app.petallibrary".equals(getCallingPackage())
                || !AriosoPositionProvider.certificateMatches(getPackageManager())) {
            message("A trusted Reader Sync launch is required");
            return;
        }
        String uri = intent.getStringExtra("bookUri");
        if (uri == null || uri.isEmpty() || uri.length() > 4096) return;
        JSONObject book = find(uri);
        if (book == null) { message("That audiobook is no longer in Arioso"); return; }
        if (!intent.hasExtra("suggestedProgress")) {
            open(book);
            showPlayer(true);
            return;
        }
        double progress = intent.getDoubleExtra("suggestedProgress", Double.NaN);
        long createdAt = intent.getLongExtra("suggestionCreatedAt", -1);
        long expectedPosition = intent.getLongExtra("expectedPositionMs", -1);
        long expectedDuration = intent.getLongExtra("expectedDurationMs", -1);
        int suggested = PositionSuggestion.target(progress, expectedDuration);
        if (!PositionSuggestion.fresh(System.currentTimeMillis(), createdAt)
                || suggested < 0 || !PositionSuggestion.unchanged(expectedPosition,
                expectedDuration, expectedPosition, expectedDuration)) {
            message("Reader Sync suggestion is invalid; saved place unchanged");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Continue listening here?")
                .setMessage("“" + bookTitle(book) + "”\n\nArioso's saved place: "
                        + time((int) expectedPosition) + "\nReader suggests about "
                        + time(suggested) + ".\n\nThis whole-book estimate may be off when editions differ. Arioso will check its place again before changing it.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Keep Arioso place", (dialog, which) -> {
                    open(book);
                    showPlayer(true);
                })
                .setPositiveButton("Use suggested place", (dialog, which) -> {
                    if (!PositionSuggestion.fresh(System.currentTimeMillis(), createdAt)) {
                        message("Reader Sync suggestion expired. Review both places again.");
                        return;
                    }
                    applyReaderSyncSuggestion(uri, expectedPosition, expectedDuration, suggested);
                })
                .show();
    }

    private JSONObject freshBook(String uri) {
        try {
            JSONArray rows = LibraryStore.get(this).readAll();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && uri.equals(row.optString("uri"))) return row;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void applyReaderSyncSuggestion(String uri, long expectedPosition,
                                        long expectedDuration, int suggested) {
        JSONObject fresh = freshBook(uri);
        if (fresh == null || !PositionSuggestion.unchanged(fresh.optInt("position", -1),
                fresh.optInt("duration", -1), expectedPosition, expectedDuration)
                || sourceChanged(fresh)) {
            message("Arioso's book or place changed. Review both places again in Reader Sync.");
            return;
        }
        if (playback != null && uri.equals(playback.currentUri())) {
            if (playback.isPlaying() || playback.isReady()
                    && playback.position() != expectedPosition) {
                message("Pause playback and review the current place again in Reader Sync.");
                return;
            }
            if (playback.isReady()) {
                playback.seekTo(suggested);
                showPlayer(true);
            } else jumpToVerifiedBook(fresh, suggested);
        } else jumpToVerifiedBook(fresh, suggested);
        new AlertDialog.Builder(this).setTitle("Place suggested in Arioso")
                .setMessage("Your previous Arioso place was " + time((int) expectedPosition)
                        + ". Undo is available while this book's place stays unchanged.")
                .setNegativeButton("Keep new place", null)
                .setPositiveButton("Undo", (dialog, which) -> {
                    JSONObject latest = freshBook(uri);
                    boolean queued = uri.equals(pendingJumpUri) && pendingJumpPosition == suggested
                            && latest != null && latest.optInt("position", -1) == expectedPosition;
                    boolean atSuggested = playback != null && uri.equals(playback.currentUri())
                            && playback.isReady() && !playback.isPlaying()
                            && Math.abs(playback.position() - suggested) <= 1000
                            && latest != null && latest.optInt("position", -1) == suggested;
                    if (latest == null || sourceChanged(latest)
                            || !queued && !atSuggested) {
                        message("The place changed again. Undo was not applied.");
                        return;
                    }
                    jumpToVerifiedBook(latest, (int) expectedPosition);
                }).show();
    }

    private void showBookmarks(String query) {
        JSONObject item = find(currentUri);
        if (item == null || playback == null || !playback.isReady()) {
            message("Choose a playable book first");
            return;
        }
        JSONArray saved = item.optJSONArray("bookmarks");
        int count = saved == null ? 0 : Math.min(saved.length(), ListeningTools.MAX_BOOKMARKS);
        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JSONObject mark = saved.optJSONObject(i);
            if (mark != null && metadata(mark, "label", "Bookmark").toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT))) matching.add(i);
        }
        String[] entries = new String[matching.size() + 3];
        entries[0] = "Search bookmark notes…";
        entries[1] = "Add bookmark at current position";
        entries[2] = "Export this book's bookmarks…";
        for (int i = 0; i < matching.size(); i++) {
            JSONObject mark = saved.optJSONObject(matching.get(i));
            entries[i + 3] = time(mark.optInt("position")) + " · " + metadata(mark, "label", "Bookmark");
        }
        new AlertDialog.Builder(this)
                .setTitle("Bookmarks · " + bookTitle(item) + (query.isEmpty() ? "" : " · " + matching.size() + " matches"))
                .setItems(entries, (dialog, which) -> {
                    if (which == 0) {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
                        input.setText(query);
                        new AlertDialog.Builder(this).setTitle("Search bookmark notes")
                                .setView(input).setNegativeButton("Cancel", null)
                                .setPositiveButton("Search", (d, w) -> showBookmarks(input.getText().toString().trim()))
                                .show();
                    } else if (which == 1) addBookmark(item);
                    else if (which == 2) pickBookmarkExport(item);
                    else {
                        int index = matching.get(which - 3);
                        JSONObject mark = saved.optJSONObject(index);
                        if (mark != null) bookmarkAction(item, index, mark);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void pickBookmarkExport(JSONObject item) {
        bookmarkExportBookUri = item.optString("uri");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/csv");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_TITLE, "arioso-bookmarks.csv");
        startActivityForResult(intent, PICK_BOOKMARK_EXPORT);
    }

    private void exportBookmarks(String bookUri, Uri destination) {
        JSONObject item = find(bookUri);
        if (item == null) { message("That book is no longer in Arioso"); return; }
        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append(BookmarkCsv.row("book_title", "position_ms", "time", "note"));
        JSONArray marks = item.optJSONArray("bookmarks");
        if (marks != null) for (int i = 0; i < Math.min(marks.length(), ListeningTools.MAX_BOOKMARKS); i++) {
            JSONObject mark = marks.optJSONObject(i);
            if (mark == null) continue;
            int position = Math.max(0, mark.optInt("position"));
            csv.append(BookmarkCsv.row(bookTitle(item), Integer.toString(position),
                    time(position), metadata(mark, "label", "")));
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        Context appContext = getApplicationContext();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        artworkWorker.execute(() -> {
            int result = 0;
            boolean wrote = false;
            try (OutputStream output = appContext.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("No output stream");
                output.write(bytes);
                output.flush();
                wrote = true;
            } catch (IOException | RuntimeException ignored) { }
            if (wrote) result = verifyWrittenBackup(appContext, destination, bytes);
            Arrays.fill(bytes, (byte) 0);
            final int outcome = result;
            MAIN.post(() -> {
                MainActivity activity = owner.get();
                if (activity == null || activity.destroyed) return;
                if (outcome == 2) activity.message("Bookmark CSV saved and verified");
                else activity.showIncompleteBackup(destination, outcome == 3);
            });
        });
    }

    private void queueBook(JSONObject item) {
        String uri = item.optString("uri");
        if (!isOnDeviceUri(uri)) { message("Choose an on-device book"); return; }
        try {
            JSONArray existing = new JSONArray(prefs.getString("queue_uris", "[]"));
            if (existing.length() >= 100) { message("The queue holds 100 books"); return; }
            for (int i = 0; i < existing.length(); i++) if (uri.equals(existing.optString(i))) {
                message("This book is already queued"); return;
            }
            existing.put(uri);
            if (prefs.edit().putString("queue_uris", existing.toString()).commit())
                message("Added to listen-next queue");
            else message("Could not save the queue");
        } catch (JSONException e) { message("Could not read the queue"); }
    }

    private void showQueue() {
        JSONArray stored;
        try { stored = new JSONArray(prefs.getString("queue_uris", "[]")); }
        catch (JSONException e) { message("The queue is unavailable"); return; }
        List<String> uris = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(100, stored.length()); i++) {
            String uri = stored.optString(i);
            JSONObject item = find(uri);
            if (item == null) continue;
            uris.add(uri);
            labels.add(bookTitle(item));
        }
        labels.add(0, "Save queue as a preset");
        labels.add(0, "Load saved queue");
        labels.add(0, "Clear queue");
        new AlertDialog.Builder(this).setTitle("Listen next")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) new AlertDialog.Builder(this).setTitle("Clear the queue?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Clear", (confirm, button) -> {
                                if (!prefs.edit().putString("queue_uris", "[]").commit())
                                    message("Could not clear the queue");
                            }).show();
                    else if (which == 1) loadQueuePreset();
                    else if (which == 2) saveQueuePreset();
                    else {
                        String chosen = uris.get(which - 3);
                        JSONObject item = find(chosen);
                        if (item == null) return;
                        new AlertDialog.Builder(this).setTitle(bookTitle(item))
                                .setItems(new String[]{"Play now", "Move earlier", "Move later", "Remove from queue"},
                                        (actionDialog, action) -> {
                                            if (action == 1 || action == 2) {
                                                moveQueuedUri(chosen, action == 1 ? -1 : 1);
                                            } else {
                                                if (!removeQueuedUri(chosen)) return;
                                                if (action == 0) { open(item); showPlayer(true); }
                                            }
                                        }).show();
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void moveQueuedUri(String chosen, int direction) {
        try {
            JSONArray stored = new JSONArray(prefs.getString("queue_uris", "[]"));
            List<String> uris = new ArrayList<>();
            for (int i = 0; i < Math.min(100, stored.length()); i++) uris.add(stored.optString(i));
            int at = uris.indexOf(chosen);
            int to = at + direction;
            if (at < 0 || to < 0 || to >= uris.size()) {
                message("That book cannot move farther"); return;
            }
            Collections.swap(uris, at, to);
            JSONArray reordered = new JSONArray();
            for (String uri : uris) reordered.put(uri);
            if (!prefs.edit().putString("queue_uris", reordered.toString()).commit())
                message("Could not reorder the queue");
            else showQueue();
        } catch (JSONException e) { message("Could not reorder the queue"); }
    }

    private void saveQueuePreset() {
        String queue = prefs.getString("queue_uris", "[]");
        if ("[]".equals(queue)) { message("Add books to the queue first"); return; }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Preset name");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        new AlertDialog.Builder(this).setTitle("Save listen-next queue")
                .setView(name).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String label = name.getText().toString().trim();
                    if (label.isEmpty()) { message("Enter a preset name"); return; }
                    try {
                        JSONObject presets = new JSONObject(prefs.getString("queue_presets", "{}"));
                        if (!presets.has(label) && presets.length() >= 10) {
                            message("Limit of 10 saved queues reached"); return;
                        }
                        presets.put(label, new JSONArray(queue));
                        if (!prefs.edit().putString("queue_presets", presets.toString()).commit())
                            message("Could not save the queue preset");
                        else message("Queue preset saved");
                    } catch (JSONException e) { message("Could not save the queue preset"); }
                }).show();
    }

    private void loadQueuePreset() {
        final JSONObject presets;
        try { presets = new JSONObject(prefs.getString("queue_presets", "{}")); }
        catch (JSONException e) { message("Saved queues are unavailable"); return; }
        List<String> names = new ArrayList<>();
        Iterator<String> keys = presets.keys();
        while (keys.hasNext() && names.size() < 10) names.add(keys.next());
        if (names.isEmpty()) { message("No saved queues yet"); return; }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        new AlertDialog.Builder(this).setTitle("Saved listen-next queues")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String label = names.get(which);
                    new AlertDialog.Builder(this).setTitle(label)
                            .setItems(new String[]{"Load queue", "Rename preset", "Delete preset"}, (d, action) -> {
                                if (action == 0) {
                                    JSONArray source = presets.optJSONArray(label);
                                    JSONArray valid = new JSONArray();
                                    if (source != null) for (int i = 0; i < Math.min(100, source.length()); i++) {
                                        String uri = source.optString(i);
                                        if (find(uri) != null) valid.put(uri);
                                    }
                                    if (!prefs.edit().putString("queue_uris", valid.toString()).commit())
                                        message("Could not load the saved queue");
                                    else message("Saved queue loaded");
                                } else if (action == 1) {
                                    renameQueuePreset(presets, label);
                                } else {
                                    presets.remove(label);
                                    if (!prefs.edit().putString("queue_presets", presets.toString()).commit())
                                        message("Could not delete the preset");
                                }
                            }).show();
                }).setNegativeButton("Close", null).show();
    }

    private void renameQueuePreset(JSONObject presets, String oldName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(oldName);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        new AlertDialog.Builder(this).setTitle("Rename queue preset")
                .setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String nextName = input.getText().toString().trim();
                    if (nextName.isEmpty()) { message("Enter a preset name"); return; }
                    if (!nextName.equals(oldName) && presets.has(nextName)) {
                        message("A preset already uses that name"); return;
                    }
                    JSONArray queue = presets.optJSONArray(oldName);
                    if (queue == null) { message("That preset is unavailable"); return; }
                    try {
                        JSONObject renamed = new JSONObject(presets.toString());
                        renamed.remove(oldName);
                        renamed.put(nextName, queue);
                        if (!prefs.edit().putString("queue_presets", renamed.toString()).commit())
                            message("Could not rename the preset");
                        else message("Queue preset renamed");
                    } catch (JSONException e) { message("Could not rename the preset"); }
                }).show();
    }

    private boolean removeQueuedUri(String chosen) {
        try {
            JSONArray existing = new JSONArray(prefs.getString("queue_uris", "[]"));
            JSONArray updated = new JSONArray();
            for (int i = 0; i < existing.length(); i++)
                if (!chosen.equals(existing.optString(i))) updated.put(existing.optString(i));
            if (prefs.edit().putString("queue_uris", updated.toString()).commit()) return true;
        } catch (JSONException ignored) { }
        message("Could not update the queue");
        return false;
    }

    private void showChapters() {
        JSONObject item = find(currentUri);
        if (item == null || playback == null || !playback.isReady()) {
            message("Choose a playable book first");
            return;
        }
        if (!chapterCache.containsKey(item.optString("uri"))) scanChapters(item);
        showChapterChoices(item, chaptersFor(item, playback.duration()), "");
    }

    private List<Mp4Chapters.Chapter> chaptersFor(JSONObject item, int durationMs) {
        if (item == null) return Collections.emptyList();
        String uri = item.optString("uri");
        List<Mp4Chapters.Chapter> embedded = chapterCache.get(uri);
        JSONArray manual = item.optJSONArray("manualChapters");
        CombinedChapters cached = combinedChapterCache.get(uri);
        if (cached != null && cached.book == item && cached.manual == manual
                && cached.embedded == embedded && cached.duration == durationMs) return cached.chapters;
        List<Mp4Chapters.Chapter> combined = ManualChapters.merge(embedded,
                manualChapterList(item, durationMs), durationMs);
        combinedChapterCache.put(uri, new CombinedChapters(item, manual, embedded, durationMs, combined));
        return combined;
    }

    private List<Mp4Chapters.Chapter> manualChapterList(JSONObject item, int durationMs) {
        List<Mp4Chapters.Chapter> result = new ArrayList<>();
        if (item == null) return result;
        JSONArray saved = item.optJSONArray("manualChapters");
        if (saved == null) return result;
        for (int i = 0; i < Math.min(saved.length(), ManualChapters.MAX_MARKERS); i++) {
            JSONObject marker = saved.optJSONObject(i);
            if (marker == null) continue;
            Mp4Chapters.Chapter chapter = new Mp4Chapters.Chapter(
                    marker.optInt("positionMs", -1), marker.optString("title", ""));
            if (ManualChapters.valid(chapter, durationMs)) result.add(chapter);
        }
        return result;
    }

    private void showChapterChoices(JSONObject item, List<Mp4Chapters.Chapter> chapters, String query) {
        List<Mp4Chapters.Chapter> matches = new ArrayList<>();
        for (Mp4Chapters.Chapter chapter : chapters)
            if (chapterLabel(item, chapter).toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT)))
                matches.add(chapter);
        boolean hasManual = !manualChapterList(item, playback.duration()).isEmpty();
        int firstChapter = hasManual ? 4 : 3;
        String[] entries = new String[matches.size() + firstChapter];
        entries[0] = "Add marker at current position";
        entries[1] = query.isEmpty() ? "Search chapter titles…" : "Change search: " + query;
        entries[2] = "Correct chapter labels locally…";
        if (hasManual) entries[3] = "Manage manual markers…";
        for (int i = 0; i < matches.size(); i++) {
            Mp4Chapters.Chapter chapter = matches.get(i);
            entries[i + firstChapter] = time(chapter.positionMs) + " · " + chapterLabel(item, chapter);
        }
        new AlertDialog.Builder(this)
                .setTitle("Chapters · " + bookTitle(item)
                        + (query.isEmpty() ? "" : " · " + matches.size() + " matches"))
                .setItems(entries, (dialog, which) -> {
                    if (which == 0) promptAddManualChapter(item);
                    else if (which == 1) {
                        if (chapters.isEmpty()) message("Add a marker or wait for the file scan");
                        else promptChapterSearch(item, chapters);
                    } else if (which == 2) {
                        if (chapters.isEmpty()) message("No chapter labels to correct yet");
                        else chooseChapterLabel(item, chapters);
                    } else if (hasManual && which == 3) manageManualChapters(item);
                    else seekToAndSave(matches.get(which - firstChapter).positionMs);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private String chapterLabel(JSONObject item, Mp4Chapters.Chapter chapter) {
        if (manualMarkerIndex(item, chapter.positionMs) >= 0) return chapter.title;
        JSONObject labels = item.optJSONObject("chapterLabels");
        String custom = labels == null ? "" : labels.optString(Integer.toString(chapter.positionMs));
        return custom.isEmpty() ? chapter.title : custom;
    }

    private int manualMarkerIndex(JSONObject item, int positionMs) {
        JSONArray saved = item.optJSONArray("manualChapters");
        if (saved == null) return -1;
        for (int i = 0; i < Math.min(saved.length(), ManualChapters.MAX_MARKERS); i++) {
            JSONObject marker = saved.optJSONObject(i);
            if (marker != null && marker.optInt("positionMs", -1) == positionMs) return i;
        }
        return -1;
    }

    private void promptAddManualChapter(JSONObject item) {
        if (playback == null || !playback.isReady() || playback.duration() <= 0) {
            message("Playback duration is needed to add a chapter marker"); return;
        }
        int position = playback.position();
        if (position >= playback.duration()) { message("Seek before the end of the book"); return; }
        if (manualMarkerIndex(item, position) >= 0) {
            message("A manual marker already exists at this position"); return;
        }
        JSONArray existing = item.optJSONArray("manualChapters");
        if (existing != null && existing.length() >= ManualChapters.MAX_MARKERS) {
            message("This book already has 200 manual markers"); return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        input.setText(getString(R.string.manual_chapter_default, time(position)));
        new AlertDialog.Builder(this).setTitle("Add chapter at " + time(position))
                .setMessage("The marker stays in Arioso's library; the audio file is unchanged.")
                .setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    String label = input.getText().toString().trim();
                    if (label.isEmpty()) { message("Enter a chapter name"); return; }
                    try {
                        JSONArray next = existing == null ? new JSONArray() : new JSONArray(existing.toString());
                        next.put(new JSONObject().put("positionMs", position).put("title", label));
                        saveManualChapterList(item, next, "Chapter marker added");
                    } catch (JSONException e) { message("Could not prepare chapter marker"); }
                }).show();
    }

    private void manageManualChapters(JSONObject item) {
        JSONArray saved = item.optJSONArray("manualChapters");
        if (saved == null || saved.length() == 0) { message("No manual markers for this book"); return; }
        List<Integer> indices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(saved.length(), ManualChapters.MAX_MARKERS); i++) {
            JSONObject marker = saved.optJSONObject(i);
            if (marker == null) continue;
            Mp4Chapters.Chapter chapter = new Mp4Chapters.Chapter(
                    marker.optInt("positionMs", -1), marker.optString("title", ""));
            if (!ManualChapters.valid(chapter, playback.duration())) continue;
            indices.add(i);
            labels.add(time(chapter.positionMs) + " · " + chapter.title);
        }
        if (indices.isEmpty()) { message("No valid manual markers for this book"); return; }
        new AlertDialog.Builder(this).setTitle("Manual chapter markers")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        editManualChapter(item, indices.get(which)))
                .setNegativeButton("Close", null).show();
    }

    private void editManualChapter(JSONObject item, int index) {
        JSONArray saved = item.optJSONArray("manualChapters");
        JSONObject marker = saved == null ? null : saved.optJSONObject(index);
        if (marker == null) { message("That marker is unavailable"); return; }
        int position = marker.optInt("positionMs", -1);
        new AlertDialog.Builder(this).setTitle(time(position) + " · " + marker.optString("title"))
                .setItems(new String[]{"Rename marker", "Delete marker"}, (dialog, choice) -> {
                    if (choice == 0) {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
                        input.setText(marker.optString("title"));
                        new AlertDialog.Builder(this).setTitle("Rename manual chapter")
                                .setView(input).setNegativeButton("Cancel", null)
                                .setPositiveButton("Save", (d, action) -> {
                                    String label = input.getText().toString().trim();
                                    if (label.isEmpty()) { message("Enter a chapter name"); return; }
                                    try {
                                        JSONArray next = new JSONArray(saved.toString());
                                        next.getJSONObject(index).put("title", label);
                                        saveManualChapterList(item, next, "Chapter marker renamed");
                                    } catch (JSONException e) { message("Could not rename marker"); }
                                }).show();
                    } else new AlertDialog.Builder(this).setTitle("Delete manual marker?")
                            .setMessage("The audio file will not change.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", (d, action) -> {
                                JSONArray next = new JSONArray();
                                for (int i = 0; i < saved.length(); i++)
                                    if (i != index) next.put(saved.opt(i));
                                saveManualChapterList(item, next, "Chapter marker deleted");
                            }).show();
                }).show();
    }

    private void saveManualChapterList(JSONObject item, JSONArray next, String success) {
        try {
            JSONObject previous = new JSONObject(item.toString());
            if (next.length() == 0) item.remove("manualChapters");
            else item.put("manualChapters", next);
            if (!saveLibraryNow(false)) {
                if (previous.has("manualChapters")) item.put("manualChapters", previous.opt("manualChapters"));
                else item.remove("manualChapters");
                message("Could not save chapter markers"); return;
            }
            rememberMetadataUndo(Collections.singletonList(previous));
            combinedChapterCache.remove(item.optString("uri"));
            refreshPlayerUi();
            message(success);
        } catch (JSONException e) { message("Could not save chapter markers"); }
    }

    private void chooseChapterLabel(JSONObject item, List<Mp4Chapters.Chapter> chapters) {
        String[] options = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++)
            options[i] = time(chapters.get(i).positionMs) + " · " + chapterLabel(item, chapters.get(i));
        new AlertDialog.Builder(this).setTitle("Correct a chapter label")
                .setItems(options, (dialog, which) -> {
                    Mp4Chapters.Chapter chapter = chapters.get(which);
                    int manualIndex = manualMarkerIndex(item, chapter.positionMs);
                    if (manualIndex >= 0) { editManualChapter(item, manualIndex); return; }
                    EditText input = new EditText(this);
                    input.setSingleLine(true);
                    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
                    input.setText(chapterLabel(item, chapter));
                    new AlertDialog.Builder(this).setTitle("Local chapter label")
                            .setMessage("This changes Arioso's display only; the audio file stays untouched.")
                            .setView(input).setNegativeButton("Cancel", null)
                            .setPositiveButton("Save", (d, button) -> {
                                String label = input.getText().toString().trim();
                                if (label.isEmpty()) { message("Enter a chapter label"); return; }
                                JSONObject previous = item.optJSONObject("chapterLabels");
                                try {
                                    JSONObject undoCopy = new JSONObject(item.toString());
                                    JSONObject updated = previous == null ? new JSONObject()
                                            : new JSONObject(previous.toString());
                                    updated.put(Integer.toString(chapter.positionMs), label);
                                    item.put("chapterLabels", updated);
                                    if (!saveLibraryNow(false)) {
                                        if (previous == null) item.remove("chapterLabels");
                                        else item.put("chapterLabels", previous);
                                        message("Could not save chapter label");
                                    } else rememberMetadataUndo(Collections.singletonList(undoCopy));
                                } catch (JSONException e) { message("Could not save chapter label"); }
                            }).show();
                }).setNegativeButton("Close", null).show();
    }

    private void promptChapterSearch(JSONObject item, List<Mp4Chapters.Chapter> chapters) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Chapter title");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        new AlertDialog.Builder(this).setTitle("Search chapters")
                .setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Search", (dialog, which) ->
                        showChapterChoices(item, chapters, input.getText().toString().trim()))
                .show();
    }

    private void jumpChapter(boolean next) {
        if (playback == null || !playback.isReady()) { message("Choose a playable book first"); return; }
        List<Mp4Chapters.Chapter> chapters = chaptersFor(find(currentUri), playback.duration());
        if (chapters == null || chapters.isEmpty()) {
            message("No chapter list is ready for this book"); return;
        }
        int target = next ? ChapterNavigation.next(chapters, playback.position(), playback.duration())
                : ChapterNavigation.previous(chapters, playback.position());
        playback.seekTo(target);
    }

    private void addBookmark(JSONObject item) {
        int position;
        if (playback == null || !playback.isReady()) {
            message("Playback position is unavailable"); return;
        }
        position = playback.position();
        JSONArray existing = item.optJSONArray("bookmarks");
        int count = existing == null ? 0 : existing.length();
        if (count >= ListeningTools.MAX_BOOKMARKS) { message("This book has 100 bookmarks"); return; }
        EditText label = new EditText(this);
        label.setSingleLine(true);
        label.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        label.setText("Bookmark " + time(position));
        new AlertDialog.Builder(this)
                .setTitle("Save bookmark at " + time(position))
                .setView(label)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, button) -> {
                    JSONArray updated = new JSONArray();
                    boolean inserted = false;
                    try {
                        JSONObject mark = new JSONObject();
                        mark.put("position", Math.max(0, position));
                        mark.put("label", cleanTag(label.getText().toString()) == null
                                ? "Bookmark" : cleanTag(label.getText().toString()));
                        for (int i = 0; i < count; i++) {
                            JSONObject old = existing.optJSONObject(i);
                            if (old == null) continue;
                            if (!inserted && old.optInt("position") > position) {
                                updated.put(mark);
                                inserted = true;
                            }
                            updated.put(old);
                        }
                        if (!inserted) updated.put(mark);
                        item.put("bookmarks", updated);
                        if (!saveLibraryNow(false)) {
                            item.put("bookmarks", existing == null ? new JSONArray() : existing);
                            message("Could not save bookmark");
                        } else message("Bookmark saved");
                    } catch (JSONException ignored) { message("Could not save bookmark"); }
                }).show();
    }

    private void bookmarkAction(JSONObject item, int index, JSONObject mark) {
        String label = metadata(mark, "label", "Bookmark");
        new AlertDialog.Builder(this)
                .setTitle(label + " · " + time(mark.optInt("position")))
                .setItems(new String[]{"Go to bookmark", "Delete bookmark"}, (dialog, action) -> {
                    if (action == 0) seekToAndSave(mark.optInt("position"));
                    else new AlertDialog.Builder(this)
                            .setTitle("Delete bookmark?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", (confirm, button) -> {
                                JSONArray previous = item.optJSONArray("bookmarks");
                                JSONArray updated = new JSONArray();
                                if (previous == null) return;
                                for (int i = 0; i < previous.length(); i++)
                                    if (i != index) updated.put(previous.opt(i));
                                try {
                                    item.put("bookmarks", updated);
                                    if (!saveLibraryNow(false)) {
                                        item.put("bookmarks", previous);
                                        message("Could not delete bookmark");
                                    } else message("Bookmark deleted");
                                } catch (JSONException ignored) { message("Could not delete bookmark"); }
                            }).show();
                }).show();
    }

    private void chooseSleepTimer() {
        int currentFadeSeconds = Math.max(5, Math.min(60,
                prefs.getInt("sleep_fade_ms", 15000) / 1000));
        String[] choices = {"Off", "5 minutes", "5 minutes · fade", "15 minutes",
                "15 minutes · fade", "30 minutes", "30 minutes · fade", "60 minutes",
                "60 minutes · fade", "End of chapter", "After 2 chapters", "After 3 chapters",
                "Fade duration · " + currentFadeSeconds + " seconds",
                "Repeat last timer", "Custom minutes (1–120)"};
        int[] minutes = {0, 5, 5, 15, 15, 30, 30, 60, 60};
        new AlertDialog.Builder(this)
                .setTitle("Sleep timer")
                .setItems(choices, (dialog, which) -> {
                    if (playback == null) { message("Player is connecting"); return; }
                    if (which == 12) { chooseFadeDuration(); return; }
                    if (which == 13) { repeatLastSleepTimer(); return; }
                    if (which == 14) { chooseCustomSleepTimer(); return; }
                    if (which >= 9) {
                        List<Mp4Chapters.Chapter> chapters = chaptersFor(find(currentUri),
                                playback == null ? 0 : playback.duration());
                        if (chapters == null || chapters.isEmpty() || !playback.isReady()) {
                            message("A chapter list is needed for this option"); return;
                        }
                        int count = which == 9 ? 1 : which == 10 ? 2 : 3;
                        int target = ChapterNavigation.afterCount(chapters,
                                playback.position(), playback.duration(), count);
                        if (target <= playback.position()) { message("Already at the last chapter end"); return; }
                        playback.stopAtPosition(target, which == 9 ? "end of chapter"
                                : which == 10 ? "after 2 chapters" : "after 3 chapters");
                    } else {
                        boolean fade = which > 0 && which % 2 == 0;
                        playback.setSleepMinutes(minutes[which], fade);
                        if (minutes[which] > 0) prefs.edit()
                                .putInt("last_sleep_minutes", minutes[which])
                                .putBoolean("last_sleep_fade", fade).apply();
                    }
                    refreshPlayerUi();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void repeatLastSleepTimer() {
        int minutes = prefs.getInt("last_sleep_minutes", 0);
        if (playback == null || minutes < 1 || minutes > 120) {
            message("Set a timed sleep timer first"); return;
        }
        playback.setSleepMinutes(minutes, prefs.getBoolean("last_sleep_fade", false));
        refreshPlayerUi();
    }

    private void chooseCustomSleepTimer() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("1–120 minutes");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        input.setText(String.valueOf(Math.max(1, Math.min(120,
                prefs.getInt("last_sleep_minutes", 20)))));
        new AlertDialog.Builder(this).setTitle("Custom sleep timer")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Start with fade", (dialog, which) -> startCustomSleep(input, true))
                .setPositiveButton("Start", (dialog, which) -> startCustomSleep(input, false)).show();
    }

    private void startCustomSleep(EditText input, boolean fade) {
        int minutes;
        try { minutes = Integer.parseInt(input.getText().toString().trim()); }
        catch (NumberFormatException e) { message("Enter 1 to 120 minutes"); return; }
        if (minutes < 1 || minutes > 120 || playback == null) {
            message("Enter 1 to 120 minutes"); return;
        }
        playback.setSleepMinutes(minutes, fade);
        prefs.edit().putInt("last_sleep_minutes", minutes)
                .putBoolean("last_sleep_fade", fade).apply();
        refreshPlayerUi();
    }

    private void chooseFadeDuration() {
        final int[] seconds = {5, 15, 30, 60};
        new AlertDialog.Builder(this).setTitle("Sleep fade duration")
                .setItems(new String[]{"5 seconds", "15 seconds", "30 seconds", "60 seconds"},
                        (dialog, which) -> {
                            int millis = seconds[which] * 1000;
                            if (!prefs.edit().putInt("sleep_fade_ms", millis).commit()) {
                                message("Could not save fade duration"); return;
                            }
                            if (playback != null) playback.setFadeDuration(millis);
                            message("Sleep fade set to " + seconds[which] + " seconds");
                        }).setNegativeButton("Cancel", null).show();
    }

    private void remove(JSONObject item) {
        boolean selected = item.optString("uri").equals(currentUri);
        if (selected) savePosition();
        int index = books.indexOf(item);
        if (index < 0) return;
        books.remove(index);
        if (!saveLibraryNow(selected)) {
            books.add(index, item);
            message("Could not save the removal. Listing kept.");
            return;
        }
        chapterCache.remove(item.optString("uri"));
        combinedChapterCache.remove(item.optString("uri"));
        if (selected) {
            if (playback != null) playback.clear();
            currentUri = null;
            refreshPlayerUi();
        }
        try { getContentResolver().releasePersistableUriPermission(
                Uri.parse(item.optString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { /* The provider may have already revoked access. */ }
        String treeUri = item.optString("treeUri", "");
        if (!treeUri.isEmpty()) releaseTreeIfUnused(treeUri);
        releaseCoverIfUnused(item.optString("coverUri", ""));
        renderLibrary();
        message("Listing removed; original file untouched");
    }

    private JSONObject find(String uri) {
        if (uri == null) return null;
        for (JSONObject item : books) if (uri.equals(item.optString("uri"))) return item;
        return null;
    }

    private void savePosition() {
        if (playback != null) playback.saveNow();
    }

    private void readLibrary() {
        libraryLoaded = false;
        boolean removedDerivedChapters = false;
        List<JSONObject> loaded = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JSONArray array = LibraryStore.get(this).readAll();
            if (array.length() > 500) throw new IllegalStateException("Library exceeds supported size");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                String uri = item == null ? "" : item.optString("uri");
                if (item == null || uri.length() > 4096
                        || !"content".equals(Uri.parse(uri).getScheme()) || !seen.add(uri))
                    throw new IllegalStateException("Library contains an unreadable listing");
                if (item.has("chapters")) {
                    item.remove("chapters");
                    removedDerivedChapters = true;
                }
                loaded.add(item);
            }
            books.clear();
            books.addAll(loaded);
            libraryLoaded = true;
        } catch (RuntimeException ignored) {
            message("Library storage could not be read. Saved data is kept; changes are paused. Reopen Arioso to retry.");
        }
        if (libraryLoaded && removedDerivedChapters && !saveLibraryNow(false))
            message("Could not compact derived chapter data; Arioso will retry later");
    }

    private boolean libraryReadyForWrite() {
        if (libraryLoaded && syncSavedProgress()) return true;
        message("Library storage could not be read. Saved data is kept; reopen Arioso before making changes.");
        return false;
    }

    private boolean saveLibraryNow(boolean clearSelected) {
        if (!libraryReadyForWrite()) return false;
        if (!clearSelected && playback != null && playback.isReady()) {
            JSONObject active = find(playback.currentUri());
            if (active != null) try {
                active.put("position", playback.position());
                active.put("duration", playback.duration());
                active.put("finished", playback.isCompleted());
            } catch (JSONException ignored) { }
        }
        if (!LibraryStore.get(this).replaceAll(books)) return false;
        return !clearSelected || prefs.edit().remove("selected_uri").commit();
    }

    private boolean syncSavedProgress() {
        // A successful progress read cannot validate a list from a failed initial load.
        if (!libraryLoaded) return false;
        try {
            JSONArray latest = LibraryStore.get(this).readAll();
            for (int i = 0; i < latest.length(); i++) {
                JSONObject stored = latest.optJSONObject(i);
                if (stored == null) continue;
                JSONObject local = find(stored.optString("uri"));
                if (local == null) continue;
                local.put("position", stored.optInt("position"));
                local.put("duration", stored.optInt("duration"));
                local.put("finished", stored.optBoolean("finished"));
                if (stored.has("lastPlayedAt")) local.put("lastPlayedAt", stored.optLong("lastPlayedAt"));
            }
            return true;
        } catch (RuntimeException | JSONException ignored) {
            libraryLoaded = false;
            return false;
        }
    }

    private String libraryJson() {
        JSONArray array = new JSONArray();
        for (JSONObject item : books) array.put(item);
        return array.toString();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        if (playback != null) {
            playback.saveNow();
            playback.clearListener(playbackListener);
            playback = null;
        }
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && nestedBackRegistered) {
            Api33Back.unregister(this, nestedBackCallback);
            nestedBackCallback = null;
            nestedBackRegistered = false;
        }
        handler.removeCallbacksAndMessages(null);
        destroyed = true;
        if (pendingExportPassword != null) Arrays.fill(pendingExportPassword, '\0');
        pendingExportPassword = null;
        if (activeFingerprintCheck != null) activeFingerprintCheck.set(true);
        activeFingerprintCheck = null;
        if (activeFolderScan != null) activeFolderScan.set(true);
        if (folderScanDialog != null) folderScanDialog.dismiss();
        if (appearanceDialog != null) appearanceDialog.dismissSilently();
        appearanceDialog = null;
        if (drivingDialog != null) drivingDialog.dismiss();
        folderScanDialog = null;
        activeFolderScan = null;
        activeFolderTree = null;
        artworkWorker.shutdownNow();
        localSyncWorker.shutdownNow();
        waitingArtwork.clear();
        chapterScanInFlight.clear();
        chapterCache.clear();
        combinedChapterCache.clear();
        artwork.evictAll();
        absentArtwork.clear();
        shelfItems.clear();
        books.clear();
        currentUri = null;
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            artwork.evictAll();
            combinedChapterCache.clear();
        }
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(INK);
        if (!"system".equals(uiFont)) view.setTypeface(android.graphics.Typeface.create(uiFont,
                android.graphics.Typeface.NORMAL));
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }
    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextColor(INK);
        view.setTextSize(15);
        if (!"system".equals(uiFont)) view.setTypeface(android.graphics.Typeface.create(uiFont,
                android.graphics.Typeface.NORMAL));
        view.setMinHeight(dp(52));
        view.setBackground(control(BLUSH, OUTLINE, PLUM));
        view.setElevation(0);
        view.setStateListAnimator(null);
        return view;
    }
    private void applyInterfaceFont(View view) {
        if ("system".equals(uiFont)) return;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            android.graphics.Typeface previous = label.getTypeface();
            int style = previous == null ? android.graphics.Typeface.NORMAL : previous.getStyle();
            label.setTypeface(android.graphics.Typeface.create(uiFont, style));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyInterfaceFont(group.getChildAt(i));
        }
    }
    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PAPER);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), OUTLINE);
        card.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        root.addView(card, params);
        return card;
    }
    private LinearLayout.LayoutParams weightedControl() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }
    private ProgressBar progressLine(int percent, int width) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, percent)));
        bar.setProgressTintList(ColorStateList.valueOf(PLUM));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.setLayoutParams(new LinearLayout.LayoutParams(width, dp(4)));
        return bar;
    }
    private RippleDrawable decoratedCard() {
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(PAPER, PLUM, 0.08f), PAPER});
        surface.setCornerRadius(dp(20));
        surface.setStroke(dp(1), OUTLINE);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(20));
        return new RippleDrawable(ColorStateList.valueOf((PLUM & 0x00ffffff) | 0x24000000),
                surface, mask);
    }
    private static int blend(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.rgb(Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }
    private RippleDrawable control(int fill, int outline, int ripple) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(fill);
        surface.setCornerRadius(dp(12));
        surface.setStroke(dp(1), outline);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(android.graphics.Color.WHITE);
        mask.setCornerRadius(dp(12));
        return new RippleDrawable(ColorStateList.valueOf((ripple & 0x00ffffff) | 0x24000000), surface, mask);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static boolean isOnDeviceUri(String rawUri) {
        if (rawUri == null || rawUri.length() > 4096) return false;
        Uri uri = Uri.parse(rawUri);
        return LocalDocumentPolicy.accepts(uri.getScheme(), uri.getAuthority());
    }
    private static String time(int millis) {
        int seconds = Math.max(0, millis / 1000);
        int hours = seconds / 3600;
        int minutes = (seconds / 60) % 60;
        int rest = seconds % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.US, "%d:%02d", minutes, rest);
    }
}
