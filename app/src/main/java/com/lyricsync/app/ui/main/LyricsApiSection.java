package com.lyricsync.app.ui.main;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.TextView;
import android.widget.Toast;

import com.lyricsync.app.R;
import com.lyricsync.app.lyrics.provider.SpicyLyricsProvider;
import com.lyricsync.app.util.Haptics;

/** Syllable-lyrics API card: optional user Spicy Lyrics client key. */
public final class LyricsApiSection {

    private final Activity activity;
    private final SharedPreferences prefs;
    private final TextView status;

    public LyricsApiSection(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        status = activity.findViewById(R.id.tv_spicy_key_status);
        activity.findViewById(R.id.btn_spicy_key).setOnClickListener(v -> {
            Haptics.tick(v);
            showKeyDialog();
        });
        updateState();
    }

    private void showKeyDialog() {
        String existing = prefs.getString(SpicyLyricsProvider.PREF_SPICY_CLIENT_KEY, "");
        MainUi.textInputDialog(activity, R.string.spicy_key_dialog_title, R.string.spicy_key_dialog_msg,
                R.string.spicy_key_hint, existing, false, R.string.spicy_key_save, value -> {
                    if (value.isEmpty()) {
                        prefs.edit().remove(SpicyLyricsProvider.PREF_SPICY_CLIENT_KEY).apply();
                        Toast.makeText(activity, R.string.spicy_key_removed_toast, Toast.LENGTH_SHORT).show();
                    } else if (MainPrefs.isValidSpicyKey(value)) {
                        prefs.edit().putString(SpicyLyricsProvider.PREF_SPICY_CLIENT_KEY, value).apply();
                        Toast.makeText(activity, R.string.spicy_key_saved_toast, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity, R.string.spicy_key_invalid_toast, Toast.LENGTH_LONG).show();
                    }
                    updateState();
                });
    }

    private void updateState() {
        String key = prefs.getString(SpicyLyricsProvider.PREF_SPICY_CLIENT_KEY, "");
        boolean set = key != null && !key.trim().isEmpty();
        status.setText(set ? R.string.spicy_key_status_set : R.string.spicy_key_status_none);
    }
}
