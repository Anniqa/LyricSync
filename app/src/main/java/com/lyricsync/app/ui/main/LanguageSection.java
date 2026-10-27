package com.lyricsync.app.ui.main;

import android.app.Activity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.lyricsync.app.R;
import com.lyricsync.app.util.Haptics;
import com.lyricsync.app.util.LocaleHelper;

/** App language segmented toggle (English / Bahasa Indonesia). Switching recreates the activity. */
public final class LanguageSection {

    public LanguageSection(Activity activity) {
        MaterialButtonToggleGroup group = activity.findViewById(R.id.lang_toggle);
        boolean isId = LocaleHelper.LANG_ID.equalsIgnoreCase(LocaleHelper.getLanguage(activity));
        // Seed before listening so the initial check never triggers a recreate.
        group.check(isId ? R.id.btn_lang_id : R.id.btn_lang_en);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            String lang = checkedId == R.id.btn_lang_id ? LocaleHelper.LANG_ID : LocaleHelper.LANG_EN;
            if (lang.equalsIgnoreCase(LocaleHelper.getLanguage(activity))) return;
            Haptics.tick(g);
            LocaleHelper.setLanguage(activity, lang);
            activity.recreate();
        });
    }
}
