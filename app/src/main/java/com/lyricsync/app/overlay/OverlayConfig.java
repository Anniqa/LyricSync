package com.lyricsync.app.overlay;

/**
 * Every tunable of the floating overlay screen — window sizing, gestures, the AI
 * chat / bubble windows, typography, lyrics layout, render loop, timings and the
 * album-tinted theme — in one immutable object with sane defaults.
 *
 * <pre>
 * OverlayConfig.Builder b = OverlayConfig.builder();
 * b.chatHeightDp = 420;
 * OverlayConfig config = b.build();   // validated
 * </pre>
 *
 * Units are in the field names: {@code Dp} = density-independent pixels,
 * {@code Sp} = scaled pixels, {@code Ms} = milliseconds, {@code Percent} = 0..100
 * of the screen, {@code Fraction}/{@code Ratio} = plain multipliers. The pref-backed
 * percentage ranges match the settings sliders in MainActivity, so a value saved
 * from one surface is applied unchanged by the other.
 *
 * Pure JVM: no android imports, so the geometry built on it is unit-tested.
 */
public final class OverlayConfig {

    /** The shipping configuration. */
    public static final OverlayConfig DEFAULT = builder().build();

    // ── Main window size (pref-backed percentages of the screen) ──────────────
    public final int widthPercentDefault;
    public final int widthPercentMin;
    public final int widthPercentMax;
    public final int heightPercentDefault;
    public final int heightPercentMin;
    public final int heightPercentMax;
    /** Floor for the window width, whatever the percentage says. */
    public final int windowMinWidthDp;
    /** Floor for the lyrics body height. */
    public final int bodyMinHeightDp;
    /** Widest the resize handle may drag the window, as a fraction of the screen width. */
    public final float resizeMaxWidthFraction;

    // ── Main window placement & gestures ─────────────────────────────────────
    /** First-run vertical offset from the top of the screen. */
    public final int windowDefaultYDp;
    /** Height assumed for screen clamping before the window has been measured. */
    public final int windowUnmeasuredHeightDp;
    public final int windowCornerRadiusDp;
    public final int windowStrokeDp;
    public final int dragSlopDp;
    public final int resizeSlopDp;

    // ── AI chat window ───────────────────────────────────────────────────────
    public final float chatWidthFraction;
    public final int chatMaxWidthDp;
    public final int chatHeightDp;
    /** Tallest the chat may be as a fraction of the screen height (landscape phones). */
    public final float chatMaxHeightFraction;
    public final int chatDragSlopDp;

    // ── AI bubble (minimised chat) ───────────────────────────────────────────
    public final int bubbleSizeDp;
    /** Distance from the right edge on first show. */
    public final int bubbleInitialEdgeMarginDp;
    /** Distance kept from the edge the bubble snaps to after a drag. */
    public final int bubbleSnapMarginDp;
    public final int bubbleDragSlopDp;

    // ── Lyrics typography ────────────────────────────────────────────────────
    /** Base font = screen width (dp) × this, clamped to [fontBaseMinSp, fontBaseMaxSp]. */
    public final float fontScreenWidthRatio;
    public final float fontBaseMinSp;
    public final float fontBaseMaxSp;
    public final float fontScaleDefault;
    public final float fontScaleMin;
    public final float fontScaleMax;
    /** Width factor = width% / widthPercentDefault, clamped to this range. */
    public final float fontWidthFactorMin;
    public final float fontWidthFactorMax;
    public final float fontMinSp;
    public final float fontMaxSp;
    /** Font-size changes smaller than this do not trigger a lyrics rebuild. */
    public final float fontRebuildThresholdSp;

    // ── Header ───────────────────────────────────────────────────────────────
    public final float headerWidthFactorMin;
    public final float headerWidthFactorMax;
    public final float coverFontRatio;
    public final int coverMinDp;
    public final int coverMaxDp;
    public final int coverCornerDp;
    public final float titleFontRatio;
    public final float titleMinSp;
    public final float titleMaxSp;
    public final float artistFontRatio;
    public final float artistMinSp;
    public final float artistMaxSp;

