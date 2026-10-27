package com.lyricsync.app.overlay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure-JVM checks for the overlay layout math: the defaults reproduce the shipped
 * geometry exactly (behaviour preserved), and every rule holds on any screen size,
 * orientation and refresh rate.
 */
public class OverlayGeometryTest {

    private static final OverlayConfig C = OverlayConfig.DEFAULT;
    private static final float EPS = 1e-4f;

    /** Typical phones (portrait + landscape) and a tablet, with their densities. */
    private static final int[][] SCREENS = {
            {720, 1600}, {1600, 720},      // HD+, 2.0x
            {1080, 2400}, {2400, 1080},    // FHD+, 2.75x
            {1440, 3200}, {3200, 1440},    // QHD+, 3.5x
            {1600, 2560}, {2560, 1600},    // tablet, 2.0x
            {480, 800},                    // tiny, 1.5x
    };
    private static final float[] DENSITIES = {2.0f, 2.0f, 2.75f, 2.75f, 3.5f, 3.5f, 2.0f, 2.0f, 1.5f};

    // ── Defaults == the hard-coded values they replaced ──────────────────────

    @Test
    public void defaultsMatchShippedValuesAndActivitySliders() {
        assertEquals(88, C.widthPercentDefault);
        assertEquals(55, C.widthPercentMin);       // MainActivity width slider: 55..100
        assertEquals(100, C.widthPercentMax);
        assertEquals(36, C.heightPercentDefault);
        assertEquals(20, C.heightPercentMin);      // MainActivity height slider: 20..70
        assertEquals(70, C.heightPercentMax);
        assertEquals(0.5f, C.fontScaleMin, 0f);    // MainActivity font slider: 50..200%
        assertEquals(2.0f, C.fontScaleMax, 0f);
        assertEquals(1500, C.syncOffsetLimitMs);   // slider 0..3000, mid = no offset
        assertEquals(220, C.windowMinWidthDp);
        assertEquals(140, C.bodyMinHeightDp);
        assertEquals(64, C.windowDefaultYDp);
        assertEquals(50, C.bubbleSizeDp);
        assertEquals(460, C.chatHeightDp);
        assertEquals(440, C.chatMaxWidthDp);
    }

    @Test
    public void fontSizeMatchesTheOriginalFormula() {
        for (float widthDp = 200; widthDp <= 1400; widthDp += 37) {
            for (float scale : new float[]{0.3f, 0.5f, 1f, 1.7f, 2f, 3f}) {
                for (int pct : new int[]{30, 45, 55, 88, 100, 140}) {
                    float base = Math.max(11f, Math.min(22f, widthDp * 0.038f));
                    float s = Math.max(0.5f, Math.min(2.0f, scale));
                    int w = Math.max(55, Math.min(100, pct));
                    float wf = Math.max(0.70f, Math.min(1.15f, w / 88f));
                    float expected = Math.max(9f, Math.min(24f, base * s * wf));
                    assertEquals(expected, OverlayGeometry.fontSizeSp(C, widthDp, scale, pct), EPS);
                }
            }
        }
    }

    @Test
    public void scrollAnchorMatchesTheOriginalFormula() {
        float d = 2.75f;
        for (int body = 100; body < 2000; body += 53) {
            for (float fontPx : new float[]{20f, 40f, 66f}) {
                for (int pct : new int[]{55, 74, 75, 88}) {
                    float h = Math.max(OverlayGeometry.px(140, d), body);
                    float ratio = Math.max(0.04f, Math.min(0.18f, fontPx / h));
                    float expected = 0.34f + ratio + (pct < 75 ? 0.05f : 0f)
                            + (h > OverlayGeometry.px(320, d) ? -0.04f : 0f);
                    assertEquals(expected, OverlayGeometry.scrollAnchorRatio(C, fontPx, body, pct, d), EPS);
                }
            }
        }
    }

    @Test
    public void headerAndBodyStylingMatchTheOriginalFormulas() {
        float d = 2.75f;
        float sd = 2.75f;
        for (float font = 9f; font <= 24f; font += 0.5f) {
            assertEquals(Math.max(11f, Math.min(18f, font * 0.92f)), OverlayGeometry.titleSp(C, font), EPS);
            assertEquals(Math.max(9f, Math.min(15f, font * 0.78f)), OverlayGeometry.artistSp(C, font), EPS);
            assertEquals(Math.max(OverlayGeometry.px(14, d), Math.round(font * 1.2f * sd)),
                    OverlayGeometry.lyricsPadVerticalPx(C, font, d, sd));
            assertEquals(Math.max(OverlayGeometry.px(6, d), Math.round(font * 0.35f * sd)),
                    OverlayGeometry.lyricsPadHorizontalPx(C, font, d, sd));
            assertEquals(Math.max(OverlayGeometry.px(3, d), Math.round(font * sd * 0.30f)),
                    OverlayGeometry.lineGapPx(C, font, d, sd));
            for (int pct : new int[]{55, 88, 100}) {
                float wf = Math.max(0.78f, Math.min(1.08f, pct / 88f));
                int expected = Math.round(Math.max(OverlayGeometry.px(28, d),
                        Math.min(OverlayGeometry.px(54, d), font * 2.65f * sd * wf)));
                assertEquals(expected, OverlayGeometry.coverSizePx(C, font, pct, d, sd));
            }
        }
        for (int body = 0; body < 3000; body += 61) {
            int expected = Math.max(OverlayGeometry.px(10, d),
                    Math.min(OverlayGeometry.px(36, d), Math.round(body * 0.14f)));
            assertEquals(expected, OverlayGeometry.fadeEdgePx(C, body, d));
        }
    }

