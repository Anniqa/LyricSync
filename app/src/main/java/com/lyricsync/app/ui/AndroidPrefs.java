package com.lyricsync.app.ui;

import android.content.SharedPreferences;

/** SharedPreferences adapter so AnimSelection stays android-free and JVM-testable. */
public final class AndroidPrefs implements AnimSelection.Prefs {
    private final SharedPreferences prefs;

    public AndroidPrefs(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override public String get(String key) { return prefs.getString(key, null); }
    @Override public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
    @Override public void remove(String key) { prefs.edit().remove(key).apply(); }
}