    // ── Lyrics body layout ───────────────────────────────────────────────────
    public final float lyricsPadVerticalFontRatio;
    public final int lyricsPadVerticalMinDp;
    public final float lyricsPadHorizontalFontRatio;
    public final int lyricsPadHorizontalMinDp;
    /** Top/bottom fade band as a fraction of the body height. */
    public final float fadeEdgeFraction;
    public final int fadeEdgeMinDp;
    public final int fadeEdgeMaxDp;
    public final float lineGapFontRatio;
    public final int lineGapMinDp;

    // ── Active-line scroll anchor (fraction of the body height) ──────────────
    public final float anchorBase;
    public final float anchorFontRatioMin;
    public final float anchorFontRatioMax;
    /** Below this width% the anchor moves down a little (narrow window). */
    public final int anchorNarrowWidthPercent;
    public final float anchorNarrowBoost;
    /** Above this body height the anchor moves up a little (tall window). */
    public final int anchorTallBodyDp;
    public final float anchorTallPullUp;

    // ── Render loop ──────────────────────────────────────────────────────────
    /** Longest frame step handed to the springs (a stall must not fling them). */
    public final double frameMaxDtSec;
    /** Refresh rate assumed before the display reports one. */
    public final float fallbackRefreshHz;
    /** Lines starting within this window ahead of the clock are highlighted early. */
    public final long highlightLookaheadMs;
    public final int highlightLinesBehind;
    public final int highlightLinesAhead;
    /**
     * After playback stops the loop keeps stepping this long, so word / letter
     * springs land at rest instead of freezing mid-overshoot on pause.
     */
    public final long pauseSettleMs;

    // ── Sync offset ──────────────────────────────────────────────────────────
    /** Slider spans ±this; its midpoint means "no offset". */
    public final int syncOffsetLimitMs;

    // ── AI chat messages ─────────────────────────────────────────────────────
    /** Message text size (user and AI bubbles). */
    public final float chatTextSp;
    /** Line-spacing multiplier of AI answers. */
    public final float chatAiLineSpacing;
    /** Side inset that keeps bubbles / chips off the far edge. */
    public final int chatBubbleInsetDp;
    public final int chatUserPadHorizontalDp;
    public final int chatUserPadVerticalDp;
    /** Vertical gap around a user bubble. */
    public final int chatMessageGapDp;
    public final int chatAiPadHorizontalDp;
    public final int chatAiTopGapDp;
    public final int chatAiBottomGapDp;
    /** Web-source chips and their label. */
    public final float chatChipTextSp;
    /** Most source chips appended below one answer. */
    public final int chatSourceChipMax;
    public final int chatSourceChipPadHorizontalDp;
    public final int chatSourceChipPadVerticalDp;
    public final int chatSourceChipGapDp;
    public final int chatWebChipPadHorizontalDp;
    public final int chatWebChipPadVerticalDp;
    public final int chatWebChipGapDp;
    public final int chatWebLabelGapDp;
    /** Inline tool-status card. */
    public final float chatToolTextSp;
    public final int chatToolPadHorizontalDp;
    public final int chatToolPadVerticalDp;
    public final int chatToolGapDp;
    public final int chatToolIconDp;
    public final int chatToolIconGapDp;
    /** Tint of a finished tool card. */
    public final int chatToolDoneColor;
    /** Web-search chip alpha while search is off. */
    public final float chatWebSearchOffAlpha;

    // ── Lyrics editing (AI tools) ───────────────────────────────────────────
    /** Lines sampled to decide whether lyrics are in a non-Latin script. */
    public final int scriptSampleLines;
    /** Duration given to a line inserted without an end time (AI line tools). */
    public final long editInsertLineDurationMs;
    /** Shortest a line may become after a timing shift. */
    public final long editMinLineDurationMs;
    /** Shortest a word may become after a timing shift. */
    public final long editMinWordDurationMs;

