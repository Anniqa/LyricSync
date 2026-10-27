package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolCallback;
import com.lyricsync.app.ai.tools.AntigravityToolExecutor.ToolResult;
import com.lyricsync.app.ai.tools.ToolArgs;
import com.lyricsync.app.renderer.AnimStyleDef;

/**
 * The tools the model drives LyricSync with: their schema, the short detail shown on the
 * tool card while one runs, and the dispatch onto a {@link LyricActionDelegate}.
 *
 * <p>The set is small and general on purpose — read state, read lyrics, edit lyrics with
 * composable ops, undo, plus the app's AI pipelines and display/playback controls — so the
 * model can combine them for any request instead of needing a tool per use case. Web tools
 * are declared here but run by the client, which owns the HTTP stack.
 */
final class LyricTools {

    private LyricTools() {}

    static JsonArray declarations(boolean withWeb) {
        JsonArray d = new JsonArray();
        if (withWeb) {
            d.add(tool("web_search", "Search Google for facts about the song, artist, album, release, credits, "
                            + "interviews or meaning. Returns a summary with sources.",
                    req("query"),
                    str("query", "A specific search query, e.g. '<title> <artist> meaning interview'."),
                    num("num_search_results", "INTEGER", "Max sources to return (default 5).")));
            d.add(tool("web_fetch", "Read a public web page (article, interview, wiki) from a URL found by web_search.",
                    req("url"),
                    str("url", "http(s) URL.")));
        }
        d.add(tool("get_app_state", "Current app state as JSON: track, playback position and active line, lyrics "
                        + "(type, provider, line count, script, romaji/translation present), display settings, "
                        + "available motion/effect styles and undo steps. Call it when you need fresh state.",
                null));
        d.add(tool("read_lyrics", "Read lyric lines as JSON with index, timing, text, romaji and translation. "
                        + "include_words adds each line's words (the timed units word_romaji maps onto 1:1). "
                        + "Max 60 lines per call; 'next_from' tells where to continue.",
                null,
                num("from_line", "INTEGER", "First line index (default 0)."),
                num("to_line", "INTEGER", "Last line index, inclusive (default: to the end)."),
                bool("include_words", "Include the words and current word_romaji of each line (default false).")));
        d.add(editTool());
        d.add(tool("undo_edit", "Undo the last edit_lyrics call, or every AI edit since the song loaded.",
                null,
                bool("all", "true to restore the lyrics as loaded (default false: last edit only).")));
        d.add(tool("translate_lyrics", "Translate the whole song with the app's translator (full song context, "
                        + "professional subtitle style) and show it under each line. Use this for whole-song "
                        + "translations; use edit_lyrics for custom or partial ones.",
                req("target_language"),
                str("target_language", "Language code: 'id', 'en', 'ja', 'ko', 'es', ...")));
        d.add(tool("transliterate_lyrics", "Generate the Latin reading (romaji/pinyin/romaja) for non-Latin lyrics "
                        + "with the app's transliterator, aligned per syllable, and show it.",
                null,
                str("mode", "'romaji_with_translation' (Latin + original + translation), 'romaji_only' (Latin above "
                        + "the original) or 'pure_romaji' (Latin only; original and translation hidden)."),
                bool("show_original_script", "Overrides whether the original script stays visible.")));
        d.add(tool("set_display", "Change any display setting; omitted fields stay as they are.",
                null,
                bool("translation", "Show the translation line."),
                bool("romaji", "Show the Latin layer (romaji line / word_romaji)."),
                bool("original_script", "Show the original lyric text (false = only the Latin layer)."),
                num("font_scale", "NUMBER", "Lyric font scale, 0.6-2.0 (1.0 = default)."),
                num("width_percent", "INTEGER", "Overlay width, % of the screen."),
                num("height_percent", "INTEGER", "Overlay height, % of the screen."),
                num("sync_offset_ms", "INTEGER", "Lyric timing offset in ms (+ = lyrics later, - = earlier), max +-1500."),
                choice("motion", "Word animation motion.", keys(AnimStyleDef.Registry.motionStyles())),
                choice("effect", "Word highlight effect.", keys(AnimStyleDef.Registry.effectStyles()))));
        d.add(tool("control_playback", "Control the music player.",
                req("action"),
                choice("action", "What to do.", "play", "pause", "skip_next", "skip_previous", "seek_to_line", "seek_to_position"),
                num("line_index", "INTEGER", "Target line for seek_to_line."),
                num("position_ms", "INTEGER", "Target position for seek_to_position.")));
        return d;
    }

