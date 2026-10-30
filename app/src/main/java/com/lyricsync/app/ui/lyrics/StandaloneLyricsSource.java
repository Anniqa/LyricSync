package com.lyricsync.app.ui.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AiClients;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.client.LyricActionDelegate;
import com.lyricsync.app.ai.tools.LyricsEditor;
import com.lyricsync.app.ai.transliteration.AiSyllableTransliterationService;
import com.lyricsync.app.ai.transliteration.ScriptDetector;
import com.lyricsync.app.ai.transliteration.TransliterationRequest;
import com.lyricsync.app.detection.MediaSessionTracker;
import com.lyricsync.app.lyrics.LyricsProviderManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;
import com.lyricsync.app.lyrics.translation.AiTranslationService;
import com.lyricsync.app.lyrics.translation.GoogleTranslateParser;
import com.lyricsync.app.lyrics.translation.GoogleTranslateService;
import com.lyricsync.app.overlay.FloatingOverlayService;
import com.lyricsync.app.renderer.AnimConfig;
import com.lyricsync.app.renderer.AnimStyleDef;
import com.lyricsync.app.ui.AndroidPrefs;
import com.lyricsync.app.ui.AnimSelection;
import com.lyricsync.app.ui.main.MainPrefs;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Permissions;

import java.util.Locale;

/**
 * Lyrics for the full-screen view when the overlay is not running: its own media
 * session tracker (player, clock, seek, transport), lyrics lookup (same providers and
 * cache as the overlay) and the same Latin/translation layers, driven by the same prefs.
 * It is also Lyra's hands in that mode ({@link LyricActionDelegate}). Lives only while
 * the activity is visible; every callback runs on the main thread.
 */
final class StandaloneLyricsSource implements FloatingOverlayService.LyricsSource, LyricActionDelegate {
    private static final String TAG = "StandaloneLyrics";
    static final String PREF_ROMAJI = "pref_romaji_enabled";
    static final String PREF_ORIGINAL = "pref_original_script_enabled";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LyricsEditor editor = new LyricsEditor();
    private MediaSessionTracker tracker;
    private LyricsProviderManager lyricsManager;
    private AntigravityApiClient ai;
    private AiSyllableTransliterationService transliterator;
    private AiTranslationService translator;
    private AntigravityApiClient.Cancellable translitCall;
    private TrackInfo track;
    private LyricsData lyrics;
    private String pendingKey;
    private Runnable listener;
    private boolean loading, translating;

    /** Display prefs written anywhere (here, the main screen, Lyra) re-apply at once. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (PREF_ROMAJI.equals(key) || PREF_ORIGINAL.equals(key)
                || GoogleTranslateService.PREF_TRANSLATION_ENABLED.equals(key)
                || GoogleTranslateService.PREF_TARGET_LANG.equals(key)
                || AnimSelection.PREF_MOTION.equals(key) || AnimSelection.PREF_EFFECT.equals(key)) {
            if (GoogleTranslateService.PREF_TARGET_LANG.equals(key) && lyrics != null) lyrics.translationLang = null;
            enrich();
            notifyChanged();
        }
    };

    StandaloneLyricsSource(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(MainPrefs.FILE, Context.MODE_PRIVATE);
    }

    /** Needs notification access to read media sessions; false when it is missing. */
    boolean start() {
        if (!Permissions.isNotificationListenerEnabled(context)) return false;
        if (tracker != null) return true;
        lyricsManager = new LyricsProviderManager(context);
        ai = AiClients.forApp(context);
        transliterator = new AiSyllableTransliterationService(context, ai);
        translator = new AiTranslationService(context, AntigravityAuthManager.getInstance(context), ai,
                new GoogleTranslateService());
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        tracker = new MediaSessionTracker(context);
        long offset = prefs.getLong(MainPrefs.SYNC_OFFSET_MS, 0);
        tracker.setSyncOffsetMs(Math.max(-MainPrefs.SYNC_LIMIT_MS, Math.min(MainPrefs.SYNC_LIMIT_MS, offset)));
        tracker.start(new MediaSessionTracker.TrackCallback() {
            @Override
            public void onTrackChanged(TrackInfo t) {
                main.post(() -> {
                    track = t;
                    setLyrics(null);
                    fetch(t);
                    notifyChanged();
                });
            }

            @Override
            public void onTrackUpdated(TrackInfo t) {
                main.post(() -> {
                    track = t;
                    notifyChanged();
                });
            }

            @Override
            public void onTrackCleared() {
                main.post(() -> {
                    track = null;
                    setLyrics(null);
                    pendingKey = null;
                    notifyChanged();
                });
            }
        }, (state, position) -> main.post(this::notifyChanged));
        return true;
    }

