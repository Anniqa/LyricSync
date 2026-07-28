package com.lyricsync.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.AnimStyleDef;
import com.lyricsync.app.renderer.GradientWordView;
import com.lyricsync.app.util.AppFont;

/**
 * Builds the two-row lyric-animation combo picker: one row of MOTION chips and one
 * row of EFFECT chips. In the main activity each chip carries a LIVE preview — a
 * tiny GradientWordView driven by a looping fake playback clock, rendering the REAL
 * combination (chip × the other row's current selection) so the user sees exactly
 * what they'll get. The overlay panel uses the compact text-only form.
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

    public interface OnComboChosen {
        void onChosen(String motionKey, String effectKey);
    }

    /** Resolves the config a preview should render; re-called every preview cycle. */
    private interface ConfigSupplier {
        AnimConfig get();
    }

    /** Full two-row chips with animated previews (main activity). */
    public static void buildWithPreviews(Context context, LinearLayout motionRow,
                                         LinearLayout effectRow, TextView comboLabel,
                                         AnimSelection.Prefs prefs, OnComboChosen callback) {
        String motionKey = AnimSelection.motionKey(prefs);
        String effectKey = AnimSelection.effectKey(prefs);
        Runnable rebuild = () -> buildWithPreviews(context, motionRow, effectRow,
                comboLabel, prefs, callback);

        motionRow.removeAllViews();
        // Previews animate past their bounds; keep the container from slicing them.
        motionRow.setClipChildren(false);
        motionRow.setClipToPadding(false);
        for (AnimStyleDef def : AnimStyleDef.Registry.motionStyles()) {
            motionRow.addView(makePreviewChip(context, def, def.key.equals(motionKey),
                    () -> AnimConfig.combine(def,
                            AnimStyleDef.Registry.effectByKey(AnimSelection.effectKey(prefs))),
                    v -> choose(prefs, def.key, null, callback, rebuild)));
        }

        effectRow.removeAllViews();
        effectRow.setClipChildren(false);
        effectRow.setClipToPadding(false);
        for (AnimStyleDef def : AnimStyleDef.Registry.effectStyles()) {
            effectRow.addView(makePreviewChip(context, def, def.key.equals(effectKey),
                    () -> AnimConfig.combine(
                            AnimStyleDef.Registry.motionByKey(AnimSelection.motionKey(prefs)), def),
                    v -> choose(prefs, null, def.key, callback, rebuild)));
        }

        updateComboLabel(comboLabel, motionKey, effectKey);
    }

    /** Compact text-only two-row chips (overlay settings panel, no combo label). */
    public static void buildCompact(Context context, LinearLayout motionRow,
                                    LinearLayout effectRow,
                                    AnimSelection.Prefs prefs, OnComboChosen callback) {
        String motionKey = AnimSelection.motionKey(prefs);
        String effectKey = AnimSelection.effectKey(prefs);
        Runnable rebuild = () -> buildCompact(context, motionRow, effectRow, prefs, callback);

        motionRow.removeAllViews();
        for (AnimStyleDef def : AnimStyleDef.Registry.motionStyles()) {
            motionRow.addView(makeCompactChip(context, def, def.key.equals(motionKey),
                    v -> choose(prefs, def.key, null, callback, rebuild)));
        }

        effectRow.removeAllViews();
        for (AnimStyleDef def : AnimStyleDef.Registry.effectStyles()) {
            effectRow.addView(makeCompactChip(context, def, def.key.equals(effectKey),
                    v -> choose(prefs, null, def.key, callback, rebuild)));
        }
    }

    /** Re-styles one row's chips against that row's current key (cheap prefs sync). */
    public static void refresh(LinearLayout row, String currentKey) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View chip = row.getChildAt(i);
            Object tag = chip.getTag();
            if (tag instanceof AnimStyleDef) {
                styleChip(chip, ((AnimStyleDef) tag).key.equals(currentKey));
            }
        }
    }

    /** Persist the tapped slot, rebuild both rows so previews/selections track it. */
    private static void choose(AnimSelection.Prefs prefs, String newMotion, String newEffect,
                               OnComboChosen callback, Runnable rebuild) {
        String motion = newMotion != null ? newMotion : AnimSelection.motionKey(prefs);
        String effect = newEffect != null ? newEffect : AnimSelection.effectKey(prefs);
        AnimSelection.save(prefs, motion, effect);
        rebuild.run();
        if (callback != null) callback.onChosen(motion, effect);
    }

    private static void updateComboLabel(TextView label, String motionKey, String effectKey) {
        if (label == null) return;
        AnimStyleDef motion = AnimStyleDef.Registry.motionByKey(motionKey);
        AnimStyleDef effect = AnimStyleDef.Registry.effectByKey(effectKey);
        label.setText(effect == AnimStyleDef.Registry.none()
                ? motion.displayName
                : motion.displayName + " × " + effect.displayName);
    }

    private static View makePreviewChip(Context context, AnimStyleDef def, boolean selected,
                                        ConfigSupplier configSupplier,
                                        View.OnClickListener onChoose) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout chip = baseChip(context, def, selected);
        chip.setMinimumWidth(Math.round(76 * density));
        chip.setPadding(Math.round(10 * density), Math.round(10 * density),
                Math.round(10 * density), Math.round(8 * density));

        AppFont.FontPair pair = AppFont.current(context);
        GradientWordView preview = new GradientWordView(context);
        preview.setAnimConfig(configSupplier.get());
        preview.setWordIndex(0);
        // The preview is always "the active line" so the Wave ripple is visible.
        preview.setLineActive(true);
        preview.setText("Lyric");
        preview.setWordStyle(17f, PREVIEW_INACTIVE, pair.bold);
        preview.initLetterEmphasis("Lyric", 0, PREVIEW_WORD_MS);
        chip.addView(preview);

        TextView name = new TextView(context);
        name.setText(def.displayName);
        name.setTextSize(10.5f);
        name.setTextColor(COLOR_TEXT_DIM);
        name.setPadding(0, Math.round(5 * density), 0, 0);
        chip.addView(name);

        attachPreviewLoop(preview, configSupplier);
        chip.setOnClickListener(onChoose);
        FontChips.addPressFeedback(chip);
        return chip;
    }

    private static View makeCompactChip(Context context, AnimStyleDef def, boolean selected,
                                        View.OnClickListener onChoose) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout chip = baseChip(context, def, selected);
        chip.setPadding(Math.round(11 * density), Math.round(7 * density),
                Math.round(11 * density), Math.round(7 * density));

        TextView name = new TextView(context);
        name.setText(def.displayName);
        name.setTextSize(11);
        name.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        chip.addView(name);

        chip.setOnClickListener(onChoose);
        FontChips.addPressFeedback(chip);
        return chip;
    }

    private static LinearLayout baseChip(Context context, AnimStyleDef def, boolean selected) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setTag(def);
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

    /**
     * Drives a GradientWordView with a looping fake playback position: the word is
     * "sung" over PREVIEW_WORD_MS, rests, then restarts. The config is re-resolved
     * every cycle so the preview always shows the chip × the other row's CURRENT
     * selection. The loop stops when the view detaches from the window.
     */
    private static void attachPreviewLoop(GradientWordView view, ConfigSupplier configSupplier) {
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
                    // Re-arm with the freshly resolved combo for the new cycle.
                    view.resetState();
                    view.setAnimConfig(configSupplier.get());
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
