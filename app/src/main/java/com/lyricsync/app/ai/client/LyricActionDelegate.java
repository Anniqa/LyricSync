package com.lyricsync.app.ai.client;

import com.google.gson.JsonArray;

/**
 * What the AI may do inside the app — the "harness" its tools drive. Implemented by the
 * overlay service; {@link MainThreadLyricActionRouter} hops every call onto the main thread.
 *
 * <p>Results are written for the model: plain sentences for actions, JSON for reads.
 */
public interface LyricActionDelegate {

    interface ActionCallback {
        void onSuccess(String result);

        void onError(String error);
    }

    /** Display settings to change; a null field is left as it is. */
    final class Display {
        public Boolean translation;
        public Boolean romaji;
        public Boolean originalScript;
        public Float fontScale;
        public Integer widthPercent;
        public Integer heightPercent;
        public Integer syncOffsetMs;
        public String motion;
        public String effect;

        public boolean isEmpty() {
            return translation == null && romaji == null && originalScript == null && fontScale == null
                    && widthPercent == null && heightPercent == null && syncOffsetMs == null
                    && motion == null && effect == null;
        }
    }

    /** JSON: track, playback and active line, lyrics summary, display settings, undo steps. */
    void getState(ActionCallback callback);

    /** JSON of lines {@code from..to} (inclusive, -1 = to the end), optionally with their words. */
    void readLyrics(int from, int to, boolean words, ActionCallback callback);

    /** Applies {@code ops} ({@link com.lyricsync.app.ai.tools.LyricsEditor}) atomically: all, or none when one is invalid. */
    void editLyrics(JsonArray ops, ActionCallback callback);

    /** Reverts the last change, or every change since the lyrics loaded when {@code all}. */
    void undoEdit(boolean all, ActionCallback callback);

    void translateLyrics(String targetLang, ActionCallback callback);

    void transliterateLyrics(String mode, boolean showOriginalScript, ActionCallback callback);

    void setDisplay(Display display, ActionCallback callback);

    void controlPlayback(String action, int lineIndex, long positionMs, ActionCallback callback);
}