    // ── Timings ──────────────────────────────────────────────────────────────
    /** Coalesces bursts of pref changes (slider drags in the activity). */
    public final long settingsDebounceMs;
    public final int coverCrossfadeMs;
    public final long colorTransitionMs;
    public final long skeletonPulseMs;
    public final float skeletonAlphaLow;
    public final float skeletonAlphaHigh;
    /** Delay before re-reading play/pause state after a tap (player round-trip). */
    public final long playPauseRefreshDelayMs;
    public final long exitDurationMs;
    public final float exitScale;
    public final long toolStatusHideDelayMs;
    public final long toolStatusFadeMs;

    // ── Controls ─────────────────────────────────────────────────────────────
    public final float settingsOpenRotationDeg;
    public final float toggleCollapsedRotationDeg;
    public final float toggleCollapsedAlpha;
    /** Overshoot tension of the body expand (collapse stays un-bouncy). */
    public final float bodyExpandOvershoot;

    // ── Album-tinted theme ───────────────────────────────────────────────────
    /** How far the top of the background leans toward the album accent. */
    public final float themeTopAccentBlend;
    public final int themeTopAlpha;
    public final int themeMidAlpha;
    /** How far the bottom of the background darkens toward black. */
    public final float themeBottomBlackBlend;
    public final int themeBottomAlpha;
    public final int themeStrokeAlpha;

