package com.lyricsync.app.ai.service;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.util.FollowUps;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages interactive, multi-turn AI chat sessions discussing the active song.
 * Maintains conversation history, constructs contextual song prompts, and
 * streams responses via AntigravityApiClient with alternating role guarantees
 * and race condition protection across track changes.
 */
public class SongChatManager {

    public static final String DEFAULT_MODEL = "gemini-3.8-flash-high";

    private final AntigravityApiClient apiClient;
    private volatile String modelId;
    private volatile String appLanguage = "en";
    private volatile String title;
    private volatile String artist;
    private volatile String fullLyrics;
    private volatile String appState;
    private volatile long currentSessionId = 0;
    private volatile AntigravityApiClient.Cancellable activeCall;

    private volatile String activeSessionUuid = UUID.randomUUID().toString();
    private volatile AiChatHistoryManager historyManager;

    private final List<AiChatMessage> history = new ArrayList<>();
    private final List<String> lastWebSources = new ArrayList<>();

    public SongChatManager(AntigravityApiClient apiClient) {
        this(apiClient, DEFAULT_MODEL);
    }

    public SongChatManager(AntigravityApiClient apiClient, String modelId) {
        this.apiClient = apiClient;
        this.modelId = (modelId != null && !modelId.trim().isEmpty()) ? modelId.trim() : DEFAULT_MODEL;
    }

    public void setHistoryManager(AiChatHistoryManager historyManager) {
        this.historyManager = historyManager;
    }


    public synchronized String getActiveSessionId() {
        return activeSessionUuid;
    }

    public synchronized void setActiveSessionId(String id) {
        if (id != null && !id.trim().isEmpty()) {
            this.activeSessionUuid = id.trim();
        }
    }

    /**
     * Resets the conversation for a fresh multi-turn chat, aborting any active stream,
     * allocating a new session UUID, and incrementing session ID.
     */
    public synchronized void startNewSession() {
        if (activeCall != null) {
            try {
                activeCall.cancel();
            } catch (Exception ignored) {}
            activeCall = null;
        }
        this.activeSessionUuid = UUID.randomUUID().toString();
        this.currentSessionId++;
        clearHistory();
    }

    /**
     * Restores a previously saved conversation session into the active chat manager.
     */
    public synchronized void loadSession(AiChatHistoryManager.ChatSession session) {
        if (session == null) return;
        if (activeCall != null) {
            try {
                activeCall.cancel();
            } catch (Exception ignored) {}
            activeCall = null;
        }
        if (session.songTitle != null && !session.songTitle.trim().isEmpty()) {
            this.title = session.songTitle;
        }
        if (session.songArtist != null && !session.songArtist.trim().isEmpty()) {
            this.artist = session.songArtist;
        }
        this.activeSessionUuid = (session.id != null && !session.id.trim().isEmpty())
                ? session.id.trim() : UUID.randomUUID().toString();
        this.history.clear();
        if (session.messages != null) {
            this.history.addAll(session.messages);
        }
        this.lastWebSources.clear();
        if (session.webSources != null) {
            this.lastWebSources.addAll(session.webSources);
        }
        this.currentSessionId++;
    }

    /**
     * Creates a snapshot of the current conversational state for history persistence.
     */
    public synchronized AiChatHistoryManager.ChatSession createSessionSnapshot() {
        return new AiChatHistoryManager.ChatSession(
                this.activeSessionUuid,
                this.title,
                this.artist,
                System.currentTimeMillis(),
                new ArrayList<>(this.history),
                new ArrayList<>(this.lastWebSources)
        );
    }

    /**
     * Sets the active song context without destroying the current conversation session.
     * Song transitions update track metadata so AI prompts reflect the currently playing song,
     * while the conversation history and active session UUID remain intact.
     * To explicitly start a new session, call {@link #startNewSession()}.
     */
    public synchronized void setCurrentSong(String title, String artist, String fullLyrics) {
        boolean sameTrack = Objects.equals(this.title, title) && Objects.equals(this.artist, artist);
        this.title = title;
        this.artist = artist;
        if (fullLyrics != null && !fullLyrics.trim().isEmpty()) {
            this.fullLyrics = fullLyrics;
        } else if (title == null || !sameTrack) {
            // New track (or no track): drop the previous song's lyrics instead of
            // leaking them into this song's AI context.
            this.fullLyrics = null;
        }
    }