    // ── Any screen size / orientation ────────────────────────────────────────

    @Test
    public void windowAlwaysFitsTheScreen() {
        for (int s = 0; s < SCREENS.length; s++) {
            int w = SCREENS[s][0];
            int h = SCREENS[s][1];
            float d = DENSITIES[s];
            for (int pct = 0; pct <= 150; pct += 5) {
                int width = OverlayGeometry.windowWidthPx(C, w, pct, d);
                assertTrue("window fits " + w + "x" + h + " @" + pct, width > 0 && width <= w);
                int body = OverlayGeometry.bodyHeightPx(C, h, pct, d);
                assertTrue("body has a floor", body >= OverlayGeometry.px(C.bodyMinHeightDp, d));
            }
            int chatW = OverlayGeometry.chatWidthPx(C, w, d);
            int chatH = OverlayGeometry.chatHeightPx(C, h, d);
            assertTrue("chat fits width " + w, chatW > 0 && chatW <= w);
            assertTrue("chat fits height " + h, chatH > 0 && chatH <= h);
            int bx = OverlayGeometry.bubbleInitialX(C, w, d);
            int size = OverlayGeometry.px(C.bubbleSizeDp, d);
            assertTrue("bubble starts on screen", bx >= 0 && bx + size <= w);
        }
    }

    @Test
    public void chatKeepsItsDesignedHeightWhenThereIsRoom() {
        assertEquals(OverlayGeometry.px(460, 2.75f), OverlayGeometry.chatHeightPx(C, 2400, 2.75f));
        assertTrue("landscape phone caps the chat",
                OverlayGeometry.chatHeightPx(C, 1080, 2.75f) < OverlayGeometry.px(460, 2.75f));
    }

    @Test
    public void clampingKeepsWindowsOnScreenAfterRotation() {
        // A window parked bottom-right in portrait, then the phone turns to landscape.
        int view = 900;
        int x = OverlayGeometry.clampCentered(5000, 1080, view);
        assertEquals((1080 - view) / 2, x);
        assertEquals(-(2400 - view) / 2, OverlayGeometry.clampCentered(-99999, 2400, view));
        assertEquals(0, OverlayGeometry.clampCentered(123, 500, 900)); // wider than screen
        assertEquals(1080 - 300, OverlayGeometry.clampFromEdge(2000, 1080, 300));
        assertEquals(0, OverlayGeometry.clampFromEdge(-40, 1080, 300));
        assertEquals(0, OverlayGeometry.clampFromEdge(50, 200, 300));
    }

    @Test
    public void bubbleSnapsToTheNearerEdge() {
        float d = 2.75f;
        int size = OverlayGeometry.px(C.bubbleSizeDp, d);
        int margin = OverlayGeometry.px(C.bubbleSnapMarginDp, d);
        assertEquals(margin, OverlayGeometry.bubbleSnapX(C, 100, 1080, d));
        assertEquals(1080 - size - margin, OverlayGeometry.bubbleSnapX(C, 900, 1080, d));
    }

    @Test
    public void resizeLimitsRoundTripThroughThePersistedPercentages() {
        for (int s = 0; s < SCREENS.length; s++) {
            int w = SCREENS[s][0];
            int h = SCREENS[s][1];
            float d = DENSITIES[s];
            int minW = OverlayGeometry.resizeMinWidthPx(C, w, d);
            int maxW = OverlayGeometry.resizeMaxWidthPx(C, w, d);
            assertTrue(minW <= maxW && maxW <= w);
            for (int px = minW; px <= maxW; px += 7) {
                int pct = OverlayGeometry.widthPercentFromPx(C, px, w);
                assertTrue(pct >= C.widthPercentMin && pct <= C.widthPercentMax);
                // Re-reading the saved % must land within 1% of the released width.
                int back = OverlayGeometry.windowWidthPx(C, w, pct, d);
                assertTrue("width round trip " + w + ": " + px + " -> " + pct + "% -> " + back,
                        Math.abs(back - Math.max(px, minW)) <= w / 100 + 1
                                || back == OverlayGeometry.px(C.windowMinWidthDp, d));
            }
            int minB = OverlayGeometry.resizeMinBodyPx(C, h, d);
            int maxB = OverlayGeometry.resizeMaxBodyPx(C, h, d);
            assertTrue(minB <= maxB);
            for (int px = minB; px <= maxB; px += 11) {
                int pct = OverlayGeometry.heightPercentFromPx(C, px, h);
                int back = OverlayGeometry.bodyHeightPx(C, h, pct, d);
                assertTrue("body round trip " + h + ": " + px + " -> " + back,
                        Math.abs(back - px) <= h / 100 + 1 || back == OverlayGeometry.px(C.bodyMinHeightDp, d));
            }
        }
    }

