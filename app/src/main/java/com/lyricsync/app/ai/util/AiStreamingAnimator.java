package com.lyricsync.app.ai.util;

import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

/**
 * Manages Claude/Gemini-style generation animations:
 * - Animated wave-style thinking dots ("Thinking ·", "Thinking · ·", "Thinking · · ·")
 * - Live streaming cursor with glowing accent tint (" ▎")
 * - Smooth markdown updates with streaming tail guard
 */
public class AiStreamingAnimator {

    private static final String[] THINKING_FRAMES = {
            " ·",
            " · ·",
            " · · ·",
            " · ·"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView targetView;
    private final int accentColor;

    private String thinkingBaseText = "Thinking";
    private int thinkingFrameIndex = 0;
    private boolean isThinking = false;
    private boolean isStreaming = false;
    private boolean cursorVisible = true;
    private CharSequence lastFormattedContent = "";

    private final Runnable thinkingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isThinking || targetView == null) return;
            thinkingFrameIndex = (thinkingFrameIndex + 1) % THINKING_FRAMES.length;
            targetView.setText(thinkingBaseText + THINKING_FRAMES[thinkingFrameIndex]);
            handler.postDelayed(this, 320);
        }
    };

    private final Runnable cursorBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isStreaming || targetView == null) return;
            cursorVisible = !cursorVisible;
            renderStreamingText();
            handler.postDelayed(this, 480);
        }
    };

    public AiStreamingAnimator(TextView targetView, int accentColor) {
        this.targetView = targetView;
        this.accentColor = accentColor;
    }

    /**
     * Starts thinking dots wave animation before response tokens arrive.
     */
    public void startThinking(String label) {
        stopAll();
        isThinking = true;
        thinkingFrameIndex = 0;
        thinkingBaseText = (label != null && !label.trim().isEmpty()) ? label : "Thinking";
        if (targetView != null) {
            targetView.setText(thinkingBaseText + " ·");
        }
        handler.postDelayed(thinkingRunnable, 320);
    }

    /**
     * Updates the bubble with incoming streaming text, guarding unfinished syntax
     * and displaying the blinking accent cursor.
     */
    public void updateStream(String rawMarkdown) {
        if (isThinking) {
            isThinking = false;
            handler.removeCallbacks(thinkingRunnable);
        }
        if (!isStreaming) {
            isStreaming = true;
            cursorVisible = true;
            handler.postDelayed(cursorBlinkRunnable, 480);
        }

        lastFormattedContent = CleanMarkdownFormatter.formatClean(rawMarkdown, true);
        renderStreamingText();
    }

    private void renderStreamingText() {
        if (targetView == null) return;
        if (!isStreaming) {
            targetView.setText(lastFormattedContent);
            return;
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(lastFormattedContent);
        if (cursorVisible) {
            int start = ssb.length();
            ssb.append(" ▎");
            ssb.setSpan(new ForegroundColorSpan(accentColor), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        targetView.setText(ssb);
    }

    /**
     * Completes generation: stops cursor animation and renders final clean rich markdown.
     */
    public void complete(String finalMarkdown) {
        stopAll();
        if (targetView != null) {
            targetView.setText(CleanMarkdownFormatter.formatClean(finalMarkdown, false));
        }
    }

    /**
     * Displays error and cleanly stops all running animations.
     */
    public void error(String errorMessage) {
        stopAll();
        if (targetView != null) {
            targetView.setText(CleanMarkdownFormatter.formatClean("Error: " + errorMessage, false));
        }
    }

    /**
     * Stops all pending animation handlers.
     */
    public void stopAll() {
        isThinking = false;
        isStreaming = false;
        handler.removeCallbacks(thinkingRunnable);
        handler.removeCallbacks(cursorBlinkRunnable);
    }
}
