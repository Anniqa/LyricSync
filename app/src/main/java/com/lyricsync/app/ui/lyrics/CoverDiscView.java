package com.lyricsync.app.ui.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Album cover for the full-screen lyrics, drawn as a record sleeve with its vinyl.
 * <ul>
 * <li>The sleeve is a squircle (superellipse) with a glass sheen and a thin rim.</li>
 * <li>Behind it sits a vinyl disc whose centre label is the same artwork.</li>
 * <li>Playing: the disc slides out of the sleeve and spins; paused: it slows down,
 *     stops and slides most of the way back in.</li>
 * <li>A soft glow in the album accent breathes with the playback state, and a new
 *     cover crossfades over the old one.</li>
 * </ul>
 * Decorative only: the title next to it says what is playing.
 */
public final class CoverDiscView extends View {

    /** Spin speed while playing, degrees per second (slower than a real 33 rpm, calmer to watch). */
    private static final float SPIN_DPS = 110f;
    /** How far the disc stays out while paused (0 = hidden, 1 = fully out). */
    private static final float PAUSED_SLIDE = 0.45f;
    private static final float SQUIRCLE_N = 4.6f;

    private final float dp;
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint oldArt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path sleeve = new Path();
    private final Path discClip = new Path();
    private final Matrix matrix = new Matrix();

    private Bitmap source, scaled;
    private BitmapShader artShader, oldShader, labelShader;
    /** Sleeve fill when there is no artwork; rebuilt with the accent. */
    private Shader blank;
    private int accent = 0xFF1ED760;
    private boolean playing;
    private float slide = PAUSED_SLIDE, fade = 1f, angle, spin;
    private long lastFrame;
    private ValueAnimator slideAnim, fadeAnim;
    // Geometry, set in onSizeChanged.
    private float pad, size, sleeveL, sleeveT, discR, cxMin, cxMax, cy;

    public CoverDiscView(Context context) {
        this(context, null);
    }