    /**
     * Returns a copy of the conversation history.
     */
    public synchronized List<AiChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Returns a copy of the grounding web sources from the latest completion.
     */
    public synchronized List<String> getLastWebSources() {
        return new ArrayList<>(lastWebSources);
    }

    /**
     * Clears conversational history while keeping the active song context intact.
     */
    public synchronized void clearHistory() {
        history.clear();
        lastWebSources.clear();
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getFullLyrics() {
        return fullLyrics;
    }

    public synchronized void updateFullLyrics(String fullLyrics) {
        this.fullLyrics = fullLyrics;
    }

    /** App state JSON (track, playback, display) for the next message's context. */
    public void setAppState(String appStateJson) {
        this.appState = appStateJson;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        if (modelId != null && !modelId.trim().isEmpty()) {
            this.modelId = modelId.trim();
        }
    }

    public void setAppLanguage(String lang) {
        this.appLanguage = (lang != null && (lang.equalsIgnoreCase("id") || lang.equalsIgnoreCase("in"))) ? "id" : "en";
    }

    /** System instructions plus the active song: one English prompt, answered in the app language. */
    public String buildSongContext(boolean enableWebSearch) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Lyra, the AI built into LyricSync, a floating synced-lyrics overlay for Android. "
                + "You can see and change everything the overlay shows: the lyric lines, their Latin reading "
                + "(romaji/pinyin/romaja), per-word labels, translations, timing and display settings, and you "
                + "can control the music player. Act like a capable agent: understand the intent, use the tools "
                + "to do it fully, check the result, then reply briefly.\n");
        sb.append("\n[Persona]\n");
        sb.append("Lyra is an anime-style tsundere: a proud, posh, sharp-tongued girl who acts above it all "
                + "(\"Hmph\", \"not that I care\", a raised eyebrow) yet secretly enjoys helping and always does "
                + "the job perfectly. Show it in at most one short aside per reply: a light tease, never rude, no "
                + "insults. The work itself stays serious and complete: answers, facts, translations, tool arguments "
                + "and lyric edits never carry the persona. Drop the act entirely if the user is upset or the topic "
                + "is serious.\n");
        sb.append("id".equals(appLanguage)
                ? "Reply in natural, fluent Indonesian unless the user writes in another language.\n"
                : "Reply in the user's language.\n");

        sb.append("\n[Now playing]\n");
        sb.append("Title: ").append(blankTo(title, "unknown")).append("\n");
        sb.append("Artist: ").append(blankTo(artist, "unknown")).append("\n");
        if (appState != null && !appState.trim().isEmpty()) {
            sb.append("App state (JSON, taken when the user sent the message): ").append(appState.trim()).append("\n");
        }
        if (fullLyrics != null && !fullLyrics.trim().isEmpty()) {
            sb.append("Lyrics (\"[n]\" = line index; \"r:\" romaji line, \"t:\" translation):\n")
                    .append(fullLyrics.trim()).append("\n");
        } else {
            sb.append("Lyrics: not loaded yet. The app finds lyrics by itself (SpicyLyrics, Kugou, Netease, LRCLIB); "
                    + "you cannot download them.\n");
        }

        sb.append("\n[How the overlay draws a line]\n");
        sb.append("- text: the lyric (original script).\n");
        sb.append("- romaji: a Latin line drawn with it. word_romaji: one Latin label per timed word, drawn above "
                + "that word and highlighted in sync. Both accept ANY Latin text, so they also carry word-by-word "
                + "glosses, phonetic spellings or notes.\n");
        sb.append("- translation: a line under it.\n");
        sb.append("- Display switches (set_display): translation, romaji (the Latin layer), original_script "
                + "(off = Latin layer only).\n");

