package com.lyricsync.app.ai.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Follow-up suggestions the model appends to a reply, shown as tappable chips.
 *
 * <p>The model ends a reply with a {@link #MARKER} line followed by up to three "- "
 * lines. {@link #split} separates them from the answer; {@link #visible} hides the
 * block (even half-streamed) so it never flashes in the chat bubble.
 */
public final class FollowUps {

    public static final String MARKER = "[[followups]]";
    public static final int MAX = 3;
    private static final int MAX_LENGTH = 90;

    private FollowUps() {}

    /** An answer with its suggestions removed, plus the suggestions. */
    public static final class Reply {
        public final String text;
        public final List<String> suggestions;

        Reply(String text, List<String> suggestions) {
            this.text = text;
            this.suggestions = suggestions;
        }
    }

    public static Reply split(String raw) {
        if (raw == null) return new Reply("", Collections.<String>emptyList());
        int at = raw.indexOf(MARKER);
        if (at < 0) return new Reply(raw.trim(), Collections.<String>emptyList());
        List<String> out = new ArrayList<>();
        for (String line : raw.substring(at + MARKER.length()).split("\\r?\\n")) {
            String s = line.trim().replaceFirst("^([-*•]|\\d+[.)])\\s*", "").trim();
            if (s.length() >= 2 && s.length() <= MAX_LENGTH && !out.contains(s)) out.add(s);
            if (out.size() == MAX) break;
        }
        return new Reply(raw.substring(0, at).trim(), out);
    }

    /** What to show while streaming: the text before the block, without a half-typed marker. */
    public static String visible(String streaming) {
        if (streaming == null) return "";
        int at = streaming.indexOf(MARKER);
        if (at >= 0) return streaming.substring(0, at);
        int open = streaming.lastIndexOf("[[");
        if (open >= 0 && MARKER.startsWith(streaming.substring(open))) return streaming.substring(0, open);
        return streaming;
    }
}