    public CoverDiscView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        disc.setColor(0xFF0B0C10);
        groove.setStyle(Paint.Style.STROKE);
        groove.setStrokeWidth(0.6f * dp);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1f * dp);
        rim.setColor(0x38FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * dp);
        sheen.setShader(new SweepGradient(0, 0,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.08f, 0.18f, 0.5f, 0.58f, 0.68f, 1f}));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** New artwork; crossfades from the previous one. Null shows an accent-tinted sleeve. */
    public void setArt(Bitmap bitmap) {
        if (bitmap == source) return;
        source = bitmap != null && !bitmap.isRecycled() ? bitmap : null;
        oldShader = artShader;
        rebuildArt();
        if (oldShader != null && isAttachedToWindow()) {
            if (fadeAnim != null) fadeAnim.cancel();
            fadeAnim = ValueAnimator.ofFloat(0f, 1f);
            fadeAnim.setDuration(320);
            fadeAnim.addUpdateListener(a -> {
                fade = (float) a.getAnimatedValue();
                invalidate();
            });
            fadeAnim.start();
        } else {
            fade = 1f;
            oldShader = null;
        }
        invalidate();
    }

    public void setAccent(int color) {
        accent = color | 0xFF000000;
        ring.setColor((accent & 0x00FFFFFF) | 0x99000000);
        rebuildGlow();
        invalidate();
    }

    public void setPlaying(boolean nowPlaying) {
        if (nowPlaying == playing) return;
        playing = nowPlaying;
        if (slideAnim != null) slideAnim.cancel();
        slideAnim = ValueAnimator.ofFloat(slide, nowPlaying ? 1f : PAUSED_SLIDE);
        slideAnim.setDuration(nowPlaying ? 560 : 460);
        slideAnim.setInterpolator(nowPlaying ? new OvershootInterpolator(1.3f) : new DecelerateInterpolator(1.8f));
        slideAnim.addUpdateListener(a -> {
            slide = (float) a.getAnimatedValue();
            invalidate();
        });
        slideAnim.start();
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Room around the sleeve so the glow fades out inside the view (no hard edge).
        pad = 6 * dp;
        size = h - 2 * pad;
        sleeveL = pad;
        sleeveT = pad;
        discR = size * 0.45f;
        cy = h / 2f;
        cxMin = sleeveL + size / 2f;
        cxMax = Math.max(cxMin, w - 2 * dp - discR);
        buildSquircle(sleeve, sleeveL, sleeveT, size);
        gloss.setShader(new LinearGradient(sleeveL, sleeveT, sleeveL + size * 0.7f, sleeveT + size * 0.7f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        shade.setShader(new LinearGradient(sleeveL + size, 0, sleeveL + size + 5 * dp, 0,
                0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        rebuildGlow();
        rebuildArt();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        lastFrame = 0; // no time jump after the screen comes back
        if (visibility == VISIBLE) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (slideAnim != null) slideAnim.cancel();
        if (fadeAnim != null) fadeAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (size <= 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        // Spin up and wind down smoothly instead of starting/stopping dead.
        float target = playing ? SPIN_DPS : 0f;
        spin += (target - spin) * Math.min(1f, dt * (playing ? 2.2f : 1.4f));
        angle = (angle + spin * dt) % 360f;

        float cx = cxMin + (cxMax - cxMin) * slide;

        // 1) Accent glow behind everything; brighter while the record is out.
        glow.setAlpha(Math.round(255 * (0.45f + 0.55f * Math.min(1f, slide))));
        c.drawCircle(sleeveL + size * 0.62f, cy, cy, glow);

        // 2) Vinyl: grooves and label turn, the light on it does not.
        c.save();
        c.rotate(angle, cx, cy);
        c.drawCircle(cx, cy, discR, disc);
        for (int i = 0; i < 3; i++) {
            groove.setColor(0x1FFFFFFF);
            c.drawCircle(cx, cy, discR * (0.58f + 0.13f * i), groove);
        }
        float lr = discR * 0.40f;
        if (labelShader != null) {
            mapCenterCrop(labelShader, cx - lr, cy - lr, 2 * lr);
            label.setShader(labelShader);
        } else {
            label.setShader(null);
            label.setColor(accent);
        }
        c.drawCircle(cx, cy, lr, label);
        c.drawCircle(cx, cy, lr, ring);
        c.drawCircle(cx, cy, discR * 0.055f, disc);
        c.restore();
        matrix.setTranslate(cx, cy);
        sheen.getShader().setLocalMatrix(matrix);
        c.drawCircle(cx, cy, discR, sheen);
        c.drawCircle(cx, cy, discR, rim);

        // 3) The sleeve casts a soft shadow on the record.
        discClip.rewind();
        discClip.addCircle(cx, cy, discR, Path.Direction.CW);
        c.save();
        c.clipPath(discClip);
        c.drawRect(sleeveL + size, cy - discR, sleeveL + size + 5 * dp, cy + discR, shade);
        c.restore();

        // 4) Sleeve: artwork (crossfading), glass sheen, rim.
        if (artShader == null) {
            art.setShader(blank);
            art.setAlpha(255);
            c.drawPath(sleeve, art);
        } else {
            if (oldShader != null && fade < 1f) {
                oldArt.setShader(oldShader);
                oldArt.setAlpha(255);
                c.drawPath(sleeve, oldArt);
            }
            art.setShader(artShader);
            art.setAlpha(Math.round(255 * fade));
            c.drawPath(sleeve, art);
            if (fade >= 1f) oldShader = null;
        }
        c.drawPath(sleeve, gloss);
        c.drawPath(sleeve, rim);

        if (spin > 0.5f || playing) postInvalidateOnAnimation();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Square, display-sized copy so the shader never samples a 1000 px bitmap down to 40 dp. */
    private void rebuildArt() {
        if (source == null || source.isRecycled() || size <= 0) {
            artShader = null;
            labelShader = null;
            return;
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        int px = Math.max(16, Math.round(size));
        Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2,
                (source.getHeight() - side) / 2, side, side);
        scaled = Bitmap.createScaledBitmap(square, px, px, true);
        artShader = new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        mapCenterCrop(artShader, sleeveL, sleeveT, size);
        labelShader = new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    private void mapCenterCrop(BitmapShader shader, float left, float top, float side) {
        if (scaled == null) return;
        float s = side / scaled.getWidth();
        matrix.setScale(s, s);
        matrix.postTranslate(left, top);
        shader.setLocalMatrix(matrix);
    }

    private void rebuildGlow() {
        if (size <= 0) return;
        blank = new LinearGradient(sleeveL, sleeveT, sleeveL + size, sleeveT + size,
                accent, blend(accent, Color.BLACK, 0.6f), Shader.TileMode.CLAMP);
        glow.setShader(new RadialGradient(sleeveL + size * 0.62f, cy, cy,
                new int[]{(accent & 0x00FFFFFF) | 0x66000000, (accent & 0x00FFFFFF) | 0x1A000000, 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Superellipse |x|^n + |y|^n = 1: softer corners than a rounded rect. */
    private static void buildSquircle(Path p, float left, float top, float side) {
        p.rewind();
        float a = side / 2f, cx = left + a, cy = top + a;
        int steps = 96;
        for (int i = 0; i <= steps; i++) {
            double t = 2 * Math.PI * i / steps;
            double ct = Math.cos(t), st = Math.sin(t);
            float x = (float) (cx + a * Math.signum(ct) * Math.pow(Math.abs(ct), 2 / SQUIRCLE_N));
            float y = (float) (cy + a * Math.signum(st) * Math.pow(Math.abs(st), 2 / SQUIRCLE_N));
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
