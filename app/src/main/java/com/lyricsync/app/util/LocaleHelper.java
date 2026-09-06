package com.lyricsync.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Utility for managing application language selection (English vs Indonesian).
 * Defaults strictly to English ('en') unless explicitly selected by user.
 */
public final class LocaleHelper {

    public static final String PREF_LANGUAGE_KEY = "pref_app_language";
    public static final String LANG_EN = "en";
    public static final String LANG_ID = "id";

    private LocaleHelper() {}

    /**
     * Gets current selected language code ("en" or "id"). Defaults to "en".
     */
    public static String getLanguage(Context context) {
        if (context == null) return LANG_EN;
        SharedPreferences prefs = context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
        return prefs.getString(PREF_LANGUAGE_KEY, LANG_EN);
    }

    /**
     * Updates selected language in SharedPreferences and returns localized Context.
     */
    public static Context setLanguage(Context context, String langCode) {
        if (context == null) return null;
        String validCode = LANG_ID.equalsIgnoreCase(langCode) ? LANG_ID : LANG_EN;
        SharedPreferences prefs = context.getSharedPreferences("lyricsync", Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_LANGUAGE_KEY, validCode).apply();
        return applyLocale(context);
    }

    /**
     * Wraps context with the configured Locale configuration.
     */
    public static Context applyLocale(Context context) {
        if (context == null) return null;
        String langCode = getLanguage(context);
        Locale locale = new Locale(langCode);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.locale = locale;
        try {
            res.updateConfiguration(config, res.getDisplayMetrics());
        } catch (Throwable ignored) {}

        try {
            java.lang.reflect.Method method = Context.class.getMethod("createConfigurationContext", Configuration.class);
            return (Context) method.invoke(context, config);
        } catch (Throwable ignored) {
            return context;
        }
    }
}
