package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.lyricsync.app.R;
import com.lyricsync.app.lyrics.translation.AiTranslationService;
import com.lyricsync.app.lyrics.translation.GoogleTranslateService;
import com.lyricsync.app.util.Haptics;

import java.util.Arrays;

/** Translation card: on/off switch, target-language dropdown, AI-translation switch. */
public final class TranslationSection {

    public TranslationSection(Activity activity, SharedPreferences prefs) {
        MaterialSwitch main = activity.findViewById(R.id.switch_main_translation);
        main.setChecked(prefs.getBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, false));
        main.setOnCheckedChangeListener((btn, checked) -> {
            Haptics.tick(btn);
            prefs.edit().putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, checked).apply();
        });
        activity.findViewById(R.id.row_main_translation).setOnClickListener(v -> main.toggle());

        MaterialSwitch ai = activity.findViewById(R.id.switch_ai_translation);
        ai.setChecked(prefs.getBoolean(AiTranslationService.PREF_AI_TRANSLATION_ENABLED, true));
        ai.setOnCheckedChangeListener((btn, checked) -> {
            Haptics.tick(btn);
            prefs.edit().putBoolean(AiTranslationService.PREF_AI_TRANSLATION_ENABLED, checked).apply();
        });
        activity.findViewById(R.id.row_ai_translation).setOnClickListener(v -> ai.toggle());

        String[] names = activity.getResources().getStringArray(R.array.main_target_lang_names);
        MaterialAutoCompleteTextView dropdown = activity.findViewById(R.id.dropdown_target_lang);
        dropdown.setAdapter(new AllItemsAdapter(activity, names));
        int current = MainPrefs.targetLangIndex(prefs.getString(
                GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG));
        if (current < names.length) dropdown.setText(names[current], false);
        dropdown.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= MainPrefs.TARGET_LANG_CODES.length) return;
            Haptics.tick(dropdown);
            prefs.edit().putString(GoogleTranslateService.PREF_TARGET_LANG,
                    MainPrefs.TARGET_LANG_CODES[position]).apply();
        });
    }

    /**
     * An exposed dropdown is a picker, not a search box: never filter the list by the
     * current text, or after one selection the menu would show only that item.
     */
    private static final class AllItemsAdapter extends ArrayAdapter<String> {
        private final String[] items;
        private final Filter noFilter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults r = new FilterResults();
                r.values = Arrays.asList(items);
                r.count = items.length;
                return r;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };

        AllItemsAdapter(Context context, String[] items) {
            super(context, R.layout.item_dropdown_option, items);
            this.items = items;
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return noFilter;
        }
    }
}
