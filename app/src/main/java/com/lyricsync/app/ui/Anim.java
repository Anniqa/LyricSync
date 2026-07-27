package com.lyricsync.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Shared motion vocabulary. Everything the app animates goes through here so timing and
 * easing stay consistent between the activity and the floating overlay.
 */
public final class Anim {
    private Anim() {}

    /** Emphasised decelerate: quick departure, long soft landing. Use for entrances. */
    public static final Interpolator DECEL = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Standard easing for state changes that start and end at rest. */
    public static final Interpolator STANDARD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** Slight overshoot for taps and confirmations. */
    public static final Interpolator OVERSHOOT = new OvershootInterpolator(1.1f);
    /** Big, rubbery overshoot — the bubble family of entrances and pulses. */
    public static final Interpolator BUBBLE = new OvershootInterpolator(2.2f);

    public static final long D_FAST = 160;
    public static final long D_MED = 280;
    public static final long D_SLOW = 480;

    public interface ColorSink {
        void onColor(int color);
    }

    /** Fade + rise a view into place. Safe to call before the view has been laid out. */
    public static void enter(View v, long delayMs) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 22f));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(D_SLOW)
                .setInterpolator(DECEL)
                .start();
    }

    /** Enter a list of views one after another. Nulls are skipped without shifting the beat. */
    public static void enterStagger(long baseDelay, long step, View... views) {
        if (views == null) return;
        long delay = baseDelay;
        for (View v : views) {
            if (v == null) continue;
            enter(v, delay);
            delay += step;
        }
    }

    /** Scale pop, for buttons and values that just changed. */
    public static void pop(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(0)
                .setDuration(D_MED)
                .setInterpolator(OVERSHOOT)
                .start();
    }

    // ── Bubble family ──────────────────────────────────────────────────────

    /**
     * Bubble entrance: the view inflates from a small blob with a rubbery overshoot
     * while fading in. Used when the overlay first appears on screen.
     */
    public static void bubbleIn(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(0.55f);
        v.setScaleY(0.55f);
        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(D_SLOW + 40)
                .setInterpolator(BUBBLE)
                .start();
    }

    /**
     * Track-change pulse: a quick squash inward, then a rubbery rebound. Reads as
     * the overlay "breathing" once when a new song lands. Alpha is forced to full
     * because cancelling a still-running entrance could otherwise freeze it midway.
     */
    public static void bubblePulse(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .alpha(1f)
                .scaleX(0.955f)
                .scaleY(0.955f)
                .setDuration(110)
                .setInterpolator(STANDARD)
                .withEndAction(() -> v.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(360)
                        .setInterpolator(BUBBLE)
                        .start())
                .start();
    }

    /**
     * Drag-release settle: a tiny outward puff that relaxes back, so letting go of
     * the overlay after a drag feels like dropping a bubble into place.
     */
    public static void bubbleSettle(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .alpha(1f)
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setDuration(90)
                .setInterpolator(STANDARD)
                .withEndAction(() -> v.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(BUBBLE)
                        .start())
                .start();
    }

    /** Rotate an affordance (chevron, caret) to a new angle. */
    public static void rotateTo(View v, float degrees) {
        if (v == null) return;
        v.animate()
                .rotation(degrees)
                .setDuration(D_MED)
                .setInterpolator(STANDARD)
                .start();
    }

    /**
     * Animate a gone view open to its natural height. The height is restored to
     * WRAP_CONTENT once the animation lands so later content changes still fit.
     */
    public static void expand(final View v) {
        if (v == null || v.getVisibility() == View.VISIBLE) return;

        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            v.setVisibility(View.VISIBLE);
            return;
        }

        int parentWidth = 0;
        if (v.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) v.getParent();
            parentWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        }
        int widthSpec = parentWidth > 0
                ? View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        v.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = Math.max(1, v.getMeasuredHeight());

        lp.height = 0;
        v.setLayoutParams(lp);
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);

        ValueAnimator anim = ValueAnimator.ofInt(0, target);
        anim.setDuration(D_MED);
        anim.setInterpolator(DECEL);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            v.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
            }
        });
        v.animate().alpha(1f).setStartDelay(40).setDuration(D_MED).start();
        anim.start();
    }

    /** Animate a visible view closed, ending at GONE with height restored to WRAP_CONTENT. */
    public static void collapse(final View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;

        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            v.setVisibility(View.GONE);
            return;
        }
        final int start = v.getHeight();
        if (start <= 0) {
            v.setVisibility(View.GONE);
            return;
        }

        ValueAnimator anim = ValueAnimator.ofInt(start, 0);
        anim.setDuration(D_MED);
        anim.setInterpolator(STANDARD);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            v.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(View.GONE);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
                v.setAlpha(1f);
            }
        });
        v.animate().alpha(0f).setDuration(D_FAST).start();
        anim.start();
    }

    /** Swap text with a short vertical cross-fade instead of a hard snap. */
    public static void setTextAnimated(final TextView tv, final CharSequence text) {
        if (tv == null) return;
        CharSequence next = text == null ? "" : text;
        if (next.toString().contentEquals(tv.getText())) return;

        tv.animate().cancel();
        final float rise = dp(tv, 6f);
        tv.animate()
                .alpha(0f)
                .translationY(-rise)
                .setDuration(D_FAST - 40)
                .setInterpolator(STANDARD)
                .withEndAction(() -> {
                    tv.setText(next);
                    tv.setTranslationY(rise);
                    tv.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(D_MED)
                            .setInterpolator(DECEL)
                            .start();
                })
                .start();
    }

    /** Tween between two ARGB colours, handing each step to the sink. */
    public static ValueAnimator color(int from, int to, long duration, final ColorSink sink) {
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        anim.setDuration(duration);
        anim.setInterpolator(STANDARD);
        anim.addUpdateListener(a -> sink.onColor((Integer) a.getAnimatedValue()));
        anim.start();
        return anim;
    }

    public static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density;
    }
}
