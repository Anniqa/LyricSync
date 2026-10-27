package com.lyricsync.app.ui.main;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.button.MaterialButton;
import com.lyricsync.app.R;
import com.lyricsync.app.ui.AlbumPalette;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.util.NowPlaying;

/**
 * Hero card: now-playing mirror, equalizer, album-tinted card + ambient glow,
 * the primary start/stop button and the header "Live" pill.
 */
public final class HeroSection {

    /** How much album accent is mixed into the hero surface at its top-left corner. */
    private static final float HERO_ACCENT_MIX = 0.18f;
    private static final int HERO_STROKE_ALPHA = 0x33;
    private static final long TINT_MS = 700;
    private static final float[] EQ_FLOORS = {0.30f, 0.62f, 0.42f};
    private static final long[] EQ_DURATIONS = {430, 560, 350};

    private final Activity activity;
    private final View heroGlow;
    private final View heroCard;
    private final GradientDrawable heroBg;
    private final View equalizer;
    private final View[] eqBars;
    private final ImageView art;
    private final ImageView artPlaceholder;
    private final TextView title;
    private final TextView artist;
    private final TextView status;
    private final MaterialButton startButton;
    private final TextView livePill;

    private final int surface;
    private final int strokePx;

    private ObjectAnimator[] eqAnimators;
    private ValueAnimator tintAnimator;
    private ValueAnimator startTintAnimator;
    private int accent = AlbumPalette.DEFAULT_ACCENT;
    /** Playback-state pushes repeat the same bitmap; only re-run Palette when it changes. */
    private Bitmap lastPaletteArt;
    private boolean lastRunning;
    private boolean startStateKnown;

