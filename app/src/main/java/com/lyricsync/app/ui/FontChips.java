package com.lyricsync.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lyricsync.app.util.AppFont;

/**
 * Builds the horizontal font-picker chip row shared by the main activity and the
 * overlay settings panel. Each chip previews the real typeface ("Aa" rendered in
 * that font) and persists the choice straight into SharedPreferences so the
 * overlay picks it up live through its preference listener.
 */
public final class FontChips {
    private FontChips() {}

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xB8FFFFFF;
    private static final int COLOR_ACCENT = 0xFF1ED760;
    private static final int COLOR_FILL = 0x10FFFFFF;
    private static final int COLOR_FILL_SELECTED = 0x291ED760;
    private static final int COLOR_STROKE = 0x24FFFFFF;

    public interface OnFontChosen {
        void onChosen(AppFont.Style style);
    }

    /** Inflate a full set of chips into {@code container}. */
    public static void build(Context context, LinearLayout container,
                             String selectedKey, boolean compact, OnFontChosen callback) {
        container.removeAllViews();
        for (AppFont.Style style : AppFont.styles()) {
            container.addView(makeChip(context, container, style, selectedKey, compact, callback));
        }
    }

    /** Re-tint chips after the selection changed elsewhere (e.g. from the overlay). */
    public static void refresh(LinearLayout container, String selectedKey) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View chip = container.getChildAt(i);
            Object tag = chip.getTag();
            if (tag instanceof AppFont.Style) {
                styleChip(chip, (AppFont.Style) tag, ((AppFont.Style) tag).key.equals(selectedKey));
            }
        }
    }

    private static View makeChip(Context context, LinearLayout container, AppFont.Style style,
                                 String selectedKey, boolean compact, OnFontChosen callback) {
        float density = context.getResources().getDisplayMetrics().density;
        int padH = Math.round((compact ? 10 : 14) * density);
        int padV = Math.round((compact ? 6 : 9) * density);

        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(padH, padV, padH, padV);
        chip.setMinimumWidth(Math.round((compact ? 52 : 64) * density));
        chip.setTag(style);

        TextView preview = new TextView(context);
        preview.setText("Aa");
        preview.setTextSize(compact ? 15 : 18);
        preview.setIncludeFontPadding(false);
        chip.addView(preview);

        TextView name = new TextView(context);
        name.setText(style.displayName);
        name.setTextSize(compact ? 9 : 10.5f);
        name.setTextColor(COLOR_TEXT_DIM);
        name.setPadding(0, Math.round(2 * density), 0, 0);
        chip.addView(name);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(8 * density));
        chip.setLayoutParams(lp);

        styleChip(chip, style, style.key.equals(selectedKey));

        chip.setOnClickListener(v -> {
            SharedPreferences prefs =
                    context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
            prefs.edit().putString(AppFont.PREF_KEY, style.key).apply();
            refresh(container, style.key);
            if (callback != null) callback.onChosen(style);
        });
        addPressFeedback(chip);
        return chip;
    }

    private static void styleChip(View chip, AppFont.Style style, boolean selected) {
        Context context = chip.getContext();
        float density = context.getResources().getDisplayMetrics().density;

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * density);
        bg.setColor(selected ? COLOR_FILL_SELECTED : COLOR_FILL);
        bg.setStroke(Math.round((selected ? 1.5f : 1f) * density),
                selected ? COLOR_ACCENT : COLOR_STROKE);
        chip.setBackground(bg);

        // The "Aa" preview is always rendered in the chip's own typeface.
        if (chip instanceof LinearLayout && ((LinearLayout) chip).getChildCount() > 0) {
            View first = ((LinearLayout) chip).getChildAt(0);
            if (first instanceof TextView) {
                TextView preview = (TextView) first;
                AppFont.FontPair pair = AppFont.get(context, style.key);
                preview.setTypeface(pair.bold, Typeface.BOLD);
                preview.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT);
            }
        }
    }

    /** Small helper so ripple-less chips still react visibly to touch. */
    public static void addPressFeedback(View chip) {
        chip.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                            .setInterpolator(Anim.BUBBLE).start();
                    break;
            }
            return false;
        });
    }
}
