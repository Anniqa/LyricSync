package com.lyricsync.app.ai.client;

import android.os.Handler;

import com.google.gson.JsonArray;

/**
 * Forwards {@link LyricActionDelegate} calls to a host on the main thread: tool calls
 * arrive on network threads, but the host touches its views and lyrics there.
 */
public final class MainThreadLyricActionRouter implements LyricActionDelegate {

    private final Handler mainHandler;
    private final LyricActionDelegate target;

    public MainThreadLyricActionRouter(Handler mainHandler, LyricActionDelegate target) {
        this.mainHandler = mainHandler;
        this.target = target;
    }

    @Override
    public void getState(ActionCallback cb) {
        mainHandler.post(() -> target.getState(cb));
    }

    @Override
    public void readLyrics(int from, int to, boolean words, ActionCallback cb) {
        mainHandler.post(() -> target.readLyrics(from, to, words, cb));
    }

    @Override
    public void editLyrics(JsonArray ops, ActionCallback cb) {
        mainHandler.post(() -> target.editLyrics(ops, cb));
    }

    @Override
    public void undoEdit(boolean all, ActionCallback cb) {
        mainHandler.post(() -> target.undoEdit(all, cb));
    }

    @Override
    public void translateLyrics(String targetLang, ActionCallback cb) {
        mainHandler.post(() -> target.translateLyrics(targetLang, cb));
    }

    @Override
    public void transliterateLyrics(String mode, boolean showOriginalScript, ActionCallback cb) {
        mainHandler.post(() -> target.transliterateLyrics(mode, showOriginalScript, cb));
    }

    @Override
    public void setDisplay(Display display, ActionCallback cb) {
        mainHandler.post(() -> target.setDisplay(display, cb));
    }

    @Override
    public void controlPlayback(String action, int lineIndex, long positionMs, ActionCallback cb) {
        mainHandler.post(() -> target.controlPlayback(action, lineIndex, positionMs, cb));
    }
}
