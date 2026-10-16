package com.lyricsync.app.ai.client;

import android.os.Handler;

import com.lyricsync.app.ai.client.AntigravityApiClient.LyricActionDelegate;
import com.lyricsync.app.ai.client.AntigravityApiClient.LyricActionDelegate.ActionCallback;
import com.lyricsync.app.ai.client.AntigravityApiClient.LyricActionDelegate.LineEdit;
import com.lyricsync.app.ai.client.AntigravityApiClient.LyricActionDelegate.TrackInfoCallback;

import java.util.List;

/**
 * Forwards {@link LyricActionDelegate} calls to a host on the main thread.
 *
 * <p>The overlay service and the settings screen serve the same action set, and both must
 * touch their views on the main thread, so each implements {@link LyricActionDelegate} and
 * wraps itself in this — rather than hand-rolling the hop again.
 */
public final class MainThreadLyricActionRouter implements LyricActionDelegate {

    private final Handler mainHandler;
    private final LyricActionDelegate target;

    public MainThreadLyricActionRouter(Handler mainHandler, LyricActionDelegate target) {
        this.mainHandler = mainHandler;
        this.target = target;
    }

    private void onMain(Runnable action) {
        mainHandler.post(action);
    }

    @Override
    public void translateLyrics(String targetLang, ActionCallback callback) {
        onMain(() -> target.translateLyrics(targetLang, callback));
    }

    @Override
    public void transliterateLyrics(String mode, ActionCallback callback) {
        onMain(() -> target.transliterateLyrics(mode, true, callback));
    }

    @Override
    public void transliterateLyrics(String mode, boolean showOriginalScript, ActionCallback callback) {
        onMain(() -> target.transliterateLyrics(mode, showOriginalScript, callback));
    }

    @Override
    public void setSyncOffset(int offsetMs, ActionCallback callback) {
        onMain(() -> target.setSyncOffset(offsetMs, callback));
    }

    @Override
    public void modifyLine(int lineIndex, String text, String translation, String romanji, ActionCallback callback) {
        onMain(() -> target.modifyLine(lineIndex, text, translation, romanji, callback));
    }

    @Override
    public void batchEditLyrics(List<LineEdit> edits, ActionCallback callback) {
        onMain(() -> target.batchEditLyrics(edits, callback));
    }

    @Override
    public void toggleDisplay(String feature, boolean enabled, ActionCallback callback) {
        onMain(() -> target.toggleDisplay(feature, enabled, callback));
    }

    @Override
    public void controlPlayback(String action, int lineIndex, long positionMs, ActionCallback callback) {
        onMain(() -> target.controlPlayback(action, lineIndex, positionMs, callback));
    }

    @Override
    public void adjustAppearance(Float fontScale, String animStyle, Integer widthPercent, Integer heightPercent,
                                 ActionCallback callback) {
        onMain(() -> target.adjustAppearance(fontScale, animStyle, widthPercent, heightPercent, callback));
    }

    @Override
    public void manageLines(String action, int lineIndex, long startMs, long endMs, String text, String translation,
                            String romanji, long shiftMs, ActionCallback callback) {
        onMain(() -> target.manageLines(action, lineIndex, startMs, endMs, text, translation, romanji, shiftMs, callback));
    }

    @Override
    public void getCurrentPlayingTrack(TrackInfoCallback callback) {
        onMain(() -> target.getCurrentPlayingTrack(callback));
    }
}
