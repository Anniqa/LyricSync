package com.lyricsync.app.renderer;

/**
 * Pure-JVM geometry for the effect overlays: where the karaoke fill actually ends on
 * each render path, and where an overlay that has to stay on the sung side of that
 * edge is allowed to draw.
 *
 * The renderer has three fill paths, so the raw sweep value is NOT the visible edge
 * for all of them:
 *  - sweep motions (Spring, Calm, Wave, Drop, ...) fill continuously up to the sweep;
 *  - TYPEWRITER strikes letter by letter, so the fill ends at the struck run;
 *  - CLASSIC flips the whole word the instant it starts being sung.
 * Overlay geometry that assumed the sweep made effects miss the motion they were
 * combined with (Shimmer rode ahead of the edge and was clipped away, Sparkle could
 * twinkle over unfilled letters), so the mapping lives here — pinned by a JVM test —
 * instead of being open-coded per overlay.
 */
final class EffectLayout {

    /** Half-width of the Shimmer band as a fraction of the word width. */
    static final float SHIMMER_HALF_WIDTH = 0.06f;

    private EffectLayout() {}

    /**
     * Visible fill edge as a fraction (0..1) of the word width.
     *
     * @param progress       word progress (0..1) — decides the CLASSIC whole-word flip
     * @param p              sweep position in the word gradient's -0.20..1.0 space
     * @param classic        the CLASSIC motion is active (whole-word flip, no sweep)
     * @param binaryLetters  the active fill is a TYPEWRITER-style binary strike
     * @param struckFraction width of the struck-letter run over the word width
     */
    static float fillEdge(float progress, float p, boolean classic, boolean binaryLetters,
                          float struckFraction) {
        if (binaryLetters) return clamp01(struckFraction);
        if (classic) return progress > 0f ? 1f : 0f;
        return clamp01(p);
    }

    /**
     * Shimmer band centre as a fraction of the word width.
     *
     * @param progress word progress (0..1) — in CLASSIC mode, sweeps the band across
     *                 the word over progress (0.05..0.98)
     * @param edge     visible fill edge (0..1)
     * @param classic  true if CLASSIC motion is active
     */
    static float shimmerCenter(float progress, float edge, boolean classic) {
        if (classic) {
            float band = (progress - 0.05f) / 0.93f;
            return clamp01(band);
        }
        return Math.max(0f, clamp01(edge) - SHIMMER_HALF_WIDTH);
    }

    /**
     * Backward-compatible overload for sweep / binary modes trailing the fill edge.
     */
    static float shimmerCenter(float edge) {
        return shimmerCenter(0f, edge, false);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
