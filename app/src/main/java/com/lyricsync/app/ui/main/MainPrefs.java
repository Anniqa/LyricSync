package com.lyricsync.app.ui.main;

/**
 * Preference contract of the main screen: keys, defaults and ranges.
 *
 * <p>The key strings are a contract with saved installs and with the floating overlay,
 * which reads and writes the same keys. Never rename one without a migration.
 * Pure JVM so the clamps and offset formatting stay unit-testable.
 */
public final class MainPrefs {
    private MainPrefs() {}

    public static final String FILE = "lyricsync";

    public static final String FONT_SCALE = "font_scale";
    public static final float FONT_SCALE_DEFAULT = 1.0f;
    /** The slider works in percent: 50..200 % maps to a 0.5..2.0 scale. */
    public static final int FONT_PERCENT_MIN = 50;
    public static final int FONT_PERCENT_MAX = 200;

    public static final String WIDTH_PERCENT = "overlay_width_percent";
    public static final int WIDTH_DEFAULT = 88;
    public static final int WIDTH_MIN = 55;
    public static final int WIDTH_MAX = 100;

    public static final String HEIGHT_PERCENT = "overlay_height_percent";
    public static final int HEIGHT_DEFAULT = 36;
    public static final int HEIGHT_MIN = 20;
    public static final int HEIGHT_MAX = 70;

    public static final String SYNC_OFFSET_MS = "sync_offset_ms";
    public static final int SYNC_LIMIT_MS = 1500;
    /** Step of the fine-tune buttons beside the sync slider. */
    public static final int SYNC_NUDGE_MS = 10;

    public static final String AI_MODEL = "pref_ai_model";
    public static final String AI_SEARCH_MODEL = "pref_ai_search_model";
    public static final String AI_TOTAL_TOKENS = "pref_ai_total_tokens";
    public static final String DEV_EXPANDED = "dev_expanded";

    /** Target-language codes, index-aligned with R.array.main_target_lang_names. */
    public static final String[] TARGET_LANG_CODES = {"auto", "id", "en", "ja", "ko", "es", "zh-CN", "fr", "de"};

    /** Spicy Lyrics client keys look like sl_pk_… or sl_sk_…. */
    public static final String SPICY_KEY_PATTERN = "sl_(pk|sk)_[A-Za-z0-9_-]+";

    public static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    /** Stored font scale → slider percent, clamped to the slider range. */
    public static int fontPercent(float scale) {
        return clamp((int) (scale * 100), FONT_PERCENT_MIN, FONT_PERCENT_MAX);
    }

    public static int widthPercent(int stored) {
        return clamp(stored, WIDTH_MIN, WIDTH_MAX);
    }

    public static int heightPercent(int stored) {
        return clamp(stored, HEIGHT_MIN, HEIGHT_MAX);
    }

    public static int syncOffset(long stored) {
        long c = Math.max(-SYNC_LIMIT_MS, Math.min(SYNC_LIMIT_MS, stored));
        return (int) c;
    }

    /** Signed read-out without unit: "+120", "-40", "0". */
    public static String signedOffset(int ms) {
        return ms > 0 ? "+" + ms : Integer.toString(ms);
    }

    /** -1 lyrics earlier, 0 in sync, +1 lyrics later. */
    public static int offsetDirection(int ms) {
        return Integer.signum(ms);
    }

    /**
     * Snap a Material Slider value into [from, to] on an integer step. Slider throws
     * IllegalStateException for out-of-range or off-step values, and old prefs can hold either.
     */
    public static float sliderValue(float value, float from, float to) {
        float v = Math.round(value);
        return v < from ? from : (v > to ? to : v);
    }

    public static int targetLangIndex(String code) {
        if (code != null) {
            for (int i = 0; i < TARGET_LANG_CODES.length; i++) {
                if (TARGET_LANG_CODES[i].equalsIgnoreCase(code)) return i;
            }
        }
        return 0;
    }

    public static boolean isValidSpicyKey(String value) {
        return value != null && value.matches(SPICY_KEY_PATTERN);
    }

    /** First letter for the account avatar; "G" when there is no usable email. */
    public static String avatarLetter(String email) {
        if (email == null) return "G";
        String t = email.trim();
        if (t.isEmpty()) return "G";
        int cp = t.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp)));
    }
}