        sb.append("\n[Working with tools]\n");
        sb.append("- Only act when the user asks for a change; questions get answers, not edits.\n");
        sb.append("- Read before you write: use read_lyrics (include_words for word_romaji) to get exact text, word "
                + "counts and timing. Never guess indexes or word counts.\n");
        sb.append("- Put every change in ONE edit_lyrics call with many ops. Long songs: work in chunks of about "
                + "20-30 lines per call.\n");
        sb.append("- If a tool returns an error, read it, fix the arguments and try again.\n");
        sb.append("- Prefer the app pipelines for whole-song jobs: translate_lyrics (full-context translation) and "
                + "transliterate_lyrics (accurate syllable-aligned romaji). Use edit_lyrics for custom, partial or "
                + "creative changes.\n");
        sb.append("- Examples: \"romaji → Indonesian word by word\": read_lyrics with include_words, then set ops with "
                + "word_romaji = the Indonesian meaning of each word (same count; '' for particles), and romaji = "
                + "the full literal gloss if useful. \"Latin only\": set_display original_script=false, romaji=true. "
                + "\"Fix line 5\": set op on line 5. \"Lyrics are late\": set_display sync_offset_ms. "
                + "\"Undo that\": undo_edit.\n");
        sb.append("- Wrong, missing or not word-by-word lyrics: find_lyrics search (try the original-script title "
                + "or another spelling if nothing fits), pick the best match (SYLLABLE first) and load it with "
                + "use. Lyrics the user pastes go to find_lyrics paste.\n");
        sb.append("- After acting, confirm in one or two short sentences what changed. Do not paste the edited lyrics "
                + "back unless asked.\n");

        sb.append("\n[Answers]\n");
        sb.append("- Be concise and specific; quote the exact lines you discuss.\n");
        sb.append("- Separate what the lyrics say from interpretation. Never invent facts about the song, artist or "
                + "release; if unsure, say so.\n");
        sb.append("- Translate meaning and tone naturally, like professional subtitles.\n");
        if (enableWebSearch) {
            sb.append("- For facts (release, credits, interviews, background) use web_search first, web_fetch to read "
                    + "a promising source, and base the answer on what you found.\n");
        }

        sb.append("\n[Follow-ups]\n");
        sb.append("End every reply with a line \"").append(FollowUps.MARKER).append("\" and then 2 or 3 lines "
                + "starting with \"- \": short next requests the user is likely to want, written as the user would "
                + "type them (under 60 characters, same language as your reply), specific to this song and to what "
                + "just happened, and doable with your tools or knowledge. Do not repeat what was just done.\n");
        return sb.toString();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /**
     * Prepares the full sequence of messages (context with search directives + multi-turn history) to send to the API,
     * guaranteeing strictly alternating roles (user, model, user, model...).
     */
    public List<AiChatMessage> buildMessagesToSend(boolean enableWebSearch) {
        List<AiChatMessage> messages = new ArrayList<>();
        String context = buildSongContext(enableWebSearch);
        if (!context.trim().isEmpty()) {
            messages.add(AiChatMessage.user("[System Song Context & Instructions]\n" + context));
            messages.add(AiChatMessage.model("OK."));
        }

        // Sanitize history to ensure strict alternating role structure
        List<AiChatMessage> sanitizedHistory = new ArrayList<>();
        synchronized (this) {
            for (AiChatMessage msg : history) {
                if (msg == null || msg.content == null || msg.content.trim().isEmpty()) continue;
                String normalizedRole = ("model".equalsIgnoreCase(msg.role) || "assistant".equalsIgnoreCase(msg.role)) ? "model" : "user";
                if (sanitizedHistory.isEmpty()) {
                    if ("user".equals(normalizedRole)) {
                        sanitizedHistory.add(new AiChatMessage("user", msg.content));
                    }
                } else {
                    AiChatMessage prev = sanitizedHistory.get(sanitizedHistory.size() - 1);
                    if (!normalizedRole.equals(prev.role)) {
                        sanitizedHistory.add(new AiChatMessage(normalizedRole, msg.content));
                    } else if ("user".equals(normalizedRole)) {
                        // Merge consecutive user turns if any occurred
                        prev.content = prev.content + "\n\n" + msg.content;
                    }
                }
            }
        }

        messages.addAll(sanitizedHistory);
        return messages;
    }

