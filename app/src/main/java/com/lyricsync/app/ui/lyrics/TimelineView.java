package com.lyricsync.app.ui.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

import java.util.Locale;

/**
 * Lyric-aware song timeline for the full-screen lyrics.
 * <ul>
 * <li>The track is cut into one segment per lyric line, so the song reads like chapters;
 *     the rest of the current line glows faintly ahead of the playhead.</li>
 * <li>While playing, a light band sweeps along the played part and the playhead sends out
 *     a soft sonar ring; paused, the bar thins and calms down.</li>
 * <li>Drag anywhere: the bar thickens, a bubble shows the target time with the lyric line
 *     you will land on, and each line boundary you cross ticks under the finger.</li>
 * <li>Jumps (seek, new song) glide instead of snapping. Tap the right time to switch
 *     between total and remaining.</li>
 * </ul>
 */
public final class TimelineView extends View {

    public interface Listener {
        void onSeek(long positionMs);
    }

    private static final long JUMP_MS = 340;
    private static final long SHIMMER_MS = 2600;
    private static final long SONAR_MS = 1700;
    private static final long SEEK_STEP_MS = 10_000;
    /** Smallest segment drawn, dp. */
    private static final float MIN_GAP_DP = 14f;

    private final float dp;
    private final Paint base = paint(), fill = paint(), glow = paint(), ahead = paint(), notch = paint(),
            shimmer = paint(), head = paint(), sonar = paint(), bubble = paint();
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleTime = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleLine = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shimmerMatrix = new Matrix();
    private final RectF rect = new RectF();

    private Listener listener;
    private int accent = 0xFF1ED760;
    private long duration, position;
    private long[] starts = new long[0];
    private String[] texts = new String[0];
    private boolean playing, scrubbing, showRemaining, labelTap;
    private long scrubMs;
    private int scrubLine = -1;
    /** Seek glide: from where, and when it started. */
    private long jumpFrom = -1, jumpAt;
    /** 0 idle .. 1 dragging; 0 playing .. 1 paused; 0 .. 1 first draw-in. */
    private float grab, calm = 1f, reveal;
    private ValueAnimator grabAnim, calmAnim, revealAnim;

    public TimelineView(Context context) {
        this(context, null);
    }

    public TimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        base.setStrokeCap(Paint.Cap.ROUND);
        base.setColor(0x2EFFFFFF);
        for (Paint p : new Paint[]{fill, glow, ahead, shimmer}) {
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
        }
        base.setStyle(Paint.Style.STROKE);
        notch.setColor(0xCC0B0C10);
        head.setColor(Color.WHITE);
        sonar.setStyle(Paint.Style.STROKE);
        sonar.setStrokeWidth(2 * dp);
        bubble.setColor(0xFAFFFFFF);
        label.setTextSize(12 * sp);
        label.setColor(0xB3FFFFFF);
        label.setFontFeatureSettings("tnum");
        bubbleTime.setTextSize(13 * sp);
        bubbleTime.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleTime.setFontFeatureSettings("tnum");
        bubbleTime.setColor(0xFF0B0C10);
        bubbleLine.setTextSize(13 * sp);
        bubbleLine.setColor(0xFF3A3D4A);
        setAccent(accent);
        setFocusable(true);
        setClickable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setTypeface(Typeface medium) {
        if (medium == null) return;
        label.setTypeface(medium);
        bubbleLine.setTypeface(medium);
    }

    public void setAccent(int color) {
        accent = color | 0xFF000000;
        glow.setColor((accent & 0x00FFFFFF) | 0x47000000);
        ahead.setColor((accent & 0x00FFFFFF) | 0x52000000);
        sonar.setColor(accent);
        rebuildShaders();
        invalidate();
    }

    /** Line start times and the text the bubble previews (both in line order). */
    public void setLines(long[] lineStarts, String[] lineTexts) {
        starts = lineStarts != null ? lineStarts : new long[0];
        texts = lineTexts != null ? lineTexts : new String[0];
        invalidate();
    }

    public void setDuration(long ms) {
        if (ms == duration) return;
        duration = Math.max(0, ms);
        setEnabled(duration > 0);
        invalidate();
    }

    /** Live position; a jump of more than 1.5 s glides instead of snapping. */
    public void setPosition(long ms) {
        long shown = shownPosition();
        if (!scrubbing && Math.abs(ms - shown) > 1500 && duration > 0) {
            jumpFrom = shown;
            jumpAt = SystemClock.uptimeMillis();
        }
        position = ms;
        postInvalidateOnAnimation();
    }

