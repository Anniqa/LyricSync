package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.TextView;

import com.google.android.material.slider.Slider;
import com.lyricsync.app.R;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.util.Haptics;

/**
 * Sync offset card: big signed read-out, meaning line, slider with ±10 ms fine-tune
 * buttons and Reset. The slider value IS the offset (−1500…1500 ms).
 */
public final class SyncSection {

    private final Activity activity;
    private final TextView value;
    private final TextView meaning;

    public SyncSection(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        value = activity.findViewById(R.id.sync_offset_label);
        meaning = activity.findViewById(R.id.sync_offset_meaning);
        Slider slider = activity.findViewById(R.id.sync_offset_slider);

        int saved = MainPrefs.syncOffset(prefs.getLong(MainPrefs.SYNC_OFFSET_MS, 0));
        show(saved);
        MainUi.bindSlider(slider, saved, this::spoken, (v, user) -> {
            int offset = MainPrefs.syncOffset(v);
            show(offset);
            prefs.edit().putLong(MainPrefs.SYNC_OFFSET_MS, offset).apply();
        });

        activity.findViewById(R.id.btn_sync_minus).setOnClickListener(v -> {
            Haptics.tick(v);
            MainUi.setSlider(slider, Math.round(slider.getValue()) - MainPrefs.SYNC_NUDGE_MS);
        });
        activity.findViewById(R.id.btn_sync_plus).setOnClickListener(v -> {
            Haptics.tick(v);
            MainUi.setSlider(slider, Math.round(slider.getValue()) + MainPrefs.SYNC_NUDGE_MS);
        });
        activity.findViewById(R.id.btn_sync_reset).setOnClickListener(v -> {
            Haptics.tick(v);
            MainUi.setSlider(slider, 0);
            Anim.pop(value);
        });
    }

    private void show(int ms) {
        value.setText(activity.getString(R.string.main_offset_value, MainPrefs.signedOffset(ms)));
        meaning.setText(meaningRes(ms));
    }

    private static int meaningRes(int ms) {
        int dir = MainPrefs.offsetDirection(ms);
        return dir == 0 ? R.string.in_sync : (dir > 0 ? R.string.lyrics_later : R.string.lyrics_earlier);
    }

    /** What TalkBack reads for the slider: "+120 ms, lyrics later". */
    private String spoken(int ms) {
        return activity.getString(R.string.main_offset_value, MainPrefs.signedOffset(ms))
                + ", " + activity.getString(meaningRes(ms));
    }
}