    void stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (search != null) search.cancel();
        search = null;
        if (tracker != null) tracker.stop();
        tracker = null;
        cancelJobs();
        if (lyricsManager != null) lyricsManager.shutdown();
        lyricsManager = null;
        pendingKey = null;
        listener = null;
    }

    /** True while lyrics for the current track are being looked up. */
    boolean isLoading() {
        return loading;
    }

    private void setLyrics(LyricsData l) {
        cancelJobs();
        lyrics = l;
    }

    private void cancelJobs() {
        if (translitCall != null) translitCall.cancel();
        translitCall = null;
        if (translator != null) translator.cancel();
        translating = false;
    }

    private void fetch(TrackInfo t) {
        if (t == null || lyricsManager == null) return;
        String key = t.title + "|||" + t.artist;
        pendingKey = key;
        loading = true;
        lyricsManager.fetchLyrics(t, new LyricsProviderManager.LyricsCallback() {
            @Override
            public void onLyricsLoaded(LyricsData l) {
                main.post(() -> {
                    if (!key.equals(pendingKey)) return;
                    loading = false;
                    setLyrics(l);
                    enrich();
                    notifyChanged();
                });
            }

            @Override
            public void onLyricsError(String error) {
                main.post(() -> {
                    if (!key.equals(pendingKey)) return;
                    AppLog.w(TAG, "No lyrics: " + error);
                    loading = false;
                    notifyChanged();
                });
            }
        });
    }

    /**
     * Fills in what the prefs ask for, like the overlay does: the Latin reading for
     * non-Latin lyrics (needs an AI provider) and the translation (falls back to Google).
     */
    private void enrich() {
        LyricsData l = lyrics;
        if (l == null || l.isEmpty() || track == null) return;
        if (romajiEnabled() && translitCall == null && !l.hasTransliteration && nonLatin(l) && ai.isAuthenticated()) {
            transliterate(null);
        }
        if (translationEnabled() && !translating && l.translationLang == null) translate(null);
    }

    private void transliterate(ActionCallback cb) {
        final LyricsData target = lyrics;
        final TrackInfo t = track;
        final String key = pendingKey;
        translitCall = transliterator.transliterateSyllablesAsync(target,
                t.title != null ? t.title : "", t.artist != null ? t.artist : "", null,
                new AiSyllableTransliterationService.Callback() {
                    @Override
                    public void onSuccess(LyricsData enriched) {
                        main.post(() -> {
                            translitCall = null;
                            if (lyrics != target || !java.util.Objects.equals(key, pendingKey)) return;
                            lyrics = enriched;
                            if (lyricsManager != null) lyricsManager.updateCachedLyrics(t, enriched);
                            notifyChanged();
                            if (cb != null) cb.onSuccess("Latin reading ready.");
                        });
                    }

                    @Override
                    public void onError(String message) {
                        main.post(() -> {
                            translitCall = null;
                            AppLog.w(TAG, "Transliteration failed: " + message);
                            notifyChanged();
                            if (cb != null) cb.onError(message != null ? message : "Transliteration failed");
                        });
                    }
                });
        notifyChanged();
    }

    private void translate(ActionCallback cb) {
        final LyricsData target = lyrics;
        final TrackInfo t = track;
        translating = true;
        String lang = prefs.getString(GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG);
        translator.translateLyrics(target.lines, lang, new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String resolvedLang) {
                main.post(() -> {
                    translating = false;
                    if (lyrics != target) return;
                    // Same language: remember it so we do not ask again for this song.
                    target.translationLang = resolvedLang;
                    if (!result.isSameLanguage && lyricsManager != null) lyricsManager.updateCachedLyrics(t, target);
                    notifyChanged();
                    if (cb != null) cb.onSuccess(result.isSameLanguage
                            ? "The lyrics are already in " + result.detectedSourceLang + "."
                            : "Translated to " + resolvedLang + ".");
                });
            }

            @Override
            public void onError(Exception error) {
                main.post(() -> {
                    translating = false;
                    notifyChanged();
                    if (cb != null) cb.onError("Translation failed: " + (error != null ? error.getMessage() : ""));
                });
            }
        });
    }

    private void notifyChanged() {
        Runnable r = listener;
        if (r != null) r.run();
    }

    /** Non-Latin script in the first lines (the overlay samples the same way). */
    static boolean nonLatin(LyricsData l) {
        if (l == null || l.lines == null) return false;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (LyricsData.LyricsLine line : l.lines) {
            if (line == null || line.isInterlude || line.text == null) continue;
            sb.append(line.text).append(' ');
            if (++n >= 12) break;
        }
        return ScriptDetector.isNonLatin(sb.toString());
    }

    // ── LyricsSource ────────────────────────────────────────────────────────────

    @Override public LyricsData lyrics() { return lyrics; }

    @Override public TrackInfo track() { return track; }

    @Override public long position() { return tracker != null ? tracker.getCurrentPosition() : 0; }

    @Override public long duration() {
        long d = tracker != null ? tracker.getDurationMs() : 0;
        return d > 0 ? d : track != null ? Math.max(0, track.duration) : 0;
    }

    @Override public boolean isPlaying() { return tracker != null && tracker.isPlaying(); }

    @Override public void togglePlayPause() { if (tracker != null) tracker.togglePlayPause(); }

    @Override public void skip(boolean next) {
        if (tracker == null) return;
        if (next) tracker.skipNext();
        else tracker.skipPrevious();
    }

    @Override public void seekTo(long positionMs) { if (tracker != null) tracker.seekTo(positionMs); }

    @Override public AnimConfig animConfig() { return AnimSelection.current(new AndroidPrefs(prefs)); }

    @Override public boolean translationEnabled() {
        return prefs.getBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, false);
    }

    @Override public boolean romajiEnabled() { return prefs.getBoolean(PREF_ROMAJI, true); }

    @Override public boolean originalScriptEnabled() { return prefs.getBoolean(PREF_ORIGINAL, true); }

    @Override public boolean transliterating() { return translitCall != null; }

    @Override public LyricActionDelegate actions() { return this; }

    @Override public LyricsProviderManager lyricsManager() { return lyricsManager; }

    /** The user's pick (search dialog, paste, Lyra) replaces the cached choice for this track. */
    @Override public void useLyrics(LyricsData chosen) {
        if (chosen == null || chosen.isEmpty() || track == null) return;
        LyricsData copy = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(chosen), LyricsData.class);
        loading = false;
        pendingKey = track.title + "|||" + track.artist;
        setLyrics(copy);
        if (lyricsManager != null) lyricsManager.useLyrics(track, copy);
        enrich();
        notifyChanged();
    }

    @Override public void setListener(Runnable onChanged) { listener = onChanged; }

    // ── LyricActionDelegate (Lyra, when the overlay is off) ─────────────────────

    @Override
    public void getState(ActionCallback cb) {
        JsonObject o = new JsonObject();
        JsonObject t = new JsonObject();
        t.addProperty("title", track != null && track.title != null ? track.title : "");
        t.addProperty("artist", track != null && track.artist != null ? track.artist : "");
        if (duration() > 0) t.addProperty("duration_ms", duration());
        o.add("track", t);
        JsonObject p = new JsonObject();
        boolean player = tracker != null && tracker.hasController();
        p.addProperty("player_connected", player);
        if (player) {
            p.addProperty("playing", tracker.isPlaying());
            p.addProperty("position_ms", tracker.getCurrentPosition());
        }
        o.add("playback", p);
        JsonObject l = new JsonObject();
        l.addProperty("loaded", lyrics != null && !lyrics.isEmpty());
        if (lyrics != null && lyrics.lines != null) {
            l.addProperty("type", lyrics.type != null ? lyrics.type.name() : "");
            l.addProperty("lines", lyrics.lines.size());
            l.addProperty("non_latin_script", nonLatin(lyrics));
            l.addProperty("has_romaji", lyrics.hasTransliteration);
            if (lyrics.translationLang != null) l.addProperty("translation_language", lyrics.translationLang);
        }
        o.add("lyrics", l);
        JsonObject d = new JsonObject();
        d.addProperty("screen", "full_screen_lyrics");
        d.addProperty("translation", translationEnabled());
        d.addProperty("romaji", romajiEnabled());
        d.addProperty("original_script", originalScriptEnabled());
        AndroidPrefs ap = new AndroidPrefs(prefs);
        d.addProperty("motion", AnimSelection.motionKey(ap));
        d.addProperty("effect", AnimSelection.effectKey(ap));
        o.add("display", d);
        cb.onSuccess(o.toString());
    }

    @Override
    public void readLyrics(int from, int to, boolean words, ActionCallback cb) {
        if (lyrics == null || lyrics.isEmpty()) {
            cb.onError("No lyrics are loaded.");
            return;
        }
        cb.onSuccess(LyricsEditor.read(lyrics, from, to, words).toString());
    }

    @Override
    public void editLyrics(JsonArray ops, ActionCallback cb) {
        try {
            cancelJobs();
            LyricsEditor.Result r = editor.apply(lyrics, ops);
            if (r.wroteRomaji) {
                lyrics.hasTransliteration = true;
                prefs.edit().putBoolean(PREF_ROMAJI, true).apply();
            }
            if (r.wroteTranslation) {
                lyrics.translationLang = GoogleTranslateService.resolveTargetLang(prefs.getString(
                        GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG),
                        Locale.getDefault());
                prefs.edit().putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, true).apply();
            }
            if (lyricsManager != null) lyricsManager.updateCachedLyrics(track, lyrics);
            notifyChanged();
            cb.onSuccess(r.summary);
        } catch (IllegalArgumentException e) {
            cb.onError(e.getMessage());
        }
    }

    @Override
    public void undoEdit(boolean all, ActionCallback cb) {
        try {
            String msg = editor.undo(lyrics, all);
            if (lyricsManager != null) lyricsManager.updateCachedLyrics(track, lyrics);
            notifyChanged();
            cb.onSuccess(msg);
        } catch (IllegalStateException e) {
            cb.onError(e.getMessage());
        }
    }

    @Override
    public void translateLyrics(String targetLang, ActionCallback cb) {
        if (lyrics == null || lyrics.isEmpty()) {
            cb.onError("No lyrics are loaded.");
            return;
        }
        String lang = targetLang != null && !targetLang.trim().isEmpty() ? targetLang.trim() : "id";
        lyrics.translationLang = null;
        prefs.edit().putString(GoogleTranslateService.PREF_TARGET_LANG, lang)
                .putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, true).apply();
        translator.cancel();
        translate(cb);
    }

    @Override
    public void transliterateLyrics(String mode, boolean showOriginalScript, ActionCallback cb) {
        if (lyrics == null || lyrics.isEmpty()) {
            cb.onError("No lyrics are loaded.");
            return;
        }
        TransliterationRequest req = TransliterationRequest.fromTool(mode, showOriginalScript);
        SharedPreferences.Editor e = prefs.edit().putBoolean(PREF_ROMAJI, true);
        if (req.showOriginal != null) e.putBoolean(PREF_ORIGINAL, req.showOriginal);
        if (req.showTranslation != null) e.putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, req.showTranslation);
        e.apply();
        if (lyrics.hasTransliteration) {
            cb.onSuccess("Display applied: " + req.describe() + ".");
            return;
        }
        if (translitCall != null) translitCall.cancel();
        transliterate(cb);
    }

    @Override
    public void setDisplay(Display d, ActionCallback cb) {
        if (d.motion != null && !hasStyle(AnimStyleDef.Registry.motionStyles(), d.motion)
                || d.effect != null && !hasStyle(AnimStyleDef.Registry.effectStyles(), d.effect)) {
            cb.onError("Unknown motion or effect; use one of the values listed in the tool schema.");
            return;
        }
        SharedPreferences.Editor e = prefs.edit();
        if (d.translation != null) e.putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, d.translation);
        if (d.romaji != null) e.putBoolean(PREF_ROMAJI, d.romaji);
        if (d.originalScript != null) e.putBoolean(PREF_ORIGINAL, d.originalScript);
        e.apply();
        if (d.motion != null || d.effect != null) {
            AndroidPrefs ap = new AndroidPrefs(prefs);
            AnimSelection.save(ap, d.motion != null ? d.motion : AnimSelection.motionKey(ap),
                    d.effect != null ? d.effect : AnimSelection.effectKey(ap));
        }
        boolean overlayOnly = d.fontScale != null || d.widthPercent != null || d.heightPercent != null
                || d.syncOffsetMs != null;
        cb.onSuccess(overlayOnly ? "Applied. Size and sync settings belong to the overlay and were left unchanged."
                : "Applied.");
    }

    private static boolean hasStyle(java.util.List<AnimStyleDef> styles, String key) {
        for (AnimStyleDef s : styles) if (s.key.equals(key)) return true;
        return false;
    }

    @Override
    public void controlPlayback(String action, int lineIndex, long positionMs, ActionCallback cb) {
        if (tracker == null || !tracker.hasController()) {
            cb.onError("No music player is connected.");
            return;
        }
        String act = action != null ? action.trim().toLowerCase(Locale.ROOT) : "";
        switch (act) {
            case "seek_to_line":
                if (lyrics == null || lineIndex < 0 || lineIndex >= lyrics.lines.size()) {
                    cb.onError("line_index " + lineIndex + " is out of range.");
                    return;
                }
                tracker.seekTo(lyrics.lines.get(lineIndex).startTime);
                cb.onSuccess("Jumped to line " + lineIndex + ".");
                return;
            case "seek_to_position":
                tracker.seekTo(Math.max(0, positionMs));
                cb.onSuccess("Jumped to " + Math.max(0, positionMs) + " ms.");
                return;
            case "play": tracker.play(); break;
            case "pause": tracker.pause(); break;
            case "skip_next": tracker.skipNext(); break;
            case "skip_previous": tracker.skipPrevious(); break;
            default:
                cb.onError("Unknown action: " + action);
                return;
        }
        cb.onSuccess("Done: " + act + ".");
    }

    private final java.util.List<LyricsData> found = new java.util.ArrayList<>();
    private LyricsProviderManager.Search search;

    @Override
    public void findLyrics(String action, String title, String artist, int index, String text, ActionCallback cb) {
        if (track == null || lyricsManager == null) {
            cb.onError("Nothing is playing.");
            return;
        }
        switch (action == null ? "" : action.trim().toLowerCase(Locale.ROOT)) {
            case "search": {
                if (search != null) search.cancel();
                java.util.List<LyricsData> hits = new java.util.ArrayList<>();
                search = lyricsManager.search(track, title, artist, new LyricsProviderManager.SearchListener() {
                    @Override
                    public void onCandidate(LyricsProviderManager.Candidate c) {
                        hits.add(c.lyrics);
                    }

                    @Override
                    public void onFinished(int count) {
                        search = null;
                        hits.sort((a, b) -> LyricsProviderManager.betterThan(a, b) ? -1
                                : LyricsProviderManager.betterThan(b, a) ? 1 : 0);
                        found.clear();
                        found.addAll(hits);
                        cb.onSuccess(describe(hits));
                    }
                });
                return;
            }
            case "use":
                if (index < 0 || index >= found.size()) {
                    cb.onError(found.isEmpty() ? "Search first (action=search)." : "index must be 0.." + (found.size() - 1) + ".");
                    return;
                }
                LyricsData chosen = found.get(index);
                useLyrics(chosen);
                cb.onSuccess("Now showing " + chosen.provider + " (" + chosen.type + ", "
                        + com.lyricsync.app.overlay.LyricsSearchDialog.lines(chosen) + " lines).");
                return;
            case "paste": {
                LyricsData pasted = com.lyricsync.app.lyrics.model.LyricsFormat.parse(text);
                if (pasted.isEmpty()) {
                    cb.onError("No lyric lines found in the text. Formats: TTML, QRC, KRC, enhanced LRC, LRC, plain.");
                    return;
                }
                pasted.provider = context.getString(com.lyricsync.app.R.string.lyrics_source_pasted);
                useLyrics(pasted);
                cb.onSuccess("Loaded " + com.lyricsync.app.overlay.LyricsSearchDialog.lines(pasted) + " lines.");
                return;
            }
            default:
                cb.onError("action must be search, use or paste.");
        }
    }

    private static String describe(java.util.List<LyricsData> hits) {
        if (hits.isEmpty()) return "No source has lyrics for this query. Try another spelling or the original-script title.";
        StringBuilder sb = new StringBuilder("Results (best first); load one with action=use and its index:\n");
        for (int i = 0; i < hits.size(); i++) {
            LyricsData l = hits.get(i);
            sb.append('[').append(i).append("] ").append(l.provider).append(" · ").append(l.type).append(" · ")
                    .append(com.lyricsync.app.overlay.LyricsSearchDialog.lines(l)).append(" lines · ")
                    .append(com.lyricsync.app.overlay.LyricsSearchDialog.preview(l)).append('\n');
        }
        return sb.toString().trim();
    }
}