    public void setPlaying(boolean nowPlaying) {
        if (nowPlaying == playing && calmAnim != null) return;
        playing = nowPlaying;
        calmAnim = animate(calmAnim, calm, nowPlaying ? 0f : 1f, 420, v -> calm = v);
    }

    public boolean isScrubbing() {
        return scrubbing;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        reveal = 0f;
        revealAnim = animate(revealAnim, 0f, 1f, 750, v -> reveal = v);
    }

    @Override
    protected void onDetachedFromWindow() {
        for (ValueAnimator a : new ValueAnimator[]{grabAnim, calmAnim, revealAnim}) if (a != null) a.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(Math.round(76 * dp), heightSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildShaders();
    }

    // ── geometry ────────────────────────────────────────────────────────────────

    /** Track inset: room for the grown playhead and its ring, inside the view (never clipped). */
    private float left() {
        return 14 * dp;
    }

    private float right() {
        return getWidth() - 14 * dp;
    }

    private float trackY() {
        return 44 * dp;
    }

    private float xOf(long ms) {
        if (duration <= 0) return left();
        float f = Math.max(0f, Math.min(1f, ms / (float) duration));
        return left() + (right() - left()) * f;
    }

    private long msAt(float x) {
        float f = (x - left()) / Math.max(1f, right() - left());
        return Math.round(Math.max(0f, Math.min(1f, f)) * duration);
    }

    private long shownPosition() {
        if (scrubbing) return scrubMs;
        if (jumpFrom < 0) return position;
        float t = (SystemClock.uptimeMillis() - jumpAt) / (float) JUMP_MS;
        if (t >= 1f) {
            jumpFrom = -1;
            return position;
        }
        float e = 1f - (1f - t) * (1f - t) * (1f - t); // ease-out cubic
        return jumpFrom + Math.round((position - jumpFrom) * e);
    }

    private int lineAt(long ms) {
        int lo = 0, hi = starts.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= ms) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void rebuildShaders() {
        if (getWidth() == 0) return;
        int light = blend(accent, Color.WHITE, 0.35f), dark = blend(accent, Color.BLACK, 0.25f);
        fill.setShader(new LinearGradient(left(), 0, right(), 0, dark, light, Shader.TileMode.CLAMP));
        float band = 90 * dp;
        shimmer.setShader(new LinearGradient(0, 0, band, 0,
                new int[]{0x00FFFFFF, 0x73FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    // ── drawing ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas c) {
        float l = left(), r = l + (right() - l) * reveal, y = trackY();
        long shown = shownPosition();
        float x = Math.min(r, xOf(shown));
        float thick = (4 + 5 * grab) * dp * (1f - 0.25f * calm);
        long now = SystemClock.uptimeMillis();

        base.setStrokeWidth(thick);
        c.drawLine(l, y, r, y, base);

        // What is left of the current line, just ahead of the playhead.
        int line = lineAt(shown);
        if (line >= 0 && duration > 0) {
            long end = line + 1 < starts.length ? starts[line + 1] : duration;
            float ax = Math.min(r, xOf(end));
            if (ax > x) {
                ahead.setStrokeWidth(thick);
                c.drawLine(x, y, ax, y, ahead);
            }
        }

        if (duration > 0 && x > l) {
            glow.setStrokeWidth(thick * 2.6f);
            c.drawLine(l, y, x, y, glow);
            fill.setStrokeWidth(thick);
            c.drawLine(l, y, x, y, fill);
            float live = 1f - calm;
            if (live > 0.01f) {
                float band = 90 * dp;
                float t = (now % SHIMMER_MS) / (float) SHIMMER_MS;
                shimmerMatrix.setTranslate(l - band + (x - l + band) * t, 0);
                shimmer.getShader().setLocalMatrix(shimmerMatrix);
                shimmer.setAlpha(Math.round(255 * live));
                shimmer.setStrokeWidth(thick);
                c.drawLine(l, y, x, y, shimmer);
            }
        }

        // Line boundaries cut the bar into segments. Boundaries closer than MIN_GAP to the
        // last one drawn are skipped, so fast verses read as one segment, not a barcode.
        if (duration > 0 && starts.length > 1) {
            float half = thick / 2f + dp, last = l;
            for (long s : starts) {
                if (s <= 0 || s >= duration) continue;
                float nx = xOf(s);
                if (nx > r) break;
                if (nx - last < MIN_GAP_DP * dp || r - nx < MIN_GAP_DP * dp) continue;
                c.drawRect(nx - dp * 0.6f, y - half, nx + dp * 0.6f, y + half, notch);
                last = nx;
            }
        }

        if (duration > 0) {
            float hr = (5 + 4 * grab) * dp;
            if (playing && !scrubbing) {
                float p = (now % SONAR_MS) / (float) SONAR_MS;
                sonar.setAlpha(Math.round(150 * (1f - p) * (1f - calm)));
                c.drawCircle(x, y, hr + 12 * dp * p, sonar);
            }
            c.drawCircle(x, y, hr, head);
        }

        drawLabels(c, shown);
        if (grab > 0.01f) drawBubble(c, x);
        if (playing || jumpFrom >= 0 || grab > 0 || reveal < 1f) postInvalidateOnAnimation();
    }

    private void drawLabels(Canvas c, long shown) {
        float base = trackY() + 26 * dp;
        label.setColor(scrubbing ? accent : 0xB3FFFFFF);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText(clock(shown), left(), base, label);
        label.setColor(0x8CFFFFFF);
        label.setTextAlign(Paint.Align.RIGHT);
        String end = duration <= 0 ? "--:--" : showRemaining ? "-" + clock(duration - shown) : clock(duration);
        c.drawText(end, right(), base, label);
    }

    private void drawBubble(Canvas c, float x) {
        String time = clock(scrubMs);
        int line = lineAt(scrubMs);
        String text = line >= 0 && line < texts.length && texts[line] != null ? texts[line].trim() : "";
        float pad = 12 * dp, gap = 8 * dp;
        float timeW = bubbleTime.measureText(time);
        float maxLine = getWidth() * 0.72f - timeW - 2 * pad - gap;
        CharSequence shownText = text.isEmpty() || maxLine < 24 * dp ? ""
                : TextUtils.ellipsize(text, bubbleLine, maxLine, TextUtils.TruncateAt.END);
        float w = 2 * pad + timeW + (shownText.length() > 0 ? gap + bubbleLine.measureText(shownText, 0, shownText.length()) : 0);
        float h = 28 * dp;
        float lift = (1f - grab) * 8 * dp;
        float cx = Math.max(w / 2f + 2 * dp, Math.min(getWidth() - w / 2f - 2 * dp, x));
        rect.set(cx - w / 2f, 2 * dp + lift, cx + w / 2f, 2 * dp + h + lift);
        int a = Math.round(255 * grab);
        bubble.setAlpha(Math.round(250 * grab));
        c.drawRoundRect(rect, h / 2f, h / 2f, bubble);
        float baseLine = rect.centerY() - (bubbleTime.descent() + bubbleTime.ascent()) / 2f;
        bubbleTime.setAlpha(a);
        c.drawText(time, rect.left + pad, baseLine, bubbleTime);
        if (shownText.length() > 0) {
            bubbleLine.setAlpha(a);
            c.drawText(shownText, 0, shownText.length(), rect.left + pad + timeW + gap, baseLine, bubbleLine);
        }
    }

    // ── touch ───────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (duration <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                labelTap = e.getY() > trackY() + 12 * dp && e.getX() > getWidth() * 0.72f;
                if (labelTap) return true;
                getParent().requestDisallowInterceptTouchEvent(true);
                scrubbing = true;
                scrubMs = msAt(e.getX());
                scrubLine = lineAt(scrubMs);
                grabAnim = animate(grabAnim, grab, 1f, 180, v -> grab = v);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scrubbing) return true;
                scrubMs = msAt(e.getX());
                int lineNow = lineAt(scrubMs);
                if (lineNow != scrubLine) {
                    scrubLine = lineNow;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (labelTap) {
                    labelTap = false;
                    showRemaining = !showRemaining;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
                if (!scrubbing) return true;
                endScrub(true);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                labelTap = false;
                if (scrubbing) endScrub(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void endScrub(boolean commit) {
        scrubbing = false;
        grabAnim = animate(grabAnim, grab, 0f, 260, v -> grab = v);
        if (commit) {
            position = scrubMs; // show the target at once; the player confirms a moment later
            jumpFrom = -1;
            if (listener != null) listener.onSeek(scrubMs);
        }
        invalidate();
    }

    // ── accessibility: a seek bar that steps 10 s ──────────────────────────────

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        if (duration > 0) {
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, duration, position));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (duration > 0 && listener != null
                && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            long step = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? SEEK_STEP_MS : -SEEK_STEP_MS;
            long target = Math.max(0, Math.min(duration, position + step));
            setPosition(target);
            listener.onSeek(target);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private interface FloatSink {
        void set(float v);
    }

    private ValueAnimator animate(ValueAnimator old, float from, float to, long ms, FloatSink sink) {
        if (old != null) old.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> {
            sink.set((float) v.getAnimatedValue());
            invalidate();
        });
        a.start();
        return a;
    }

    private static Paint paint() {
        return new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
