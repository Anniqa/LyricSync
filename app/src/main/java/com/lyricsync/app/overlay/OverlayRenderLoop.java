package com.lyricsync.app.overlay;

import android.view.Choreographer;
import android.view.Display;
import android.view.View;

/**
 * Vsync-driven lyrics render loop. Steps once per display refresh via
 * {@link Choreographer} with the real frame gap ({@link OverlayGeometry#frameDeltaSec}),
 * so motion speed is identical at 60, 90, 120 or 144 Hz.
 *
 * The loop goes idle on its own when there is nothing to animate (paused and
 * settled) — no frames are scheduled while idle — and is woken by
 * {@link #wake()} on play / seek / new lyrics. After playback stops it keeps
 * stepping {@link OverlayConfig#pauseSettleMs} so springs land instead of
 * freezing mid-overshoot. {@link #stop()} removes the callback; nothing here holds
 * a reference past that.
 */
final class OverlayRenderLoop {

    interface Frame {
        /** True while playback is running with lyrics on screen. */
        boolean isPlaying();

        /** True once scroll and word animations are at rest. */
        boolean isSettled();

        /** Advance every animation by {@code dtSec}. */
        void step(double dtSec);
    }

    private final OverlayConfig config;
    private final Frame frame;
    private final Choreographer choreographer = Choreographer.getInstance();
    /** Any attached view: its Display reports the live refresh rate (changes with LTPO / modes). */
    private View displayView;

    /** The loop is wanted (lyrics visible). */
    private boolean enabled;
    /** A frame callback is currently scheduled. */
    private boolean scheduled;
    private long lastFrameNanos;
    /** Frame time when playback was last seen; drives the post-pause settle window. */
    private long lastPlayingNanos;

    private final Choreographer.FrameCallback callback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            scheduled = false;
            if (!enabled) return;

            boolean playing = frame.isPlaying();
            if (playing) lastPlayingNanos = frameTimeNanos;
            boolean settling = lastPlayingNanos > 0
                    && frameTimeNanos - lastPlayingNanos < config.pauseSettleMs * 1_000_000L;
            if (!playing && !settling && frame.isSettled()) {
                // Idle: drop the frame clock so the next wake starts with one nominal step.
                lastFrameNanos = 0;
                lastPlayingNanos = 0;
                return;
            }

            // The refresh rate is only needed when there is no previous vsync to measure
            // from; querying the Display every frame can cost a binder call on older releases.
            boolean needNominal = lastFrameNanos <= 0 || frameTimeNanos <= lastFrameNanos;
            double dt = OverlayGeometry.frameDeltaSec(config, lastFrameNanos, frameTimeNanos,
                    needNominal ? refreshHz() : config.fallbackRefreshHz);
            lastFrameNanos = frameTimeNanos;
            frame.step(dt);
            schedule();
        }
    };

    OverlayRenderLoop(OverlayConfig config, Frame frame) {
        this.config = config;
        this.frame = frame;
    }

    void setDisplayView(View view) {
        displayView = view;
    }

    /** Lyrics became visible: run (and keep running while there is motion). */
    void start() {
        enabled = true;
        wake();
    }

    /** Lyrics hidden / cleared / service gone: unschedule immediately. */
    void stop() {
        enabled = false;
        scheduled = false;
        lastFrameNanos = 0;
        lastPlayingNanos = 0;
        choreographer.removeFrameCallback(callback);
    }

    /** Something changed (play, seek, new lyrics): make sure a frame is coming. */
    void wake() {
        if (!enabled) return;
        if (lastPlayingNanos == 0) lastPlayingNanos = System.nanoTime();
        schedule();
    }

    boolean isEnabled() {
        return enabled;
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        choreographer.postFrameCallback(callback);
    }

    private float refreshHz() {
        View v = displayView;
        Display d = v != null ? v.getDisplay() : null;
        return d != null ? d.getRefreshRate() : config.fallbackRefreshHz;
    }
}