    /**
     * Appends user message, initiates streaming response from the AI model, and appends model
     * response upon completion. Rolls back the user turn if the stream fails or is cancelled
     * to preserve the alternating role invariant.
     */
    public AntigravityApiClient.Cancellable sendMessage(final String userMessage, final boolean enableWebSearch,
                                                        final AntigravityApiClient.StreamListener listener) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            failFast(listener, new IllegalArgumentException("User message cannot be empty"));
            return new NoOpCancellable();
        }

        if (apiClient == null) {
            failFast(listener, new IllegalStateException("AntigravityApiClient is null"));
            return new NoOpCancellable();
        }

        // Abort any prior in-flight generation
        if (activeCall != null && !activeCall.isCancelled()) {
            try {
                activeCall.cancel();
            } catch (Exception ignored) {}
        }

        final long sessionId = this.currentSessionId;
        final String trimmedUserMessage = userMessage.trim();
        final AiChatMessage userTurn = AiChatMessage.user(trimmedUserMessage);

        synchronized (this) {
            history.add(userTurn);
        }

        List<AiChatMessage> messagesToSend = buildMessagesToSend(enableWebSearch);
        final SessionCancellable sessionHandle = new SessionCancellable(new Runnable() {
            @Override
            public void run() {
                rollbackUserTurnIfOrphaned(userTurn);
            }
        });

        AntigravityApiClient.Cancellable innerCall = apiClient.generateContentStream(
                modelId, messagesToSend, enableWebSearch, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                if (stale(sessionHandle, sessionId)) return;
                safeForward(listener, l -> l.onDelta(textDelta));
            }

            @Override
            public void onToolExecuting(String toolName, String queryOrUrl) {
                if (stale(sessionHandle, sessionId)) return;
                safeForward(listener, l -> l.onToolExecuting(toolName, queryOrUrl));
            }

            @Override
            public void onToolCompleted(String toolName, int resultsCount, boolean isError) {
                if (stale(sessionHandle, sessionId)) return;
                safeForward(listener, l -> l.onToolCompleted(toolName, resultsCount, isError));
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                if (stale(sessionHandle, sessionId)) {
                    rollbackUserTurnIfOrphaned(userTurn);
                    return;
                }

                AiChatHistoryManager.ChatSession savedSnapshot = null;
                synchronized (SongChatManager.this) {
                    if (sessionId == currentSessionId) {
                        // Stored without the follow-up block: it is UI, not conversation.
                        history.add(AiChatMessage.model(FollowUps.split(fullText).text));
                        lastWebSources.clear();
                        if (webSources != null) {
                            lastWebSources.addAll(webSources);
                        }
                        if (historyManager != null) {
                            savedSnapshot = createSessionSnapshot();
                        }
                    } else {
                        rollbackUserTurnIfOrphaned(userTurn);
                        return;
                    }
                }

                if (historyManager != null && savedSnapshot != null) {
                    try {
                        historyManager.saveSession(savedSnapshot);
                    } catch (Exception ignored) {}
                }

                safeForward(listener, l -> l.onComplete(fullText, webSources, promptTokens, candidateTokens));
            }

            @Override
            public void onError(Exception error) {
                // Roll back orphaned user turn to preserve strict alternating role invariant
                rollbackUserTurnIfOrphaned(userTurn);
                safeForward(listener, l -> l.onError(error));
            }
        });

        sessionHandle.setInner(innerCall);
        this.activeCall = sessionHandle;
        return sessionHandle;
    }

    /**
     * A turn or callback belongs to this session only while the send is still live
     * and no newer session has started: cancelled handles and stale sessions must
     * neither reach the outer listener nor commit history.
     */
    private boolean stale(SessionCancellable handle, long sessionId) {
        return handle.isCancelled() || sessionId != currentSessionId;
    }

    private interface Forward {
        void send(AntigravityApiClient.StreamListener l) throws Throwable;
    }

    /** Single copy of the never-let-a-throw-escape forwarding the callbacks repeat five times. */
    private static void safeForward(AntigravityApiClient.StreamListener listener, Forward f) {
        if (listener == null) return;
        try {
            f.send(listener);
        } catch (Throwable ignored) {}
    }

    private void failFast(AntigravityApiClient.StreamListener listener, RuntimeException e) {
        if (listener != null) {
            listener.onError(e);
        }
    }

    private synchronized void rollbackUserTurnIfOrphaned(AiChatMessage userTurn) {
        if (!history.isEmpty()) {
            int lastIdx = history.size() - 1;
            if (history.get(lastIdx) == userTurn) {
                history.remove(lastIdx);
            }
        }
    }

    private static class SessionCancellable implements AntigravityApiClient.Cancellable {
        private volatile boolean cancelled = false;
        private volatile AntigravityApiClient.Cancellable inner;
        private final Runnable onCancel;

        SessionCancellable(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        void setInner(AntigravityApiClient.Cancellable inner) {
            this.inner = inner;
            if (cancelled && inner != null) {
                inner.cancel();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                if (inner != null) {
                    inner.cancel();
                }
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || (inner != null && inner.isCancelled());
        }
    }

    private static class NoOpCancellable implements AntigravityApiClient.Cancellable {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
