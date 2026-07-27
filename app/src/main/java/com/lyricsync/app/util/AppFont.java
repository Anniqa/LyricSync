package com.lyricsync.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for the lyric typefaces the user can pick. Each style is a
 * bold/medium pair loaded lazily from assets (or the system typeface) and cached
 * for the process lifetime. Loading is defensive: a corrupt or missing asset
 * falls back to the default style, then to the system font, never to a crash.
 */
public final class AppFont {
    private AppFont() {}

    public static final String PREF_KEY = "font_family";
    public static final String DEFAULT_KEY = "montserrat";

    public static final class Style {
        public final String key;
        public final String displayName;
        final String boldAsset;
        final String mediumAsset;

        Style(String key, String displayName, String boldAsset, String mediumAsset) {
            this.key = key;
            this.displayName = displayName;
            this.boldAsset = boldAsset;
            this.mediumAsset = mediumAsset;
        }
    }

    public static final class FontPair {
        public final Typeface bold;
        public final Typeface medium;

        FontPair(Typeface bold, Typeface medium) {
            this.bold = bold;
            this.medium = medium;
        }
    }

    private static final List<Style> STYLES;
    private static final Map<String, FontPair> cache = new HashMap<>();

    static {
        List<Style> styles = new ArrayList<>();
        styles.add(new Style(DEFAULT_KEY, "Montserrat",
                "fonts/lyrics_font.ttf", "fonts/lyrics_font_medium.ttf"));
        styles.add(new Style("poppins", "Poppins",
                "fonts/font_poppins_bold.ttf", "fonts/font_poppins_medium.ttf"));
        styles.add(new Style("barlow", "Barlow",
                "fonts/font_barlow_bold.ttf", "fonts/font_barlow_medium.ttf"));
        styles.add(new Style("lato", "Lato",
                "fonts/font_lato_bold.ttf", "fonts/font_lato_medium.ttf"));
        styles.add(new Style("system", "System", null, null));
        STYLES = Collections.unmodifiableList(styles);
    }

    public static List<Style> styles() {
        return STYLES;
    }

    public static Style byKey(String key) {
        if (key != null) {
            for (Style s : STYLES) {
                if (s.key.equals(key)) return s;
            }
        }
        return STYLES.get(0);
    }

    public static String currentKey(SharedPreferences prefs) {
        return byKey(prefs.getString(PREF_KEY, DEFAULT_KEY)).key;
    }

    /** The pair for the user's current preference. */
    public static FontPair current(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
        return get(context, currentKey(prefs));
    }

    /** The pair for an explicit style key, cached. Never returns null. */
    public static synchronized FontPair get(Context context, String key) {
        Style style = byKey(key);
        FontPair cached = cache.get(style.key);
        if (cached != null) return cached;

        FontPair pair = load(context.getApplicationContext(), style);
        cache.put(style.key, pair);
        return pair;
    }

    private static FontPair load(Context context, Style style) {
        if (style.boldAsset == null) {
            return new FontPair(
                    Typeface.create("sans-serif", Typeface.BOLD),
                    Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        Typeface bold = tryLoad(context, style.boldAsset);
        Typeface medium = tryLoad(context, style.mediumAsset);
        if (bold == null && medium == null && !DEFAULT_KEY.equals(style.key)) {
            // Whole family unreadable — degrade to the bundled default, then system.
            return load(context, byKey(DEFAULT_KEY));
        }
        if (bold == null) bold = Typeface.create("sans-serif", Typeface.BOLD);
        if (medium == null) medium = bold;
        return new FontPair(bold, medium);
    }

    private static Typeface tryLoad(Context context, String asset) {
        try {
            return Typeface.createFromAsset(context.getAssets(), asset);
        } catch (Exception e) {
            AppLog.w("AppFont", "Failed to load " + asset + ": " + e.getMessage());
            return null;
        }
    }
}
