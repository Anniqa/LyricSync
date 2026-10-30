package com.lyricsync.app.ui.lyrics;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.lyricsync.app.R;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.AnimStyleDef;
import com.lyricsync.app.renderer.SpringScroller;
import com.lyricsync.app.renderer.SyllableHighlighter;
import com.lyricsync.app.ui.AlbumPalette;
import com.lyricsync.app.util.AppFont;
import com.lyricsync.app.util.Haptics;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen lyrics inside the app. With the overlay running it mirrors the service
 * (same lyrics, clock and combo); without it, {@link StandaloneLyricsSource} follows
 * the player itself, so the overlay never has to be started first. Rendering uses its
 * own {@link SyllableHighlighter}, so every effect matches the overlay, only bigger.
 * Tap a line or drag the timeline to jump anywhere; scrolling by hand pauses
 * auto-follow until "Back to the current line". The Latin / original / translation
 * chips write the same prefs as the overlay switches, so both always agree; "Lyra"
 * opens the AI on this screen. The screen stays on while open.
 */
public final class LyricsActivity extends Activity {

    /** Big enough to read at arm's length, small enough that most lines take two rows at most. */
    private static final float FONT_SP = 28f;
    /** Auto-follow resumes this long after the user stops scrolling. */
    private static final long MANUAL_SCROLL_MS = 4000;

    private FloatingOverlayService.LyricsSource source;
    /** Used when the overlay service is not running; null otherwise. */
    private StandaloneLyricsSource standalone;
    private LyraSheet lyra;
    private com.lyricsync.app.overlay.LyricsSearchDialog searchDialog;
    private android.content.SharedPreferences prefs;
    /** Any display pref (from the chips, the overlay or Lyra) rebuilds the lines. */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefWatch = (p, k) -> dirty = true;
    private SyllableHighlighter highlighter;
    private SpringScroller scroller;
    private final List<SyllableHighlighter.LineView> lineViews = new ArrayList<>();
    private LyricsData shown;
    /** What the built lines show; play/pause and art updates leave it unchanged, so no rebuild. */
    private long shownStamp;
    private String shownArtKey;
    private boolean dirty;
    private long lastFrameNanos;
    private long manualUntil;
    private int lastActive = -1;