    // ── Refresh-rate independence ────────────────────────────────────────────

    @Test
    public void frameDeltaFollowsTheRealVsyncAtAnyRefreshRate() {
        for (float hz : new float[]{30f, 60f, 90f, 120f, 144f, 165f}) {
            long period = (long) (1_000_000_000L / hz);
            double total = 0;
            long t = 5_000_000_000L;
            long last = 0;
            int frames = Math.round(hz); // one second of frames
            for (int i = 0; i < frames; i++) {
                total += OverlayGeometry.frameDeltaSec(C, last, t, hz);
                last = t;
                t += period;
            }
            // One wall-clock second of animation regardless of refresh rate.
            assertEquals("hz=" + hz, 1.0, total, 0.02);
            assertEquals("first frame is one refresh period", 1.0 / hz,
                    OverlayGeometry.frameDeltaSec(C, 0, t, hz), 1e-9);
        }
    }

    @Test
    public void frameDeltaIsCappedAndNeverNegative() {
        assertEquals(C.frameMaxDtSec, OverlayGeometry.frameDeltaSec(C, 1, 5_000_000_000L, 60f), 1e-9);
        assertEquals(1.0 / 60f, OverlayGeometry.frameDeltaSec(C, 200, 100, 60f), 1e-9); // clock went back
        assertEquals("unknown refresh rate uses the fallback", 1.0 / C.fallbackRefreshHz,
                OverlayGeometry.frameDeltaSec(C, 0, 1000, 0f), 1e-9);
    }

    @Test
    public void highlightWindowMatchesTheOriginalRule() {
        long[] starts = new long[40];
        for (int i = 0; i < starts.length; i++) starts[i] = i * 1200L;
        for (int active = 0; active < starts.length; active++) {
            for (int last = -1; last < starts.length; last++) {
                long pos = starts[active] + 300;
                // Original inline implementation.
                boolean seeked = Math.abs(active - last) > 1;
                int eStart = seeked ? 0 : Math.max(0, active - 2);
                int eEnd;
                if (seeked) {
                    eEnd = starts.length;
                } else {
                    eEnd = active;
                    while (eEnd < starts.length && starts[eEnd] - pos < 3000) eEnd++;
                    eEnd = Math.min(starts.length, eEnd + 2);
                }
                assertEquals(eStart, OverlayGeometry.highlightStart(C, active, last));
                assertEquals(eEnd, OverlayGeometry.highlightEnd(C, active, last, starts.length,
                        starts.length, i -> starts[i], pos));
            }
        }
    }

    // ── Pref sanitising ──────────────────────────────────────────────────────

    @Test
    public void prefValuesAreClampedToTheirRanges() {
        assertEquals(1500, OverlayGeometry.syncOffset(C, 99999));
        assertEquals(-1500, OverlayGeometry.syncOffset(C, -99999));
        assertEquals(-250, OverlayGeometry.syncOffset(C, -250));
        assertEquals(55, OverlayGeometry.widthPercent(C, 10));
        assertEquals(70, OverlayGeometry.heightPercent(C, 99));
        assertEquals(2.0f, OverlayGeometry.fontScale(C, 9f), 0f);
    }

    // ── Config validation ────────────────────────────────────────────────────

    @Test
    public void builderRejectsUnusableConfigs() {
        expectInvalid(b -> b.widthPercentMin = 90);            // min > default
        expectInvalid(b -> b.heightPercentMax = 120);          // > 100%
        expectInvalid(b -> b.fontScaleMin = 0f);
        expectInvalid(b -> b.chatWidthFraction = 1.5f);
        expectInvalid(b -> b.bubbleSizeDp = 0);
        expectInvalid(b -> b.themeStrokeAlpha = 300);
        expectInvalid(b -> b.highlightLinesAhead = -1);

        OverlayConfig.Builder ok = OverlayConfig.builder();
        ok.chatHeightDp = 420;
        assertEquals(420, ok.build().chatHeightDp);
    }

    private interface Mutation {
        void apply(OverlayConfig.Builder b);
    }

    private static void expectInvalid(Mutation m) {
        OverlayConfig.Builder b = OverlayConfig.builder();
        m.apply(b);
        try {
            b.build();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