    public HeroSection(Activity activity) {
        this.activity = activity;
        heroGlow = activity.findViewById(R.id.hero_glow);
        heroCard = activity.findViewById(R.id.card_now_playing);
        equalizer = activity.findViewById(R.id.np_equalizer);
        eqBars = new View[]{
                activity.findViewById(R.id.eq_bar_1),
                activity.findViewById(R.id.eq_bar_2),
                activity.findViewById(R.id.eq_bar_3)};
        art = activity.findViewById(R.id.np_art);
        artPlaceholder = activity.findViewById(R.id.np_art_placeholder);
        title = activity.findViewById(R.id.np_title);
        artist = activity.findViewById(R.id.np_artist);
        status = activity.findViewById(R.id.np_status);
        startButton = activity.findViewById(R.id.btn_start);
        livePill = activity.findViewById(R.id.header_live_pill);

        surface = ContextCompat.getColor(activity, R.color.ls_surface_container);
        strokePx = Math.round(activity.getResources().getDisplayMetrics().density);
        heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{surface, surface});
        heroBg.setCornerRadius(activity.getResources().getDimension(R.dimen.ls_radius_xl));
        heroCard.setBackground(heroBg);
        applyAccent(accent);
    }

    public View startButton() {
        return startButton;
    }

    public View card() {
        return heroCard;
    }

    // ── Now playing ─────────────────────────────────────────────────────

    public void onNowPlaying(NowPlaying.Snapshot snapshot) {
        if (!snapshot.hasTrack) {
            Anim.setTextAnimated(title, activity.getString(R.string.nothing_playing));
            Anim.setTextAnimated(artist, activity.getString(R.string.nothing_playing_desc));
            status.setVisibility(View.GONE);
            clearArt();
            stopEqualizer();
            equalizer.setVisibility(View.INVISIBLE);
            tint(AlbumPalette.DEFAULT_ACCENT);
            return;
        }

        Anim.setTextAnimated(title, snapshot.title == null ? "" : snapshot.title);
        Anim.setTextAnimated(artist, snapshot.artist == null ? "" : snapshot.artist);

        if (snapshot.lyricsStatus != null && !snapshot.lyricsStatus.isEmpty()) {
            status.setText(snapshot.lyricsStatus);
            if (status.getVisibility() != View.VISIBLE) {
                status.setVisibility(View.VISIBLE);
                Anim.pop(status);
            }
        } else {
            status.setVisibility(View.GONE);
        }

        Bitmap bmp = snapshot.art;
        if (bmp != null && !bmp.isRecycled()) {
            art.setImageBitmap(bmp);
            artPlaceholder.setVisibility(View.GONE);
            if (bmp != lastPaletteArt) {
                lastPaletteArt = bmp;
                AlbumPalette.from(bmp, (a, deep) -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) tint(a);
                });
            }
        } else {
            clearArt();
            tint(AlbumPalette.DEFAULT_ACCENT);
        }

        if (snapshot.playing) {
            equalizer.setVisibility(View.VISIBLE);
            startEqualizer();
        } else {
            stopEqualizer();
            equalizer.setVisibility(View.INVISIBLE);
        }
    }

    private void clearArt() {
        art.setImageDrawable(null);
        artPlaceholder.setVisibility(View.VISIBLE);
        lastPaletteArt = null;
    }

    // ── Album tint ──────────────────────────────────────────────────────

    private void tint(int target) {
        if (target == accent) return;
        if (tintAnimator != null) tintAnimator.cancel();
        int from = accent;
        accent = target;
        tintAnimator = Anim.color(from, target, TINT_MS, this::applyAccent);
    }

    private void applyAccent(int color) {
        // Opaque blend, so the card never shows the window glow through it.
        heroBg.setColors(new int[]{AlbumPalette.blend(surface, color, HERO_ACCENT_MIX), surface});
        heroBg.setStroke(strokePx, AlbumPalette.withAlpha(color, HERO_STROKE_ALPHA));
        tintBackground(heroGlow, color);
        for (View bar : eqBars) tintBackground(bar, color);
    }

    /** SRC_IN tinting keeps the drawable's alpha ramp. */
    private static void tintBackground(View v, int color) {
        if (v == null) return;
        Drawable bg = v.getBackground();
        if (bg == null) return;
        Drawable mutated = bg.mutate();
        DrawableCompat.setTint(mutated, color);
        v.setBackground(mutated);
    }

    // ── Equalizer ───────────────────────────────────────────────────────

    private void startEqualizer() {
        if (eqAnimators != null) return;
        eqAnimators = new ObjectAnimator[eqBars.length];
        for (int i = 0; i < eqBars.length; i++) {
            final View bar = eqBars[i];
            if (bar == null) continue;
            // Bars grow from the baseline, so the pivot must sit at the bottom edge.
            bar.post(() -> bar.setPivotY(bar.getHeight()));
            ObjectAnimator a = ObjectAnimator.ofFloat(bar, View.SCALE_Y, EQ_FLOORS[i], 1f);
            a.setDuration(EQ_DURATIONS[i]);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.start();
            eqAnimators[i] = a;
        }
    }

    public void stopEqualizer() {
        if (eqAnimators == null) return;
        for (ObjectAnimator a : eqAnimators) {
            if (a != null) a.cancel();
        }
        eqAnimators = null;
    }

    // ── Start / stop ────────────────────────────────────────────────────

    /** Re-reads the service state; animates the button only when it actually changed. */
    public void updateRunning(boolean running) {
        livePill.setVisibility(running ? View.VISIBLE : View.GONE);
        if (startStateKnown && running == lastRunning) return;

        startButton.setText(running ? R.string.stop : R.string.start);
        startButton.setIconResource(running ? R.drawable.ic_stop : R.drawable.ic_play);

        int from = ContextCompat.getColor(activity, lastRunning ? R.color.ls_error_container : R.color.ls_primary);
        int to = ContextCompat.getColor(activity, running ? R.color.ls_error_container : R.color.ls_primary);
        int content = ContextCompat.getColor(activity, running ? R.color.ls_error : R.color.ls_on_primary);
        startButton.setTextColor(content);
        startButton.setIconTint(ColorStateList.valueOf(content));

        if (startTintAnimator != null) startTintAnimator.cancel();
        if (startStateKnown) {
            startTintAnimator = Anim.color(from, to, Anim.D_MED,
                    c -> startButton.setBackgroundTintList(ColorStateList.valueOf(c)));
            Anim.pop(startButton);
            if (running) Anim.pop(livePill);
        } else {
            startButton.setBackgroundTintList(ColorStateList.valueOf(to));
        }

        lastRunning = running;
        startStateKnown = true;
    }

    public void release() {
        stopEqualizer();
        if (tintAnimator != null) tintAnimator.cancel();
        if (startTintAnimator != null) startTintAnimator.cancel();
    }
}