    private ScrollView scroll;
    private LinearLayout lines;
    private View empty, resync;
    private TextView emptyText, title, artist, combo;
    private com.google.android.material.button.MaterialButton chipLatin, chipOriginal, chipTranslation;
    private View chipBusy;
    private int accent = 0xFF1ED760;
    private CoverDiscView cover;
    private ImageView backdrop;
    private PlayPauseButton play;
    /** After a tap the player needs a moment to report the new state; until then the button shows the tap. */
    private long playPendingUntil;
    private TimelineView timeline;
    private long timelineLyricsStamp;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            double dt = lastFrameNanos == 0 ? 1 / 60.0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
            lastFrameNanos = now;
            step(dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public static void open(Activity from) {
        from.startActivity(new Intent(from, LyricsActivity.class));
        from.overridePendingTransition(R.anim.ls_lyrics_enter, android.R.anim.fade_out);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_lyrics);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scroll = findViewById(R.id.lyrics_scroll);
        lines = findViewById(R.id.lyrics_lines);
        empty = findViewById(R.id.lyrics_empty);
        emptyText = findViewById(R.id.lyrics_empty_text);
        resync = findViewById(R.id.lyrics_resync);
        title = findViewById(R.id.lyrics_title);
        // Long titles scroll left (marquee needs the view selected, it never takes focus).
        title.setSelected(true);
        artist = findViewById(R.id.lyrics_artist);
        combo = findViewById(R.id.lyrics_combo);
        cover = findViewById(R.id.lyrics_cover);
        backdrop = findViewById(R.id.lyrics_backdrop);
        play = findViewById(R.id.lyrics_play);
        play.setLabels(getString(R.string.lyrics_full_play), getString(R.string.lyrics_full_pause));
        timeline = findViewById(R.id.lyrics_timeline);
        timeline.setTypeface(AppFont.current(this).medium);
        timeline.setListener(ms -> {
            if (source == null) return;
            Haptics.confirm(timeline);
            manualUntil = 0;
            resync.setVisibility(View.GONE);
            source.seekTo(ms);
        });
        prefs = getSharedPreferences(com.lyricsync.app.ui.main.MainPrefs.FILE, MODE_PRIVATE);
        chipLatin = findViewById(R.id.lyrics_chip_latin);
        chipOriginal = findViewById(R.id.lyrics_chip_original);
        chipTranslation = findViewById(R.id.lyrics_chip_translation);
        chipBusy = findViewById(R.id.lyrics_chip_busy);
        chipLatin.setOnClickListener(v -> toggle(v, StandaloneLyricsSource.PREF_ROMAJI, true));
        chipOriginal.setOnClickListener(v -> toggle(v, StandaloneLyricsSource.PREF_ORIGINAL, true));
        chipTranslation.setOnClickListener(v -> toggle(v,
                com.lyricsync.app.lyrics.translation.GoogleTranslateService.PREF_TRANSLATION_ENABLED, false));
        lyra = new LyraSheet(this, new LyraSheet.Context() {
            @Override
            public FloatingOverlayService.LyricsSource source() {
                return source;
            }

            @Override
            public int activeLine() {
                return shown != null && source != null && !lineViews.isEmpty() ? LyricsActivity.this.activeLine(source.position()) : -1;
            }
        });
        View.OnClickListener searchLyrics = v -> {
            Haptics.tick(v);
            openSearch();
        };
        findViewById(R.id.lyrics_chip_search).setOnClickListener(searchLyrics);
        findViewById(R.id.lyrics_empty_search).setOnClickListener(searchLyrics);
        findViewById(R.id.lyrics_ask_lyra).setOnClickListener(v -> {
            Haptics.tick(v);
            lyra.show();
        });
        scroller = new SpringScroller(scroll);
        scroller.setScrollPositionRatio(0.32f);
        if (Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP));
        }

        View content = findViewById(R.id.lyrics_content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            int pad = Math.round(getResources().getDisplayMetrics().heightPixels * 0.30f);
            lines.setPadding(lines.getPaddingLeft(), pad, lines.getPaddingRight(), pad);
            return WindowInsetsCompat.CONSUMED;
        });

        findViewById(R.id.lyrics_close).setOnClickListener(v -> finish());
        play.setOnClickListener(v -> {
            if (source == null) return;
            Haptics.confirm(v);
            play.setPlaying(!play.isPlaying(), true); // answer the tap at once
            playPendingUntil = System.currentTimeMillis() + 700;
            source.togglePlayPause();
        });
        findViewById(R.id.lyrics_prev).setOnClickListener(v -> {
            Haptics.tick(v);
            if (source != null) source.skip(false);
        });
        findViewById(R.id.lyrics_next).setOnClickListener(v -> {
            Haptics.tick(v);
            if (source != null) source.skip(true);
        });
        resync.setOnClickListener(v -> {
            Haptics.tick(v);
            manualUntil = 0;
            resync.setVisibility(View.GONE);
        });
        findViewById(R.id.lyrics_start).setOnClickListener(v -> {
            Haptics.tick(v);
            // The only thing the full-screen view needs is notification access (to read the player).
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
        scroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                manualUntil = System.currentTimeMillis() + MANUAL_SCROLL_MS;
                resync.setVisibility(View.VISIBLE);
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(prefWatch);
        attach();
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Choreographer.getInstance().removeFrameCallback(frame);
        prefs.unregisterOnSharedPreferenceChangeListener(prefWatch);
        if (source != null) source.setListener(null);
        if (standalone != null) {
            standalone.stop();
            standalone = null;
        }
        source = null;
    }

    /** The overlay's search dialog, on top of this screen; the pick shows here at once. */
    private void openSearch() {
        if (source == null || source.track() == null || source.lyricsManager() == null) return;
        if (searchDialog != null) searchDialog.dismiss();
        final FloatingOverlayService.LyricsSource src = source;
        searchDialog = com.lyricsync.app.overlay.LyricsSearchDialog.forActivity(this,
                new com.lyricsync.app.overlay.LyricsSearchDialog.Host() {
                    @Override
                    public TrackInfo currentTrack() {
                        return src.track();
                    }

                    @Override
                    public com.lyricsync.app.lyrics.LyricsProviderManager lyricsManager() {
                        return src.lyricsManager();
                    }

                    @Override
                    public void useLyrics(LyricsData lyrics) {
                        src.useLyrics(lyrics);
                        dirty = true;
                    }
                });
        searchDialog.show();
    }

    @Override
    protected void onDestroy() {
        if (searchDialog != null) searchDialog.dismiss();
        lyra.release();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, R.anim.ls_lyrics_exit);
    }

    /**
     * Mirrors the overlay when it runs; otherwise follows the player directly. Only a
     * missing notification access stops it (that is how any app reads the player).
     */
    private void attach() {
        source = FloatingOverlayService.lyricsSource();
        if (source == null) {
            standalone = new StandaloneLyricsSource(this);
            if (!standalone.start()) {
                standalone = null;
                showEmpty(getString(R.string.lyrics_full_need_access), true);
                return;
            }
            source = standalone;
        }
        source.setListener(() -> dirty = true);
        shown = null;
        shownStamp = 0;
        timelineLyricsStamp = 0;
        dirty = true;
    }

    private void step(double dt) {
        if (source == null) return;
        if (dirty) {
            dirty = false;
            refresh();
        }
        long pos = source.position();
        updateTransport(pos);
        if (highlighter == null || shown == null || lineViews.isEmpty()) return;
        int active = activeLine(pos);
        if (lastActive < 0 || Math.abs(active - lastActive) > 1) {
            highlighter.updateHighlight(pos, dt); // first frame or a seek: restyle every line
        } else {
            highlighter.updateHighlight(pos, dt, Math.max(0, lastActive - 2), Math.min(lineViews.size(), active + 6));
        }
        if (active >= 0 && System.currentTimeMillis() > manualUntil) {
            if (resync.getVisibility() == View.VISIBLE) resync.setVisibility(View.GONE);
            View v = lines.getChildAt(active);
            if (v != null) scroller.followView(v, dt);
        }
        lastActive = active;
    }

    /** Rebuilds the lines when the lyrics or combo changed, and updates the header. */
    private void refresh() {
        TrackInfo track = source.track();
        setIfChanged(title, track != null ? track.title : getString(R.string.no_music));
        setIfChanged(artist, track != null && track.artist != null ? track.artist : "");
        updateArt(track);
        AnimConfig cfg = source.animConfig();
        AnimStyleDef m = AnimStyleDef.Registry.motionByKey(cfg.motionKey);
        AnimStyleDef e = AnimStyleDef.Registry.effectByKey(cfg.effectKey);
        combo.setText(e == AnimStyleDef.Registry.none() ? m.displayName
                : getString(R.string.lyrics_full_combo, m.displayName, e.displayName));

        LyricsData lyrics = source.lyrics();
        updateChips(lyrics);
        updateTimelineLines(lyrics);
        if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) {
            lines.removeAllViews();
            lineViews.clear();
            boolean loading = standalone != null && standalone.isLoading();
            showEmpty(getString(track == null ? R.string.lyrics_full_nothing_playing
                    : loading ? R.string.lyrics_full_loading : R.string.lyrics_full_no_lyrics), false);
            // Keep the real track in the header even while there are no lines.
            setIfChanged(title, track != null ? track.title : getString(R.string.lyrics_full_title));
            setIfChanged(artist, track != null && track.artist != null ? track.artist : "");
            return;
        }
        empty.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        long stamp = stamp(lyrics, cfg);
        if (lyrics == shown && stamp == shownStamp) return;
        shown = lyrics;
        shownStamp = stamp;
        build(lyrics, cfg);
    }

    private void build(LyricsData lyrics, AnimConfig cfg) {
        AppFont.FontPair fonts = AppFont.current(this);
        highlighter = new SyllableHighlighter(this, fonts.bold, fonts.medium, FONT_SP);
        highlighter.setAnimConfig(cfg);
        highlighter.setCenterLines(true);
        highlighter.setSyllableMode(lyrics.type == LyricsData.Type.SYLLABLE);
        highlighter.setTranslationEnabled(source.translationEnabled());
        highlighter.setTransliterationEnabled(source.romajiEnabled());
        highlighter.setOriginalScriptEnabled(source.originalScriptEnabled());
        lines.removeAllViews();
        lineViews.clear();
        int gap = Math.round(FONT_SP * 0.75f * getResources().getDisplayMetrics().scaledDensity);
        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricsData.LyricsLine line = lyrics.lines.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            SyllableHighlighter.LineView lv = highlighter.createLineView(line, lp);
            lineViews.add(lv);
            if (!line.isInterlude) {
                final long start = Math.max(0, line.startTime);
                lv.rootView.setOnClickListener(v -> {
                    Haptics.confirm(v);
                    manualUntil = 0;
                    resync.setVisibility(View.GONE);
                    source.seekTo(start);
                });
            }
            lines.addView(lv.rootView);
        }
        lastActive = -1;
        long pos = source.position();
        highlighter.updateHighlight(pos, 0);
        int active = activeLine(pos);
        lines.post(() -> {
            View v = active >= 0 ? lines.getChildAt(active) : null;
            if (v != null) scroller.jumpToView(v);
        });
    }

    /**
     * Content fingerprint: line texts, translations and romaji (edited in place), plus the
     * combo and the display switches. Equal stamps mean the built views are still right.
     */
    private long stamp(LyricsData lyrics, AnimConfig cfg) {
        // By combo key: the stand-alone source builds a fresh AnimConfig on every call.
        long h = (cfg.motionKey + "|" + cfg.effectKey).hashCode();
        h = h * 31 + (source.translationEnabled() ? 1 : 0) + (source.romajiEnabled() ? 2 : 0)
                + (source.originalScriptEnabled() ? 4 : 0);
        for (LyricsData.LyricsLine l : lyrics.lines) {
            h = h * 31 + (l.text != null ? l.text.hashCode() : 0);
            h = h * 31 + (l.translation != null ? l.translation.hashCode() : 0);
            h = h * 31 + (l.romanji != null ? l.romanji.hashCode() : 0);
            // Word readings arrive without touching the line text; include them too.
            if (l.words != null) for (LyricsData.Word w : l.words) {
                h = h * 31 + (w.romanji != null ? w.romanji.hashCode() : 0);
            }
        }
        return h;
    }

    private int activeLine(long pos) {
        List<LyricsData.LyricsLine> l = shown.lines;
        int lo = 0, hi = l.size() - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).startTime <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.min(ans, lines.getChildCount() - 1);
    }

    /**
     * Clock and play state, every frame. The length is read live from the player, so a
     * duration that arrives after the title shows up at once.
     */
    private void updateTransport(long pos) {
        timeline.setDuration(source.duration());
        timeline.setPosition(pos);
        boolean playing = source.isPlaying();
        timeline.setPlaying(playing);
        cover.setPlaying(playing);
        if (System.currentTimeMillis() >= playPendingUntil && playing != play.isPlaying()) {
            play.setPlaying(playing, true);
        }
        boolean busy = source.transliterating();
        if ((chipBusy.getVisibility() == View.VISIBLE) != busy) chipBusy.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    /** Line starts + texts for the timeline segments and the drag bubble (Latin when shown). */
    private void updateTimelineLines(LyricsData lyrics) {
        long stamp = lyrics == null || lyrics.lines == null ? 0 : System.identityHashCode(lyrics) * 31L
                + lyrics.lines.size() + (source.romajiEnabled() ? 1 : 0) + (source.originalScriptEnabled() ? 2 : 0)
                + (lyrics.hasTransliteration ? 4 : 0);
        if (stamp == timelineLyricsStamp) return;
        timelineLyricsStamp = stamp;
        if (stamp == 0) {
            timeline.setLines(null, null);
            return;
        }
        boolean latin = source.romajiEnabled() && !source.originalScriptEnabled();
        long[] starts = new long[lyrics.lines.size()];
        String[] texts = new String[starts.length];
        for (int i = 0; i < starts.length; i++) {
            LyricsData.LyricsLine l = lyrics.lines.get(i);
            starts[i] = Math.max(0, l.startTime);
            boolean useRomaji = latin && l.romanji != null && !l.romanji.trim().isEmpty();
            texts[i] = l.isInterlude ? "" : useRomaji ? l.romanji : l.text;
        }
        timeline.setLines(starts, texts);
    }

    /** Latin and original script only matter for non-Latin lyrics; translation always does. */
    private void updateChips(LyricsData lyrics) {
        boolean has = lyrics != null && !lyrics.isEmpty();
        boolean nonLatin = has && StandaloneLyricsSource.nonLatin(lyrics);
        findViewById(R.id.lyrics_chips_scroll).setVisibility(source.track() != null ? View.VISIBLE : View.GONE);
        chipTranslation.setVisibility(has ? View.VISIBLE : View.GONE);
        chipLatin.setVisibility(nonLatin ? View.VISIBLE : View.GONE);
        chipOriginal.setVisibility(nonLatin && source.romajiEnabled() ? View.VISIBLE : View.GONE);
        chipLatin.setChecked(source.romajiEnabled());
        chipOriginal.setChecked(source.originalScriptEnabled());
        chipTranslation.setChecked(source.translationEnabled());
        tintChips();
    }

    private void tintChips() {
        int[][] states = {{android.R.attr.state_checked}, {}};
        ColorStateList bg = new ColorStateList(states, new int[]{accent, 0x1FFFFFFF});
        int on = PlayPauseButton.luminance(accent) > 0.45f ? 0xFF0B0C10 : 0xFFFFFFFF;
        ColorStateList fg = new ColorStateList(states, new int[]{on, 0xE6FFFFFF});
        for (com.google.android.material.button.MaterialButton c : new com.google.android.material.button.MaterialButton[]{chipLatin, chipOriginal, chipTranslation}) {
            c.setBackgroundTintList(bg);
            c.setTextColor(fg);
            c.setIconTint(fg);
        }
    }

    /**
     * Flips a display pref. The overlay (when running) and the stand-alone source both
     * listen to it, fetch what is missing (Latin reading, translation) and re-render.
     */
    private void toggle(View chip, String key, boolean def) {
        Haptics.tick(chip);
        boolean now = !prefs.getBoolean(key, def);
        android.content.SharedPreferences.Editor e = prefs.edit().putBoolean(key, now);
        // Hiding the original script needs the Latin line, or nothing would be left to read.
        if (StandaloneLyricsSource.PREF_ORIGINAL.equals(key) && !now) e.putBoolean(StandaloneLyricsSource.PREF_ROMAJI, true);
        e.apply();
        dirty = true;
    }

    /** Cover + blurred backdrop + scrim tinted with the album's deep colour; skipped when the art is unchanged. */
    private void updateArt(TrackInfo track) {
        Bitmap art = track != null ? track.albumArtBitmap : null;
        String key = art == null ? "" : art.getWidth() + "x" + art.getHeight() + "@" + System.identityHashCode(art);
        if (key.equals(shownArtKey)) return;
        shownArtKey = key;
        cover.setArt(art);
        backdrop.setImageBitmap(art);
        AlbumPalette.from(art, (accent, deep) -> {
            GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{AlbumPalette.withAlpha(deep, 0xCC), AlbumPalette.withAlpha(deep, 0x99), 0xF207080C});
            findViewById(R.id.lyrics_scrim).setBackground(scrim);
            this.accent = accent;
            timeline.setAccent(accent);
            play.setAccent(accent);
            cover.setAccent(accent);
            tintChips();
            combo.setTextColor(accent);
        });
    }

    private void showEmpty(String message, boolean offerStart) {
        shown = null;
        scroll.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        emptyText.setText(message);
        findViewById(R.id.lyrics_start).setVisibility(offerStart ? View.VISIBLE : View.GONE);
        boolean canSearch = !offerStart && source != null && source.track() != null
                && !(standalone != null && standalone.isLoading());
        findViewById(R.id.lyrics_empty_search).setVisibility(canSearch ? View.VISIBLE : View.GONE);
        if (offerStart) {
            setIfChanged(title, getString(R.string.lyrics_full_title));
            setIfChanged(artist, "");
        }
    }

    /**
     * setText restarts a running marquee from the left, and refresh() runs on every player
     * update, so the title would never get to scroll. Only touch it when the text changes.
     */
    private static void setIfChanged(TextView v, CharSequence text) {
        CharSequence now = v.getText();
        if (now == null ? text == null : text != null && now.toString().contentEquals(text)) return;
        v.setText(text);
    }
}
