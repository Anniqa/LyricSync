package com.lyricsync.app.ui.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;

/**
 * Round play/pause button for the full-screen lyrics. The glyph morphs between the
 * play triangle and the two pause bars (each half of the triangle becomes one bar),
 * the disc takes the album accent with an icon colour that keeps contrast, and a
 * press squeezes the disc and springs it back.
 */
public final class PlayPauseButton extends View {

    // Unit geometry (fractions of the glyph box, centred on 0,0). Play is nudged right
    // for optical centring; its two halves are quads so they morph point by point.
    // Triangle centroid sits at x = 0, so it looks centred in the disc.
    private static final float[] PLAY = {
            -0.23f, -0.38f, 0.11f, -0.19f, 0.11f, 0.19f, -0.23f, 0.38f,   // left half
            0.11f, -0.19f, 0.45f, 0f, 0.45f, 0f, 0.11f, 0.19f};           // right half (point)
    private static final float[] PAUSE = {
            -0.35f, -0.36f, -0.09f, -0.36f, -0.09f, 0.36f, -0.35f, 0.36f,
            0.09f, -0.36f, 0.35f, -0.36f, 0.35f, 0.36f, 0.09f, 0.36f};

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** 0 = play triangle, 1 = pause bars. */
    private float morph;
    private boolean playing;
    private ValueAnimator morphAnim;
    private CharSequence playLabel = "Play", pauseLabel = "Pause";

    public PlayPauseButton(Context context) {
        this(context, null);
    }

    public PlayPauseButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        disc.setStyle(Paint.Style.FILL);
        glyph.setStyle(Paint.Style.FILL);
        setAccent(0xFF1ED760);
        setClickable(true);
        setFocusable(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        setElevation(8 * getResources().getDisplayMetrics().density);
    }

    /** Disc colour (album accent); the glyph turns dark or light to stay readable. */
    public void setAccent(int color) {
        disc.setColor(color | 0xFF000000);
        glyph.setColor(luminance(color) > 0.45f ? 0xFF0B0C10 : 0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= 28) {
            setOutlineSpotShadowColor(color);
            setOutlineAmbientShadowColor(color);
        }
        invalidate();
    }

    /** Spoken labels for the two states (localised by the caller). */
    public void setLabels(CharSequence play, CharSequence pause) {
        playLabel = play;
        pauseLabel = pause;
        setContentDescription(playing ? pauseLabel : playLabel);
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Shows pause while playing; {@code animate} morphs the glyph instead of jumping. */
    public void setPlaying(boolean nowPlaying, boolean animate) {
        if (nowPlaying == playing && (morphAnim == null || !morphAnim.isRunning())) return;
        playing = nowPlaying;
        setContentDescription(playing ? pauseLabel : playLabel);
        float target = nowPlaying ? 1f : 0f;
        if (morphAnim != null) morphAnim.cancel();
        if (!animate || !isAttachedToWindow()) {
            morph = target;
            invalidate();
            return;
        }
        morphAnim = ValueAnimator.ofFloat(morph, target);
        morphAnim.setDuration(240);
        morphAnim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        morphAnim.addUpdateListener(a -> {
            morph = (float) a.getAnimatedValue();
            invalidate();
        });
        morphAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Soft glyph corners scale with the button.
        glyph.setPathEffect(new CornerPathEffect(Math.min(w, h) * 0.035f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        canvas.drawCircle(cx, cy, r, disc);
        float s = r * 0.82f; // glyph box edge
        path.rewind();
        for (int quad = 0; quad < 2; quad++) {
            for (int p = 0; p < 4; p++) {
                int i = quad * 8 + p * 2;
                float x = cx + s * lerp(PLAY[i], PAUSE[i], morph);
                float y = cy + s * lerp(PLAY[i + 1], PAUSE[i + 1], morph);
                if (p == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
        }
        canvas.drawPath(path, glyph);
    }

    /** Press feedback only; super.onTouchEvent still detects the click and calls performClick. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).setInterpolator(null).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new OvershootInterpolator(3f)).start();
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Button.class.getName();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Relative luminance 0..1 (sRGB, WCAG). */
    public static float luminance(int c) {
        return (float) (0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c)));
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
