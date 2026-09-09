package com.lyricsync.app.overlay;

import org.junit.Assert;
import org.junit.Test;

public class EdgeSwipeTriggerGestureTest {

    public static class GestureStateMachine {
        public static final long HOLD_THRESHOLD_MS = 250;
        public static final float MIN_SWIPE_DISTANCE_PX = 40.0f;

        private float downX;
        private float downY;
        private long downTime;
        private boolean armed;
        private boolean cancelled;

        public void onDown(float x, float y, long time) {
            this.downX = x;
            this.downY = y;
            this.downTime = time;
            this.armed = false;
            this.cancelled = false;
        }

        public void onMove(float x, float y) {
            float dx = Math.abs(x - downX);
            float dy = Math.abs(y - downY);
            if (dy > dx * 1.5f) {
                cancelled = true;
            } else if (dx >= MIN_SWIPE_DISTANCE_PX && !cancelled) {
                armed = true;
            }
        }

        public boolean evaluateTrigger(long currentTime) {
            return armed && !cancelled && (currentTime - downTime >= HOLD_THRESHOLD_MS);
        }

        public void onUp() {
            armed = false;
        }
    }

    @Test
    public void testSwipeAndHold_Success() {
        GestureStateMachine sm = new GestureStateMachine();
        sm.onDown(10f, 100f, 1000L);
        sm.onMove(60f, 105f); // dx = 50, dy = 5 -> valid horizontal swipe
        Assert.assertFalse("Not triggered yet before 250ms", sm.evaluateTrigger(1150L));
        Assert.assertTrue("Triggered at >= 250ms hold", sm.evaluateTrigger(1250L));
    }

    @Test
    public void testQuickFlick_DoesNotTrigger() {
        GestureStateMachine sm = new GestureStateMachine();
        sm.onDown(10f, 100f, 1000L);
        sm.onMove(70f, 100f);
        Assert.assertFalse("Flick released before threshold must not trigger", sm.evaluateTrigger(1100L));
        sm.onUp();
        Assert.assertFalse("Released state does not trigger", sm.evaluateTrigger(1300L));
    }

    @Test
    public void testVerticalScroll_CancelsGesture() {
        GestureStateMachine sm = new GestureStateMachine();
        sm.onDown(10f, 100f, 1000L);
        sm.onMove(20f, 180f); // dy = 80, dx = 10 -> vertical scroll
        Assert.assertFalse("Vertical scroll should cancel gesture", sm.evaluateTrigger(1300L));
    }

    @Test
    public void testHoldThenSwipe_Success() {
        GestureStateMachine sm = new GestureStateMachine();
        sm.onDown(10f, 100f, 1000L);
        Assert.assertFalse("Not triggered while stationary even after 250ms", sm.evaluateTrigger(1300L));
        sm.onMove(60f, 105f);
        Assert.assertTrue("Triggered when swipe occurs after holding past threshold", sm.evaluateTrigger(1300L));
    }
}
