package com.lyricsync.app.ui;

import android.graphics.Bitmap;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

/**
 * Derives a usable accent from album art.
 *
 * Raw Palette swatches are unreliable for UI: they can come back nearly black, nearly
 * white, or so desaturated the overlay looks broken. Every colour that leaves this class
 * is normalised into a saturation/lightness band that stays legible on the dark overlay.
 */
public final class AlbumPalette {
    private AlbumPalette() {}

    /** Fallback matches the static app accent so a missing/failed palette is invisible. */
    public static final int DEFAULT_ACCENT = 0xFF1ED760;
    public static final int DEFAULT_DEEP = 0xFF12131A;

    public interface Callback {
        /**
         * @param accent bright colour for glow, sliders and highlights
         * @param deep   very dark tinted colour for overlay/hero backgrounds
         */
        void onPalette(int accent, int deep);
    }

    public static void from(Bitmap bitmap, final Callback callback) {
        if (callback == null) return;
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onPalette(DEFAULT_ACCENT, DEFAULT_DEEP);
            return;
        }
        try {
            Palette.from(bitmap)
                    .clearFilters()
                    .maximumColorCount(24)
                    .generate(palette -> {
                        if (palette == null) {
                            callback.onPalette(DEFAULT_ACCENT, DEFAULT_DEEP);
                            return;
                        }
                        int raw = pickSwatch(palette);
                        callback.onPalette(brighten(raw), deepen(raw));
                    });
        } catch (Exception e) {
            // Palette can throw on odd bitmap configs (e.g. hardware bitmaps).
            callback.onPalette(DEFAULT_ACCENT, DEFAULT_DEEP);
        }
    }

    private static int pickSwatch(Palette palette) {
        Palette.Swatch s = palette.getVibrantSwatch();
        if (s == null) s = palette.getLightVibrantSwatch();
        if (s == null) s = palette.getDarkVibrantSwatch();
        if (s == null) s = palette.getMutedSwatch();
        if (s == null) s = palette.getLightMutedSwatch();
        if (s == null) s = palette.getDominantSwatch();
        return s == null ? DEFAULT_ACCENT : s.getRgb();
    }

    /** Push a swatch into the "reads as an accent on black" band. */
    public static int brighten(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        // Grey art would otherwise produce a lifeless accent — force some chroma.
        hsl[1] = clamp(hsl[1] * 1.3f, 0.45f, 1f);
        hsl[2] = clamp(hsl[2], 0.56f, 0.74f);
        return ColorUtils.HSLToColor(hsl);
    }

    /** Same hue, dropped to a near-black tint suitable for a background wash. */
    public static int deepen(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = clamp(hsl[1] * 0.8f, 0f, 0.5f);
        hsl[2] = 0.085f;
        return ColorUtils.HSLToColor(hsl);
    }

    /** Same colour at a given alpha, for glows and soft fills. */
    public static int withAlpha(int color, int alpha) {
        return (clampInt(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    /** Mix {@code from} toward {@code to}; ratio 0 keeps from, 1 gives to. */
    public static int blend(int from, int to, float ratio) {
        return ColorUtils.blendARGB(from, to, clamp(ratio, 0f, 1f));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
