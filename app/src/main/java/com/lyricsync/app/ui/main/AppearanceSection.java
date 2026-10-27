package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.slider.Slider;
import com.lyricsync.app.R;
import com.lyricsync.app.ui.AndroidPrefs;
import com.lyricsync.app.ui.AnimStyleChips;
import com.lyricsync.app.ui.FontChips;
import com.lyricsync.app.util.AppFont;
import com.lyricsync.app.util.Haptics;

/** Typography & effects card plus the overlay dimensions card. */
public final class AppearanceSection {

    public AppearanceSection(Activity activity, SharedPreferences prefs) {
        LinearLayout fontChips = activity.findViewById(R.id.font_chips);
        LinearLayout animChips = activity.findViewById(R.id.anim_chips);
        LinearLayout effectChips = activity.findViewById(R.id.anim_effect_chips);
        TextView comboLabel = activity.findViewById(R.id.anim_combo_label);

        FontChips.build(activity, fontChips, AppFont.currentKey(prefs), false,
                style -> Haptics.tick(fontChips));
        AnimStyleChips.buildWithPreviews(activity, animChips, effectChips, comboLabel,
                new AndroidPrefs(prefs), (m, e) -> Haptics.tick(animChips));

        MainUi.IntFormatter percent = v -> activity.getString(R.string.main_percent, v);

        TextView fontLabel = activity.findViewById(R.id.font_size_label);
        int font = MainPrefs.fontPercent(prefs.getFloat(MainPrefs.FONT_SCALE, MainPrefs.FONT_SCALE_DEFAULT));
        fontLabel.setText(percent.format(font));
        MainUi.bindSlider(activity.findViewById(R.id.font_size_slider), font, percent, (v, user) -> {
            int value = MainPrefs.clamp(v, MainPrefs.FONT_PERCENT_MIN, MainPrefs.FONT_PERCENT_MAX);
            fontLabel.setText(percent.format(value));
            prefs.edit().putFloat(MainPrefs.FONT_SCALE, value / 100f).apply();
        });

        TextView widthLabel = activity.findViewById(R.id.overlay_width_label);
        int width = MainPrefs.widthPercent(prefs.getInt(MainPrefs.WIDTH_PERCENT, MainPrefs.WIDTH_DEFAULT));
        widthLabel.setText(percent.format(width));
        Slider widthSlider = activity.findViewById(R.id.overlay_width_slider);
        MainUi.bindSlider(widthSlider, width, percent, (v, user) -> {
            int value = MainPrefs.widthPercent(v);
            widthLabel.setText(percent.format(value));
            prefs.edit().putInt(MainPrefs.WIDTH_PERCENT, value).apply();
        });

        TextView heightLabel = activity.findViewById(R.id.overlay_height_label);
        int height = MainPrefs.heightPercent(prefs.getInt(MainPrefs.HEIGHT_PERCENT, MainPrefs.HEIGHT_DEFAULT));
        heightLabel.setText(percent.format(height));
        MainUi.bindSlider(activity.findViewById(R.id.overlay_height_slider), height, percent, (v, user) -> {
            int value = MainPrefs.heightPercent(v);
            heightLabel.setText(percent.format(value));
            prefs.edit().putInt(MainPrefs.HEIGHT_PERCENT, value).apply();
        });
    }
}
