package com.lyricsync.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lyricsync.app.renderer.GradientWordView;
import com.lyricsync.app.renderer.LyricAnimStyle;
import com.lyricsync.app.util.AppFont;

/**
 * Builds the lyric-animation variant chips. In the main activity each chip carries
 * a LIVE preview — a tiny GradientWordView driven by a looping fake playback clock,
 * so the user sees Spring/Bubble/Calm/Glow before picking one. The overlay panel
 * uses the compact text-only form.
 */
public final class AnimStyleChips {
    private AnimStyleChips() {}

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xB8FFFFFF;
    private static final int COLOR_ACCENT = 0xFF1ED760;
    private static final int COLOR_FILL = 0x10FFFFFF;
    private static final int COLOR_FILL_SELECTED = 0x291ED760;
    private static final int COLOR_STROKE = 0x24FFFFFF;
    private static final int PREVIEW_INACTIVE = 0x82FFFFFF;

    private static final long PREVIEW_WORD_MS = 1400;
    private static final long PREVIEW_PAUSE_MS = 600;

    public interface OnStyleChosen {
        void onChosen(LyricAnimStyle.Variant variant);
    }

    /** Full chips with animated previews (main activity). */
    public static void buildWithPreviews(Context context, LinearLayout container,
                                         int selectedStyle, OnStyleChosen callback) {
        container.removeAllViews();
        // Previews animate past their bounds; keep the container from slicing them.
        container.setClipChildren(false);
        container.setClipToPadding(false);
        for (LyricAnimStyle.Variant variant : LyricAnimStyle.variants()) {
            container.addView(makePreviewChip(context, container, variant, selectedStyle, callback));
        }
    }

    /** Compact text-only chips (overlay settings panel). */
    public static void buildCompact(Context context, LinearLayout container,
                                    int selectedStyle, OnStyleChosen callback) {
        container.removeAllViews();
        for (LyricAnimStyle.Variant variant : LyricAnimStyle.variants()) {
            container.addView(makeCompactChip(context, container, variant, selectedStyle, callback));
        }
    }

    public static void refresh(LinearLayout container, int selectedStyle) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View chip = container.getChildAt(i);
            Object tag = chip.getTag();
            if (tag instanceof LyricAnimStyle.Variant) {
                LyricAnimStyle.Variant v = (LyricAnimStyle.Variant) tag;
                styleChip(chip, v.id == selectedStyle);
            }
        }
    }

    private static View makePreviewChip(Context context, LinearLayout container,
                                        LyricAnimStyle.Variant variant, int selectedStyle,
                                        OnStyleChosen callback) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout chip = baseChip(context, variant, selectedStyle == variant.id);
        chip.setMinimumWidth(Math.round(76 * density));
        chip.setPadding(Math.round(10 * density), Math.round(10 * density),
                Math.round(10 * density), Math.round(8 * density));

        AppFont.FontPair pair = AppFont.current(context);
        GradientWordView preview = new GradientWordView(context);
        preview.setAnimStyle(variant.id);
        preview.setWordIndex(0);
        // The preview is always "the active line" so the Wave ripple is visible.
        preview.setLineActive(true);
        preview.setText("Lyric");
        preview.setWordStyle(17f, PREVIEW_INACTIVE, pair.bold);
        preview.initLetterEmphasis("Lyric", 0, PREVIEW_WORD_MS);
        chip.addView(preview);

        TextView name = new TextView(context);
        name.setText(variant.displayName);
        name.setTextSize(10.5f);
        name.setTextColor(COLOR_TEXT_DIM);
        name.setPadding(0, Math.round(5 * density), 0, 0);
        chip.addView(name);

        attachPreviewLoop(preview, variant.id);
        wireClick(context, chip, container, variant, callback);
        return chip;
    }

    private static View makeCompactChip(Context context, LinearLayout container,
                                        LyricAnimStyle.Variant variant, int selectedStyle,
                                        OnStyleChosen callback) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout chip = baseChip(context, variant, selectedStyle == variant.id);
        chip.setPadding(Math.round(11 * density), Math.round(7 * density),
                Math.round(11 * density), Math.round(7 * density));

        TextView name = new TextView(context);
        name.setText(variant.displayName);
        name.setTextSize(11);
        name.setTextColor(selectedStyle == variant.id ? COLOR_ACCENT : COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        chip.addView(name);

        wireClick(context, chip, container, variant, callback);
        return chip;
    }

    private static LinearLayout baseChip(Context context, LyricAnimStyle.Variant variant, boolean selected) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setTag(variant);
        // The live word preview pops past its own bounds; don't slice it.
        chip.setClipChildren(false);
        chip.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(8 * density));
        chip.setLayoutParams(lp);
        styleChip(chip, selected);
        return chip;
    }

    private static void styleChip(View chip, boolean selected) {
        float density = chip.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * density);
        bg.setColor(selected ? COLOR_FILL_SELECTED : COLOR_FILL);
        bg.setStroke(Math.round((selected ? 1.5f : 1f) * density),
                selected ? COLOR_ACCENT : COLOR_STROKE);
        chip.setBackground(bg);

        // Keep the label colour in step with the selection state.
        if (chip instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) chip;
            View last = ll.getChildAt(ll.getChildCount() - 1);
            if (last instanceof TextView && !(last instanceof GradientWordView)) {
                ((TextView) last).setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT_DIM);
            }
        }
    }

    private static void wireClick(Context context, View chip, LinearLayout container,
                                  LyricAnimStyle.Variant variant, OnStyleChosen callback) {
        chip.setOnClickListener(v -> {
            SharedPreferences prefs =
                    context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
            prefs.edit().putString(LyricAnimStyle.PREF_KEY, variant.key).apply();
            refresh(container, variant.id);
            if (callback != null) callback.onChosen(variant);
        });
        FontChips.addPressFeedback(chip);
    }

    /**
     * Drives a GradientWordView with a looping fake playback position: the word is
     * "sung" over PREVIEW_WORD_MS, rests, then restarts. The loop stops when the
     * view detaches from the window.
     */
    private static void attachPreviewLoop(GradientWordView view, int styleId) {
        Handler handler = new Handler(Looper.getMainLooper());
        final long[] cycleStart = {0};
        final long[] lastTick = {0};

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!view.isAttachedToWindow()) return;
                // Window hidden (activity paused): keep the loop alive but idle cheaply.
                if (!view.isShown()) {
                    lastTick[0] = 0;
                    cycleStart[0] = 0;
                    handler.postDelayed(this, 250);
                    return;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                if (cycleStart[0] == 0) {
                    cycleStart[0] = now;
                    lastTick[0] = now;
                    view.setTiming(0, PREVIEW_WORD_MS);
                }
                long position = now - cycleStart[0];
                if (position > PREVIEW_WORD_MS + PREVIEW_PAUSE_MS) {
                    cycleStart[0] = now;
                    lastTick[0] = now;
                    position = 0;
                    // Re-arm letter emphasis for the new cycle.
                    view.resetState();
                    view.setAnimStyle(styleId);
                    view.setTiming(0, PREVIEW_WORD_MS);
                    view.initLetterEmphasis("Lyric", 0, PREVIEW_WORD_MS);
                }
                double dt = Math.min(0.1, Math.max(0.001, (now - lastTick[0]) / 1000.0));
                lastTick[0] = now;
                view.updateState(position, dt);
                handler.postDelayed(this, 33);
            }
        };

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                cycleStart[0] = 0;
                handler.post(tick);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(tick);
            }
        });
        if (view.isAttachedToWindow()) {
            handler.post(tick);
        }
    }
}
