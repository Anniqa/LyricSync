package com.lyricsync.app.overlay;

/**
 * Pure-JVM layout math for the overlay windows, driven by {@link OverlayConfig}.
 * Every input is an explicit pixel / percent / density value (no android types),
 * so each rule is unit-tested and rotation simply means calling again with the new
 * screen size.
 *
 * Window coordinates follow the WindowManager gravities the service uses:
 *  - main window: TOP | CENTER_HORIZONTAL → x is an offset from the horizontal centre,
 *    y is from the top edge;
 *  - AI chat: CENTER → both axes are offsets from the centre;
 *  - AI bubble: TOP | START → x / y are from the top-left corner.
 */
final class OverlayGeometry {
    private OverlayGeometry() {}

    // ── Units ────────────────────────────────────────────────────────────────

    /** dp → px, rounded like {@code (int) (dp * density + 0.5f)}. */
    static int px(int dp, float density) {
        return (int) (dp * density + 0.5f);
    }

    static float spPx(float sp, float scaledDensity) {
        return sp * scaledDensity;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── Pref sanitising ──────────────────────────────────────────────────────

    static int widthPercent(OverlayConfig c, int pref) {
        return clamp(pref, c.widthPercentMin, c.widthPercentMax);
    }

    static int heightPercent(OverlayConfig c, int pref) {
        return clamp(pref, c.heightPercentMin, c.heightPercentMax);
    }

    static float fontScale(OverlayConfig c, float pref) {
        return clamp(pref, c.fontScaleMin, c.fontScaleMax);
    }

    static int syncOffset(OverlayConfig c, long ms) {
        long limit = c.syncOffsetLimitMs;
        return (int) Math.max(-limit, Math.min(limit, ms));
    }

    // ── Main window ──────────────────────────────────────────────────────────

    /** Lyrics font: screen-relative base × user scale × window-width factor. */
    static float fontSizeSp(OverlayConfig c, float screenWidthDp, float fontScalePref, int widthPercentPref) {
        float base = clamp(screenWidthDp * c.fontScreenWidthRatio, c.fontBaseMinSp, c.fontBaseMaxSp);
        float widthFactor = clamp(widthPercent(c, widthPercentPref) / (float) c.widthPercentDefault,
                c.fontWidthFactorMin, c.fontWidthFactorMax);
        return clamp(base * fontScale(c, fontScalePref) * widthFactor, c.fontMinSp, c.fontMaxSp);
    }

    /** Window width for a width%: floored at the minimum, never wider than the screen. */
    static int windowWidthPx(OverlayConfig c, int screenWidthPx, int widthPercentPref, float density) {
        int width = Math.max(px(c.windowMinWidthDp, density),
                screenWidthPx * widthPercent(c, widthPercentPref) / 100);
        return Math.min(width, Math.max(1, screenWidthPx));
    }

    /** Lyrics body height for a height%, floored at the minimum. */
    static int bodyHeightPx(OverlayConfig c, int screenHeightPx, int heightPercentPref, float density) {
        return Math.max(px(c.bodyMinHeightDp, density),
                screenHeightPx * heightPercent(c, heightPercentPref) / 100);
    }

    /** Clamp an offset from the screen centre so a {@code viewPx} span stays on screen. */
    static int clampCentered(int offset, int screenPx, int viewPx) {
        int max = Math.max(0, (screenPx - viewPx) / 2);
        return clamp(offset, -max, max);
    }

    /** Clamp a position from the screen's leading edge so a {@code viewPx} span stays on screen. */
    static int clampFromEdge(int pos, int screenPx, int viewPx) {
        return clamp(pos, 0, Math.max(0, screenPx - viewPx));
    }

    /** Where the active line sits in the body, as a fraction of its height (SpringScroller). */
    static float scrollAnchorRatio(OverlayConfig c, float fontPx, int bodyHeightPx, int widthPercent,
                                   float density) {
        float height = Math.max(px(c.bodyMinHeightDp, density), bodyHeightPx);
        float fontRatio = clamp(fontPx / height, c.anchorFontRatioMin, c.anchorFontRatioMax);
        float narrow = widthPercent < c.anchorNarrowWidthPercent ? c.anchorNarrowBoost : 0f;
        float tall = height > px(c.anchorTallBodyDp, density) ? -c.anchorTallPullUp : 0f;
        return c.anchorBase + fontRatio + narrow + tall;
    }

    // ── Header & body styling ────────────────────────────────────────────────

    private static float headerWidthFactor(OverlayConfig c, int widthPercent) {
        return clamp(widthPercent / (float) c.widthPercentDefault, c.headerWidthFactorMin, c.headerWidthFactorMax);
    }

    static int coverSizePx(OverlayConfig c, float fontSp, int widthPercent, float density, float scaledDensity) {
        float want = spPx(fontSp * c.coverFontRatio, scaledDensity) * headerWidthFactor(c, widthPercent);
        return Math.round(Math.max(px(c.coverMinDp, density), Math.min(px(c.coverMaxDp, density), want)));
    }

    static float titleSp(OverlayConfig c, float fontSp) {
        return clamp(fontSp * c.titleFontRatio, c.titleMinSp, c.titleMaxSp);
    }

    static float artistSp(OverlayConfig c, float fontSp) {
        return clamp(fontSp * c.artistFontRatio, c.artistMinSp, c.artistMaxSp);
    }

    static int lyricsPadVerticalPx(OverlayConfig c, float fontSp, float density, float scaledDensity) {
        return Math.max(px(c.lyricsPadVerticalMinDp, density),
                Math.round(spPx(fontSp * c.lyricsPadVerticalFontRatio, scaledDensity)));
    }

    static int lyricsPadHorizontalPx(OverlayConfig c, float fontSp, float density, float scaledDensity) {
        return Math.max(px(c.lyricsPadHorizontalMinDp, density),
                Math.round(spPx(fontSp * c.lyricsPadHorizontalFontRatio, scaledDensity)));
    }

    /** Top/bottom fade band scaled to the body so a short body keeps its outer lines readable. */
    static int fadeEdgePx(OverlayConfig c, int bodyHeightPx, float density) {
        return Math.max(px(c.fadeEdgeMinDp, density),
                Math.min(px(c.fadeEdgeMaxDp, density), Math.round(bodyHeightPx * c.fadeEdgeFraction)));
    }

    /** Gap below each lyric line: scales with the font so small text never overlaps the glow. */
    static int lineGapPx(OverlayConfig c, float fontSp, float density, float scaledDensity) {
        return Math.max(px(c.lineGapMinDp, density),
                Math.round(spPx(fontSp, scaledDensity) * c.lineGapFontRatio));
    }

    // ── Resize gesture ───────────────────────────────────────────────────────

    // The resize floors equal what the minimum width% / height% produce, so a release
    // never snaps the window back when the persisted percentage is re-read.

    /** Narrowest the resize handle may drag: the width the minimum width% would give. */
    static int resizeMinWidthPx(OverlayConfig c, int screenWidthPx, float density) {
        return windowWidthPx(c, screenWidthPx, c.widthPercentMin, density);
    }

    static int resizeMaxWidthPx(OverlayConfig c, int screenWidthPx, float density) {
        return Math.max(resizeMinWidthPx(c, screenWidthPx, density),
                (int) (screenWidthPx * c.resizeMaxWidthFraction));
    }

    static int resizeMinBodyPx(OverlayConfig c, int screenHeightPx, float density) {
        return bodyHeightPx(c, screenHeightPx, c.heightPercentMin, density);
    }

    static int resizeMaxBodyPx(OverlayConfig c, int screenHeightPx, float density) {
        return Math.max(resizeMinBodyPx(c, screenHeightPx, density),
                bodyHeightPx(c, screenHeightPx, c.heightPercentMax, density));
    }

    /** Width% to persist after a resize; never outside what the window can actually show. */
    static int widthPercentFromPx(OverlayConfig c, int widthPx, int screenWidthPx) {
        int pct = Math.round(widthPx * 100f / Math.max(1, screenWidthPx));
        int max = Math.min(c.widthPercentMax, Math.round(c.resizeMaxWidthFraction * 100f));
        return clamp(pct, c.widthPercentMin, Math.max(c.widthPercentMin, max));
    }

    static int heightPercentFromPx(OverlayConfig c, int bodyPx, int screenHeightPx) {
        int pct = Math.round(bodyPx * 100f / Math.max(1, screenHeightPx));
        return clamp(pct, c.heightPercentMin, c.heightPercentMax);
    }

    // ── AI chat window ───────────────────────────────────────────────────────

    static int chatWidthPx(OverlayConfig c, int screenWidthPx, float density) {
        return Math.min((int) (screenWidthPx * c.chatWidthFraction), px(c.chatMaxWidthDp, density));
    }

    /** Fixed chat height, capped so it still fits a landscape phone screen. */
    static int chatHeightPx(OverlayConfig c, int screenHeightPx, float density) {
        return Math.max(1, Math.min(px(c.chatHeightDp, density),
                (int) (screenHeightPx * c.chatMaxHeightFraction)));
    }

    // ── AI bubble ────────────────────────────────────────────────────────────

    static int bubbleInitialX(OverlayConfig c, int screenWidthPx, float density) {
        return screenWidthPx - px(c.bubbleSizeDp, density) - px(c.bubbleInitialEdgeMarginDp, density);
    }

    static int bubbleInitialY(int screenHeightPx) {
        return screenHeightPx / 2;
    }

    /** Snap a released bubble to the nearer side edge. */
    static int bubbleSnapX(OverlayConfig c, int x, int screenWidthPx, float density) {
        int size = px(c.bubbleSizeDp, density);
        int margin = px(c.bubbleSnapMarginDp, density);
        return (x + size / 2 < screenWidthPx / 2) ? margin : screenWidthPx - size - margin;
    }

    // ── Render loop ──────────────────────────────────────────────────────────

    /**
     * Seconds to step the springs this frame. Uses the real vsync gap, capped so a
     * stall cannot fling them; the first frame (or a non-advancing timestamp) uses
     * one display refresh period, so 60 / 90 / 120 / 144 Hz all start correctly.
     */
    static double frameDeltaSec(OverlayConfig c, long lastFrameNanos, long frameNanos, float refreshHz) {
        double nominal = 1.0 / (refreshHz > 1f ? refreshHz : c.fallbackRefreshHz);
        if (lastFrameNanos <= 0 || frameNanos <= lastFrameNanos) return Math.min(nominal, c.frameMaxDtSec);
        return Math.min((frameNanos - lastFrameNanos) / 1_000_000_000.0, c.frameMaxDtSec);
    }

    /** A jump of more than one line (seek) refreshes every line, not just the window. */
    static boolean isSeekJump(int activeIndex, int lastActiveIndex) {
        return Math.abs(activeIndex - lastActiveIndex) > 1;
    }

    /** First line the highlighter updates this frame (inclusive). */
    static int highlightStart(OverlayConfig c, int activeIndex, int lastActiveIndex) {
        return isSeekJump(activeIndex, lastActiveIndex) ? 0 : Math.max(0, activeIndex - c.highlightLinesBehind);
    }

    /**
     * Line after the last one the highlighter updates this frame (exclusive): the
     * active line, every line starting within the look-ahead, plus a small margin.
     * Allocation-free — it runs on every vsync.
     *
     * @param viewCount  rendered line views
     * @param lineCount  lyric lines (may briefly differ from viewCount mid-edit)
     * @param lineStart  start time (ms) of line i
     */
    static int highlightEnd(OverlayConfig c, int activeIndex, int lastActiveIndex, int viewCount,
                            int lineCount, java.util.function.IntToLongFunction lineStart, long positionMs) {
        if (isSeekJump(activeIndex, lastActiveIndex)) return viewCount;
        int limit = Math.min(viewCount, lineCount);
        int end = activeIndex;
        while (end < limit && lineStart.applyAsLong(end) - positionMs < c.highlightLookaheadMs) {
            end++;
        }
        return Math.min(viewCount, end + c.highlightLinesAhead);
    }
}
