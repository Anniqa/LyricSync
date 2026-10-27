package com.lyricsync.app.overlay;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.lyricsync.app.ui.AlbumPalette;
import com.lyricsync.app.ui.Anim;

/**
 * Album-tinted overlay theme: the window's gradient background plus the accent
 * colour handed to the controls. Colour changes cross-fade; a new track's palette
 * replaces a running fade instead of stacking animators.
 */
final class OverlayTheme {

    /** Receives every intermediate accent so the controls fade with the background. */
    interface AccentSink {
        void onAccent(ColorStateList tint, int accent);
    }

    private final OverlayConfig config;
    private final OverlayScreen screen;
    private final AccentSink sink;
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final int[] gradient = new int[3];

    private GradientDrawable background;
    private ValueAnimator animator;
    private int accent = AlbumPalette.DEFAULT_ACCENT;
    private int deep = AlbumPalette.DEFAULT_DEEP;
    private boolean released;

    OverlayTheme(OverlayConfig config, OverlayScreen screen, AccentSink sink) {
        this.config = config;
        this.screen = screen;
        this.sink = sink;
    }

    /** Replaces the root's static XML shape with a drawable that can be recoloured. */
    void install(View root) {
        background = new GradientDrawable();
        background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        background.setCornerRadius(screen.px(config.windowCornerRadiusDp));
        apply(accent, deep);
        root.setBackground(background);
    }

    int accent() {
        return accent;
    }

    /** Extract the album palette off-thread and fade to it. */
    void fromArt(Bitmap art) {
        AlbumPalette.from(art, (a, d) -> {
            if (!released) animateTo(a, d);
        });
    }

    void reset() {
        animateTo(AlbumPalette.DEFAULT_ACCENT, AlbumPalette.DEFAULT_DEEP);
    }

    void animateTo(int toAccent, int toDeep) {
        if (released || (toAccent == accent && toDeep == deep)) return;
        if (animator != null) animator.cancel();
        final int fromAccent = accent;
        final int fromDeep = deep;
        accent = toAccent;
        deep = toDeep;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(config.colorTransitionMs);
        animator.setInterpolator(Anim.STANDARD);
        animator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            apply((Integer) argb.evaluate(f, fromAccent, toAccent),
                    (Integer) argb.evaluate(f, fromDeep, toDeep));
        });
        animator.start();
    }

    /** Cancel the fade and ignore late palette results (service teardown). */
    void release() {
        released = true;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void apply(int accentNow, int deepNow) {
        if (background != null) {
            // Top edge leans toward the accent, the body stays near-black so lyrics
            // keep their contrast whatever the album art is.
            gradient[0] = AlbumPalette.withAlpha(
                    AlbumPalette.blend(deepNow, accentNow, config.themeTopAccentBlend), config.themeTopAlpha);
            gradient[1] = AlbumPalette.withAlpha(deepNow, config.themeMidAlpha);
            gradient[2] = AlbumPalette.withAlpha(
                    AlbumPalette.blend(deepNow, 0xFF000000, config.themeBottomBlackBlend), config.themeBottomAlpha);
            background.setColors(gradient);
            background.setStroke(Math.max(1, screen.px(config.windowStrokeDp)),
                    AlbumPalette.withAlpha(accentNow, config.themeStrokeAlpha));
        }
        sink.onAccent(ColorStateList.valueOf(accentNow), accentNow);
    }
}
