package com.lyricsync.app.overlay;

/**
 * SharedPreferences keys the overlay reads and writes. The string values are a
 * contract with saved installs and with MainActivity, which writes the same keys —
 * never rename one without a migration.
 */
final class OverlayPrefKeys {
    private OverlayPrefKeys() {}

    static final String FILE = "lyricsync";

    static final String FONT_SCALE = "font_scale";
    static final String WIDTH_PERCENT = "overlay_width_percent";
    static final String HEIGHT_PERCENT = "overlay_height_percent";
    static final String POSITION_X = "overlay_x";
    static final String POSITION_Y = "overlay_y";
    static final String LYRICS_VISIBLE = "lyrics_visible";
    static final String HEADER_COMPACT = "overlay_header_compact";
    static final String SHOW_RESIZE_HANDLE = "overlay_show_resize_handle";
    static final String SYNC_OFFSET_MS = "sync_offset_ms";
    static final String ROMAJI_ENABLED = "pref_romaji_enabled";
    static final String ORIGINAL_SCRIPT_ENABLED = "pref_original_script_enabled";
    static final String AI_MODEL = "pref_ai_model";
    static final String AI_BUBBLE_X = "ai_bubble_x";
    static final String AI_BUBBLE_Y = "ai_bubble_y";
    /** Pre-combination animation key, still accepted from the AI appearance tool. */
    static final String LEGACY_ANIM_STYLE = "anim_style";
}