    /** edit_lyrics: one call carries every change, applied all-or-nothing. */
    private static JsonObject editTool() {
        JsonObject op = new JsonObject();
        op.addProperty("type", "OBJECT");
        op.add("properties", props(
                choice("op", "set: change fields of one line. insert: add a line before index 'line' "
                                + "(line = line count appends). delete: remove 'line'..'to_line'. "
                                + "shift: move timing of 'line'..'to_line' (default all) by 'ms'. "
                                + "replace: find/replace text in 'field' over 'line'..'to_line' (default all).",
                        "set", "insert", "delete", "shift", "replace"),
                num("line", "INTEGER", "Line index (0-based)."),
                num("to_line", "INTEGER", "Range end, inclusive."),
                str("text", "New lyric text (re-splits the words and clears that line's romaji)."),
                str("translation", "Translation line shown under the lyric; '' clears."),
                str("romaji", "Latin line shown with the lyric; any Latin text works (reading, gloss...). '' clears."),
                arr("word_romaji", "One Latin label per word of the line (exactly as many as read_lyrics "
                        + "include_words returns), drawn over each word in time: word-by-word readings or "
                        + "glosses. '' leaves a word blank. Overrides romaji."),
                num("start_ms", "INTEGER", "Line start (set/insert)."),
                num("end_ms", "INTEGER", "Line end (set/insert)."),
                num("ms", "INTEGER", "Shift amount for shift (+ later, - earlier)."),
                str("find", "Text to find for replace (case-insensitive)."),
                str("replace", "Replacement for replace ('' deletes)."),
                choice("field", "Field for replace (default text).", "text", "translation", "romaji", "all")));
        op.add("required", req("op"));

        JsonObject ops = new JsonObject();
        ops.addProperty("type", "ARRAY");
        ops.addProperty("description", "Edit operations, applied in order.");
        ops.add("items", op);
        return tool("edit_lyrics", "Edit the lyrics shown in the overlay: text, romaji line, word_romaji, "
                        + "translation, timing; insert, delete, shift, find/replace. Ops are applied in order "
                        + "and all-or-nothing; an error names the bad op and nothing changes. Batch every "
                        + "change into one call. Changes are cached and undoable.",
                req("ops"), new Object[]{"ops", ops});
    }

    /** Short detail for the tool card; lenient, a bad argument only degrades the label. */
    static String describe(String name, JsonObject a) {
        if (name == null) return "";
        switch (name) {
            case "web_search": return ToolArgs.str(a, "query", "");
            case "web_fetch": return ToolArgs.str(a, "url", "");
            case "read_lyrics": {
                int from = ToolArgs.intValue(a, "from_line", 0) + 1;
                Integer to = ToolArgs.intOrNull(a, "to_line");
                return from + "–" + (to != null && to >= 0 ? String.valueOf(to + 1) : "…");
            }
            case "edit_lyrics": {
                JsonElement ops = a != null ? a.get("ops") : null;
                return String.valueOf(ops != null && ops.isJsonArray() ? ops.getAsJsonArray().size() : 0);
            }
            case "translate_lyrics": return ToolArgs.str(a, "target_language", "id");
            case "transliterate_lyrics": return ToolArgs.str(a, "mode", "romaji");
            case "set_display": {
                StringBuilder sb = new StringBuilder();
                if (a != null) {
                    for (String k : a.keySet()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(k.replace('_', ' ')).append(' ').append(ToolArgs.str(a, k, ""));
                    }
                }
                return sb.toString();
            }
            case "control_playback": {
                String action = ToolArgs.str(a, "action", "");
                Integer line = ToolArgs.intOrNull(a, "line_index");
                return "seek_to_line".equals(action) && line != null ? action + " " + (line + 1) : action;
            }
            default: return "";
        }
    }

