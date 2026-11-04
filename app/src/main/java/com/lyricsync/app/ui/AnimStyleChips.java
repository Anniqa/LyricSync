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
 *
 * Cost control: one shared 30 fps clock updates every preview in the same frame (each
 * preview on its own timer made the screen redraw at the display rate); only previews
 * that are on screen animate; choosing a chip restyles the rows and restarts the
 * previews instead of rebuilding ~30 animated views.
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
    /** Long enough for after-word effects (fireworks ~1.1s, petals) to finish before the loop restarts. */
    private static final long PREVIEW_PAUSE_MS = 1400;

    public interface OnComboChosen {
        void onChosen(String motionKey, String effectKey);
    }

    /** Resolves the config a preview should render; re-called every preview cycle. */
    private interface ConfigSupplier {
        AnimConfig get();
    }

    /**
     * One clock for all previews of a picker. They update in the same message, so the
     * screen redraws at 30 fps whatever the number of chips; with nothing on screen it
     * idles at 4 Hz. {@code generation} bumps restart every preview with the new combo.
     */
    private static final class Ticker implements Runnable {
        final Handler handler = new Handler(Looper.getMainLooper());
        final java.util.List<Preview> previews = new java.util.ArrayList<>();
        int generation;

        void add(Preview p) {
            previews.add(p);
            if (previews.size() == 1) handler.post(this);
        }

        void remove(Preview p) {
            previews.remove(p);
            if (previews.isEmpty()) handler.removeCallbacks(this);
        }

        @Override
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            boolean any = false;
            for (int i = 0; i < previews.size(); i++) any |= previews.get(i).tick(now, generation);
            handler.postDelayed(this, any ? 33 : 250);
        }
    }

    /** One chip's word, sung over PREVIEW_WORD_MS, a rest, then again with the current combo. */
    private static final class Preview {
        final GradientWordView view;
        final String word;
        final ConfigSupplier config;
        final android.graphics.Rect visible = new android.graphics.Rect();
        long cycleStart, lastTick;
        int seen = -1;

        Preview(GradientWordView view, String word, ConfigSupplier config) {
            this.view = view;
            this.word = word;
            this.config = config;
        }

        /** Returns false (and rewinds) while the chip is off screen. */
        boolean tick(long now, int generation) {
            if (view.getWindowVisibility() != View.VISIBLE || !view.isShown() || !view.getGlobalVisibleRect(visible)) {
                cycleStart = 0;
                return false;
            }
            long position = now - cycleStart;
            if (cycleStart == 0 || seen != generation || position > PREVIEW_WORD_MS + PREVIEW_PAUSE_MS) {
                seen = generation;
                cycleStart = now;
                lastTick = now;
                position = 0;
                view.resetState();
                view.setAnimConfig(config.get());
                view.setTiming(0, PREVIEW_WORD_MS);
                view.initLetterEmphasis(word, 0, PREVIEW_WORD_MS);
            }
            double dt = Math.min(0.1, Math.max(0.001, (now - lastTick) / 1000.0));
            lastTick = now;
            view.updateState(position, dt);
            return true;
        }
    }

    /** Full two-row chips with animated previews (main activity). */
    public static void buildWithPreviews(Context context, LinearLayout motionRow,
                                         LinearLayout effectRow, TextView comboLabel,
                                         AnimSelection.Prefs prefs, OnComboChosen callback) {
        String motionKey = AnimSelection.motionKey(prefs);
        String effectKey = AnimSelection.effectKey(prefs);
        Ticker gen = new Ticker();
        String word = context.getString(com.lyricsync.app.R.string.anim_preview_word);
        // A choice restyles both rows and restarts every preview; nothing is rebuilt.
        Runnable rebuild = () -> {
            refresh(motionRow, AnimSelection.motionKey(prefs));
            refresh(effectRow, AnimSelection.effectKey(prefs));
            updateComboLabel(comboLabel, AnimSelection.motionKey(prefs), AnimSelection.effectKey(prefs));
            gen.generation++;
        };

        motionRow.removeAllViews();
        // Previews animate past their bounds; keep the container from slicing them.
        motionRow.setClipChildren(false);
        motionRow.setClipToPadding(false);
        for (AnimStyleDef def : AnimStyleDef.Registry.motionStyles()) {
            motionRow.addView(makePreviewChip(context, def, def.key.equals(motionKey), word, gen,
                    () -> AnimConfig.combine(def,
                            AnimStyleDef.Registry.effectByKey(AnimSelection.effectKey(prefs))),
                    v -> choose(prefs, def.key, null, callback, rebuild)));
        }

        effectRow.removeAllViews();
        effectRow.setClipChildren(false);
        effectRow.setClipToPadding(false);
        for (AnimStyleDef def : AnimStyleDef.Registry.effectStyles()) {
            effectRow.addView(makePreviewChip(context, def, def.key.equals(effectKey), word, gen,
                    () -> AnimConfig.combine(
                            AnimStyleDef.Registry.motionByKey(AnimSelection.motionKey(prefs)), def),
                    v -> choose(prefs, null, def.key, callback, rebuild)));
        }

        updateComboLabel(comboLabel, motionKey, effectKey);
        revealSelected(motionRow, motionKey);
        revealSelected(effectRow, effectKey);
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
        label.setText(effect == AnimStyleDef.Registry.none() ? motion.displayName
                : label.getContext().getString(com.lyricsync.app.R.string.lyrics_full_combo,
                        motion.displayName, effect.displayName));
    }

    private static View makePreviewChip(Context context, AnimStyleDef def, boolean selected,
                                        String word, Ticker gen, ConfigSupplier configSupplier,
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
        preview.setText(word);
        preview.setWordStyle(17f, PREVIEW_INACTIVE, pair.bold);
        preview.initLetterEmphasis(word, 0, PREVIEW_WORD_MS);
        chip.addView(preview);

        TextView name = new TextView(context);
        name.setText(label(def));
        name.setTextSize(10.5f);
        markCombo(name, def);
        // Track the selection: styleChip() runs before the label exists, so the
        // selected-state accent has to be applied here.
        name.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT_DIM);
        name.setPadding(0, Math.round(5 * density), 0, 0);
        chip.addView(name);

        attachPreviewLoop(preview, word, gen, configSupplier);
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
        name.setText(label(def));
        name.setTextSize(11);
        markCombo(name, def);
        name.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        chip.addView(name);

        chip.setOnClickListener(onChoose);
        FontChips.addPressFeedback(chip);
        return chip;
    }

    /** Chip label; combination effects carry the ls_ic_combo icon (see {@link #markCombo}). */
    private static String label(AnimStyleDef def) {
        return def.displayName;
    }

    /** Small two-diamond icon before a combination effect's name. */
    private static void markCombo(TextView name, AnimStyleDef def) {
        if (!def.isCombo()) return;
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(
                name.getContext(), com.lyricsync.app.R.drawable.ls_ic_combo);
        if (d == null) return;
        d = d.mutate();
        int px = Math.round(name.getTextSize() * 0.95f);
        d.setBounds(0, 0, px, px);
        d.setTint(0xFFFFB547);
        name.setCompoundDrawablesRelative(d, null, null, null);
        name.setCompoundDrawablePadding(Math.round(4 * name.getResources().getDisplayMetrics().density));
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

    /** Joins the picker's shared clock while the chip is attached. */
    private static void attachPreviewLoop(GradientWordView view, String word, Ticker ticker,
                                          ConfigSupplier configSupplier) {
        Preview p = new Preview(view, word, configSupplier);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                p.cycleStart = 0;
                ticker.add(p);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                ticker.remove(p);
            }
        });
        if (view.isAttachedToWindow()) ticker.add(p);
    }

    /** Scrolls a chip row so its selected chip is in view (after layout). */
    private static void revealSelected(LinearLayout row, String key) {
        if (!(row.getParent() instanceof android.widget.HorizontalScrollView)) return;
        android.widget.HorizontalScrollView scroll = (android.widget.HorizontalScrollView) row.getParent();
        row.post(() -> {
            for (int i = 0; i < row.getChildCount(); i++) {
                View chip = row.getChildAt(i);
                if (chip.getTag() instanceof AnimStyleDef && ((AnimStyleDef) chip.getTag()).key.equals(key)) {
                    int x = chip.getLeft() - (scroll.getWidth() - chip.getWidth()) / 2;
                    scroll.scrollTo(Math.max(0, x), 0);
                    return;
                }
            }
        });
    }
}