    private OverlayConfig(Builder b) {
        widthPercentDefault = b.widthPercentDefault;
        widthPercentMin = b.widthPercentMin;
        widthPercentMax = b.widthPercentMax;
        heightPercentDefault = b.heightPercentDefault;
        heightPercentMin = b.heightPercentMin;
        heightPercentMax = b.heightPercentMax;
        windowMinWidthDp = b.windowMinWidthDp;
        bodyMinHeightDp = b.bodyMinHeightDp;
        resizeMaxWidthFraction = b.resizeMaxWidthFraction;
        windowDefaultYDp = b.windowDefaultYDp;
        windowUnmeasuredHeightDp = b.windowUnmeasuredHeightDp;
        windowCornerRadiusDp = b.windowCornerRadiusDp;
        windowStrokeDp = b.windowStrokeDp;
        dragSlopDp = b.dragSlopDp;
        resizeSlopDp = b.resizeSlopDp;
        chatWidthFraction = b.chatWidthFraction;
        chatMaxWidthDp = b.chatMaxWidthDp;
        chatHeightDp = b.chatHeightDp;
        chatMaxHeightFraction = b.chatMaxHeightFraction;
        chatDragSlopDp = b.chatDragSlopDp;
        bubbleSizeDp = b.bubbleSizeDp;
        bubbleInitialEdgeMarginDp = b.bubbleInitialEdgeMarginDp;
        bubbleSnapMarginDp = b.bubbleSnapMarginDp;
        bubbleDragSlopDp = b.bubbleDragSlopDp;
        fontScreenWidthRatio = b.fontScreenWidthRatio;
        fontBaseMinSp = b.fontBaseMinSp;
        fontBaseMaxSp = b.fontBaseMaxSp;
        fontScaleDefault = b.fontScaleDefault;
        fontScaleMin = b.fontScaleMin;
        fontScaleMax = b.fontScaleMax;
        fontWidthFactorMin = b.fontWidthFactorMin;
        fontWidthFactorMax = b.fontWidthFactorMax;
        fontMinSp = b.fontMinSp;
        fontMaxSp = b.fontMaxSp;
        fontRebuildThresholdSp = b.fontRebuildThresholdSp;
        headerWidthFactorMin = b.headerWidthFactorMin;
        headerWidthFactorMax = b.headerWidthFactorMax;
        coverFontRatio = b.coverFontRatio;
        coverMinDp = b.coverMinDp;
        coverMaxDp = b.coverMaxDp;
        coverCornerDp = b.coverCornerDp;
        titleFontRatio = b.titleFontRatio;
        titleMinSp = b.titleMinSp;
        titleMaxSp = b.titleMaxSp;
        artistFontRatio = b.artistFontRatio;
        artistMinSp = b.artistMinSp;
        artistMaxSp = b.artistMaxSp;
        lyricsPadVerticalFontRatio = b.lyricsPadVerticalFontRatio;
        lyricsPadVerticalMinDp = b.lyricsPadVerticalMinDp;
        lyricsPadHorizontalFontRatio = b.lyricsPadHorizontalFontRatio;
        lyricsPadHorizontalMinDp = b.lyricsPadHorizontalMinDp;
        fadeEdgeFraction = b.fadeEdgeFraction;
        fadeEdgeMinDp = b.fadeEdgeMinDp;
        fadeEdgeMaxDp = b.fadeEdgeMaxDp;
        lineGapFontRatio = b.lineGapFontRatio;
        lineGapMinDp = b.lineGapMinDp;
        anchorBase = b.anchorBase;
        anchorFontRatioMin = b.anchorFontRatioMin;
        anchorFontRatioMax = b.anchorFontRatioMax;
        anchorNarrowWidthPercent = b.anchorNarrowWidthPercent;
        anchorNarrowBoost = b.anchorNarrowBoost;
        anchorTallBodyDp = b.anchorTallBodyDp;
        anchorTallPullUp = b.anchorTallPullUp;
        frameMaxDtSec = b.frameMaxDtSec;
        fallbackRefreshHz = b.fallbackRefreshHz;
        highlightLookaheadMs = b.highlightLookaheadMs;
        highlightLinesBehind = b.highlightLinesBehind;
        highlightLinesAhead = b.highlightLinesAhead;
        pauseSettleMs = b.pauseSettleMs;
        syncOffsetLimitMs = b.syncOffsetLimitMs;
        chatTextSp = b.chatTextSp;
        chatAiLineSpacing = b.chatAiLineSpacing;
        chatBubbleInsetDp = b.chatBubbleInsetDp;
        chatUserPadHorizontalDp = b.chatUserPadHorizontalDp;
        chatUserPadVerticalDp = b.chatUserPadVerticalDp;
        chatMessageGapDp = b.chatMessageGapDp;
        chatAiPadHorizontalDp = b.chatAiPadHorizontalDp;
        chatAiTopGapDp = b.chatAiTopGapDp;
        chatAiBottomGapDp = b.chatAiBottomGapDp;
        chatChipTextSp = b.chatChipTextSp;
        chatSourceChipMax = b.chatSourceChipMax;
        chatSourceChipPadHorizontalDp = b.chatSourceChipPadHorizontalDp;
        chatSourceChipPadVerticalDp = b.chatSourceChipPadVerticalDp;
        chatSourceChipGapDp = b.chatSourceChipGapDp;
        chatWebChipPadHorizontalDp = b.chatWebChipPadHorizontalDp;
        chatWebChipPadVerticalDp = b.chatWebChipPadVerticalDp;
        chatWebChipGapDp = b.chatWebChipGapDp;
        chatWebLabelGapDp = b.chatWebLabelGapDp;
        chatToolTextSp = b.chatToolTextSp;
        chatToolPadHorizontalDp = b.chatToolPadHorizontalDp;
        chatToolPadVerticalDp = b.chatToolPadVerticalDp;
        chatToolGapDp = b.chatToolGapDp;
        chatToolIconDp = b.chatToolIconDp;
        chatToolIconGapDp = b.chatToolIconGapDp;
        chatToolDoneColor = b.chatToolDoneColor;
        chatWebSearchOffAlpha = b.chatWebSearchOffAlpha;
        scriptSampleLines = b.scriptSampleLines;
        editInsertLineDurationMs = b.editInsertLineDurationMs;
        editMinLineDurationMs = b.editMinLineDurationMs;
        editMinWordDurationMs = b.editMinWordDurationMs;
        settingsDebounceMs = b.settingsDebounceMs;
        coverCrossfadeMs = b.coverCrossfadeMs;
        colorTransitionMs = b.colorTransitionMs;
        skeletonPulseMs = b.skeletonPulseMs;
        skeletonAlphaLow = b.skeletonAlphaLow;
        skeletonAlphaHigh = b.skeletonAlphaHigh;
        playPauseRefreshDelayMs = b.playPauseRefreshDelayMs;
        exitDurationMs = b.exitDurationMs;
        exitScale = b.exitScale;
        toolStatusHideDelayMs = b.toolStatusHideDelayMs;
        toolStatusFadeMs = b.toolStatusFadeMs;
        settingsOpenRotationDeg = b.settingsOpenRotationDeg;
        toggleCollapsedRotationDeg = b.toggleCollapsedRotationDeg;
        toggleCollapsedAlpha = b.toggleCollapsedAlpha;
        bodyExpandOvershoot = b.bodyExpandOvershoot;
        themeTopAccentBlend = b.themeTopAccentBlend;
        themeTopAlpha = b.themeTopAlpha;
        themeMidAlpha = b.themeMidAlpha;
        themeBottomBlackBlend = b.themeBottomBlackBlend;
        themeBottomAlpha = b.themeBottomAlpha;
        themeStrokeAlpha = b.themeStrokeAlpha;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable defaults; {@link #build()} validates and freezes them. */
    public static final class Builder {
        public int widthPercentDefault = 88;
        public int widthPercentMin = 55;
        public int widthPercentMax = 100;
        public int heightPercentDefault = 36;
        public int heightPercentMin = 20;
        public int heightPercentMax = 70;
        public int windowMinWidthDp = 220;
        public int bodyMinHeightDp = 140;
        public float resizeMaxWidthFraction = 0.96f;

        public int windowDefaultYDp = 64;
        public int windowUnmeasuredHeightDp = 200;
        public int windowCornerRadiusDp = 28;
        public int windowStrokeDp = 1;
        public int dragSlopDp = 10;
        public int resizeSlopDp = 6;

        public float chatWidthFraction = 0.92f;
        public int chatMaxWidthDp = 440;
        public int chatHeightDp = 460;
        public float chatMaxHeightFraction = 0.90f;
        public int chatDragSlopDp = 6;

        public int bubbleSizeDp = 50;
        public int bubbleInitialEdgeMarginDp = 16;
        public int bubbleSnapMarginDp = 8;
        public int bubbleDragSlopDp = 6;

        public float fontScreenWidthRatio = 0.038f;
        public float fontBaseMinSp = 11f;
        public float fontBaseMaxSp = 22f;
        public float fontScaleDefault = 1.0f;
        public float fontScaleMin = 0.5f;
        public float fontScaleMax = 2.0f;
        public float fontWidthFactorMin = 0.70f;
        public float fontWidthFactorMax = 1.15f;
        public float fontMinSp = 9f;
        public float fontMaxSp = 24f;
        public float fontRebuildThresholdSp = 0.05f;

        public float headerWidthFactorMin = 0.78f;
        public float headerWidthFactorMax = 1.08f;
        public float coverFontRatio = 2.65f;
        public int coverMinDp = 28;
        public int coverMaxDp = 54;
        public int coverCornerDp = 10;
        public float titleFontRatio = 0.92f;
        public float titleMinSp = 11f;
        public float titleMaxSp = 18f;
        public float artistFontRatio = 0.78f;
        public float artistMinSp = 9f;
        public float artistMaxSp = 15f;

        public float lyricsPadVerticalFontRatio = 1.2f;
        public int lyricsPadVerticalMinDp = 14;
        public float lyricsPadHorizontalFontRatio = 0.35f;
        public int lyricsPadHorizontalMinDp = 6;
        public float fadeEdgeFraction = 0.14f;
        public int fadeEdgeMinDp = 10;
        public int fadeEdgeMaxDp = 36;
        public float lineGapFontRatio = 0.30f;
        public int lineGapMinDp = 3;

        public float anchorBase = 0.34f;
        public float anchorFontRatioMin = 0.04f;
        public float anchorFontRatioMax = 0.18f;
        public int anchorNarrowWidthPercent = 75;
        public float anchorNarrowBoost = 0.05f;
        public int anchorTallBodyDp = 320;
        public float anchorTallPullUp = 0.04f;

        public double frameMaxDtSec = 0.1;
        public float fallbackRefreshHz = 60f;
        public long highlightLookaheadMs = 3000;
        public int highlightLinesBehind = 2;
        public int highlightLinesAhead = 2;
        public long pauseSettleMs = 800;

        public int syncOffsetLimitMs = 1500;

        public float chatTextSp = 12f;
        public float chatAiLineSpacing = 1.35f;
        public int chatBubbleInsetDp = 32;
        public int chatUserPadHorizontalDp = 12;
        public int chatUserPadVerticalDp = 8;
        public int chatMessageGapDp = 4;
        public int chatAiPadHorizontalDp = 2;
        public int chatAiTopGapDp = 2;
        public int chatAiBottomGapDp = 12;
        public float chatChipTextSp = 10f;
        public int chatSourceChipMax = 5;
        public int chatSourceChipPadHorizontalDp = 10;
        public int chatSourceChipPadVerticalDp = 4;
        public int chatSourceChipGapDp = 2;
        public int chatWebChipPadHorizontalDp = 8;
        public int chatWebChipPadVerticalDp = 3;
        public int chatWebChipGapDp = 6;
        public int chatWebLabelGapDp = 4;
        public float chatToolTextSp = 11f;
        public int chatToolPadHorizontalDp = 10;
        public int chatToolPadVerticalDp = 5;
        public int chatToolGapDp = 3;
        public int chatToolIconDp = 13;
        public int chatToolIconGapDp = 6;
        public int chatToolDoneColor = 0xFF81C784;
        public float chatWebSearchOffAlpha = 0.5f;

        public int scriptSampleLines = 25;
        public long editInsertLineDurationMs = 3000;
        public long editMinLineDurationMs = 500;
        public long editMinWordDurationMs = 100;

        public long settingsDebounceMs = 100;
        public int coverCrossfadeMs = 220;
        public long colorTransitionMs = 650;
        public long skeletonPulseMs = 760;
        public float skeletonAlphaLow = 0.35f;
        public float skeletonAlphaHigh = 0.9f;
        public long playPauseRefreshDelayMs = 180;
        public long exitDurationMs = 200;
        public float exitScale = 0.9f;
        public long toolStatusHideDelayMs = 1200;
        public long toolStatusFadeMs = 220;

        public float settingsOpenRotationDeg = 90f;
        public float toggleCollapsedRotationDeg = 180f;
        public float toggleCollapsedAlpha = 0.65f;
        public float bodyExpandOvershoot = 0.8f;

        public float themeTopAccentBlend = 0.18f;
        public int themeTopAlpha = 0xF5;
        public int themeMidAlpha = 0xF0;
        public float themeBottomBlackBlend = 0.45f;
        public int themeBottomAlpha = 0xF7;
        public int themeStrokeAlpha = 0x4D;

        private Builder() {}

        /** @throws IllegalArgumentException when a value cannot produce a usable overlay. */
        public OverlayConfig build() {
            range("widthPercent", widthPercentMin, widthPercentDefault, widthPercentMax, 1, 100);
            range("heightPercent", heightPercentMin, heightPercentDefault, heightPercentMax, 1, 100);
            range("fontScale", fontScaleMin, fontScaleDefault, fontScaleMax);
            range("fontBase", fontBaseMinSp, fontBaseMinSp, fontBaseMaxSp);
            range("font", fontMinSp, fontMinSp, fontMaxSp);
            range("fontWidthFactor", fontWidthFactorMin, fontWidthFactorMin, fontWidthFactorMax);
            range("headerWidthFactor", headerWidthFactorMin, headerWidthFactorMin, headerWidthFactorMax);
            range("cover", coverMinDp, coverMinDp, coverMaxDp, 1, Integer.MAX_VALUE);
            range("title", titleMinSp, titleMinSp, titleMaxSp);
            range("artist", artistMinSp, artistMinSp, artistMaxSp);
            range("fadeEdge", fadeEdgeMinDp, fadeEdgeMinDp, fadeEdgeMaxDp, 0, Integer.MAX_VALUE);
            range("anchorFontRatio", anchorFontRatioMin, anchorFontRatioMin, anchorFontRatioMax);
            range("skeletonAlpha", skeletonAlphaLow, skeletonAlphaLow, skeletonAlphaHigh);
            positive("windowMinWidthDp", windowMinWidthDp);
            positive("bodyMinHeightDp", bodyMinHeightDp);
            positive("chatMaxWidthDp", chatMaxWidthDp);
            positive("chatHeightDp", chatHeightDp);
            positive("bubbleSizeDp", bubbleSizeDp);
            positive("syncOffsetLimitMs", syncOffsetLimitMs);
            positive("fallbackRefreshHz", fallbackRefreshHz);
            positive("frameMaxDtSec", frameMaxDtSec);
            fraction("resizeMaxWidthFraction", resizeMaxWidthFraction);
            fraction("chatWidthFraction", chatWidthFraction);
            fraction("chatMaxHeightFraction", chatMaxHeightFraction);
            fraction("fadeEdgeFraction", fadeEdgeFraction);
            nonNegative("highlightLinesBehind", highlightLinesBehind);
            nonNegative("highlightLinesAhead", highlightLinesAhead);
            nonNegative("highlightLookaheadMs", highlightLookaheadMs);
            nonNegative("pauseSettleMs", pauseSettleMs);
            alpha("themeTopAlpha", themeTopAlpha);
            alpha("themeMidAlpha", themeMidAlpha);
            alpha("themeBottomAlpha", themeBottomAlpha);
            alpha("themeStrokeAlpha", themeStrokeAlpha);
            positive("scriptSampleLines", scriptSampleLines);
            positive("editInsertLineDurationMs", editInsertLineDurationMs);
            nonNegative("editMinLineDurationMs", editMinLineDurationMs);
            nonNegative("editMinWordDurationMs", editMinWordDurationMs);
            positive("chatTextSp", chatTextSp);
            positive("chatChipTextSp", chatChipTextSp);
            positive("chatToolTextSp", chatToolTextSp);
            nonNegative("chatSourceChipMax", chatSourceChipMax);
            fraction("chatWebSearchOffAlpha", chatWebSearchOffAlpha);
            return new OverlayConfig(this);
        }

        private static void range(String name, int min, int def, int max, int lo, int hi) {
            if (min < lo || max > hi || min > def || def > max) {
                throw new IllegalArgumentException(name + ": need " + lo + " <= min <= default <= max <= "
                        + hi + ", got " + min + "/" + def + "/" + max);
            }
        }

        private static void range(String name, float min, float def, float max) {
            if (!(min > 0f) || min > def || def > max) {
                throw new IllegalArgumentException(name + ": need 0 < min <= default <= max, got "
                        + min + "/" + def + "/" + max);
            }
        }

        private static void positive(String name, double v) {
            if (!(v > 0)) throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }

        private static void nonNegative(String name, double v) {
            if (v < 0) throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }

        private static void fraction(String name, float v) {
            if (!(v > 0f && v <= 1f)) {
                throw new IllegalArgumentException(name + " must be in (0, 1], got " + v);
            }
        }

        private static void alpha(String name, int v) {
            if (v < 0 || v > 0xFF) throw new IllegalArgumentException(name + " must be 0..255, got " + v);
        }
    }
}