    /** Runs a lyric tool on {@code d}; every outcome (including unknown tools) reaches {@code cb}. */
    static void dispatch(LyricActionDelegate d, String name, JsonObject a, ToolCallback cb) {
        if (d == null) {
            cb.onResult(ToolResult.error("The app is not ready (no overlay)."));
            return;
        }
        LyricActionDelegate.ActionCallback reply = new LyricActionDelegate.ActionCallback() {
            @Override
            public void onSuccess(String result) {
                cb.onResult(ToolResult.message(result));
            }

            @Override
            public void onError(String error) {
                cb.onResult(ToolResult.error("Error: " + error));
            }
        };
        switch (name) {
            case "get_app_state":
                d.getState(reply);
                return;
            case "read_lyrics":
                d.readLyrics(ToolArgs.intValue(a, "from_line", 0), ToolArgs.intValue(a, "to_line", -1),
                        ToolArgs.bool(a, "include_words", false), reply);
                return;
            case "edit_lyrics": {
                JsonElement ops = a != null ? a.get("ops") : null;
                if (ops == null || !ops.isJsonArray()) {
                    reply.onError("ops must be an array of edit operations.");
                    return;
                }
                d.editLyrics(ops.getAsJsonArray(), reply);
                return;
            }
            case "undo_edit":
                d.undoEdit(ToolArgs.bool(a, "all", false), reply);
                return;
            case "translate_lyrics":
                d.translateLyrics(ToolArgs.str(a, "target_language", "id"), reply);
                return;
            case "transliterate_lyrics": {
                String mode = ToolArgs.str(a, "mode", "romaji_only");
                // An explicit flag wins; otherwise "pure_romaji"/"latin_only" drop the original script.
                boolean showOriginal = a != null && a.has("show_original_script")
                        ? ToolArgs.bool(a, "show_original_script", true)
                        : !("pure_romaji".equalsIgnoreCase(mode) || "latin_only".equalsIgnoreCase(mode));
                d.transliterateLyrics(mode, showOriginal, reply);
                return;
            }
            case "set_display": {
                LyricActionDelegate.Display s = new LyricActionDelegate.Display();
                s.translation = boolOrNull(a, "translation");
                s.romaji = boolOrNull(a, "romaji");
                s.originalScript = boolOrNull(a, "original_script");
                s.fontScale = ToolArgs.floatOrNull(a, "font_scale");
                s.widthPercent = ToolArgs.intOrNull(a, "width_percent");
                s.heightPercent = ToolArgs.intOrNull(a, "height_percent");
                s.syncOffsetMs = ToolArgs.intOrNull(a, "sync_offset_ms");
                s.motion = ToolArgs.str(a, "motion", null);
                s.effect = ToolArgs.str(a, "effect", null);
                if (s.isEmpty()) {
                    reply.onError("set_display needs at least one setting.");
                    return;
                }
                d.setDisplay(s, reply);
                return;
            }
            case "control_playback":
                d.controlPlayback(ToolArgs.str(a, "action", ""), ToolArgs.intValue(a, "line_index", -1),
                        ToolArgs.longValue(a, "position_ms", 0L), reply);
                return;
            default:
                cb.onResult(ToolResult.error("Unknown tool: " + name));
        }
    }

    private static Boolean boolOrNull(JsonObject a, String key) {
        if (a == null || !a.has(key) || a.get(key).isJsonNull()) return null;
        String v = ToolArgs.str(a, key, "");
        return "true".equalsIgnoreCase(v) ? Boolean.TRUE : "false".equalsIgnoreCase(v) ? Boolean.FALSE : null;
    }

    // ---------------------------------------------------------------- schema builders

    private static String[] keys(java.util.List<AnimStyleDef> styles) {
        String[] k = new String[styles.size()];
        for (int i = 0; i < k.length; i++) k[i] = styles.get(i).key;
        return k;
    }

    private static JsonArray req(String... names) {
        JsonArray r = new JsonArray();
        for (String n : names) r.add(n);
        return r;
    }

    private static Object[] str(String name, String desc) {
        return prop(name, "STRING", desc);
    }

    private static Object[] num(String name, String type, String desc) {
        return prop(name, type, desc);
    }

    private static Object[] bool(String name, String desc) {
        return prop(name, "BOOLEAN", desc);
    }

    private static Object[] choice(String name, String desc, String... values) {
        Object[] p = prop(name, "STRING", desc);
        JsonArray e = new JsonArray();
        for (String v : values) e.add(v);
        ((JsonObject) p[1]).add("enum", e);
        return p;
    }

    private static Object[] arr(String name, String desc) {
        Object[] p = prop(name, "ARRAY", desc);
        JsonObject items = new JsonObject();
        items.addProperty("type", "STRING");
        ((JsonObject) p[1]).add("items", items);
        return p;
    }

    private static Object[] prop(String name, String type, String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", desc);
        return new Object[]{name, p};
    }

    private static JsonObject props(Object[]... props) {
        JsonObject o = new JsonObject();
        for (Object[] p : props) o.add((String) p[0], (JsonObject) p[1]);
        return o;
    }

    private static JsonObject tool(String name, String desc, JsonArray required, Object[]... props) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "OBJECT");
        params.add("properties", props(props));
        if (required != null && required.size() > 0) params.add("required", required);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", desc);
        tool.add("parameters", params);
        return tool;
    }
}
