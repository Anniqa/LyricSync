package com.lyricsync.app.ai.service;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.service.SongMeaningService.SongMeaning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages interactive, multi-turn AI chat sessions discussing the active song.
 * Maintains conversation history, constructs contextual song prompts, and
 * streams responses via AntigravityApiClient with alternating role guarantees
 * and race condition protection across track changes.
 */
public class SongChatManager {

    public static final String DEFAULT_MODEL = "gemini-2.5-flash";

    public static final List<String> QUICK_PROMPTS = Collections.unmodifiableList(Arrays.asList(
            "Kisah di balik lagu ini",
            "Makna reff / chorus",
            "Simbolisme tersembunyi",
            "Pesan moral bagi pendengar"
    ));

    private final AntigravityApiClient apiClient;
    private volatile String modelId;
    private volatile String title;
    private volatile String artist;
    private volatile String fullLyrics;
    private volatile SongMeaning meaning;
    private volatile long currentSessionId = 0;
    private volatile AntigravityApiClient.Cancellable activeCall;

    private final List<AiChatMessage> history = new ArrayList<>();
    private final List<String> lastWebSources = new ArrayList<>();

    public SongChatManager(AntigravityApiClient apiClient) {
        this(apiClient, DEFAULT_MODEL);
    }

    public SongChatManager(AntigravityApiClient apiClient, String modelId) {
        this.apiClient = apiClient;
        this.modelId = (modelId != null && !modelId.trim().isEmpty()) ? modelId.trim() : DEFAULT_MODEL;
    }

    /**
     * Initializes or resets the active song context, cancels any in-flight stream,
     * increments session ID to prevent cross-song response contamination, and clears history.
     */
    public synchronized void setCurrentSong(String title, String artist, String fullLyrics, SongMeaning meaning) {
        if (activeCall != null) {
            try {
                activeCall.cancel();
            } catch (Exception ignored) {}
            activeCall = null;
        }
        this.title = title;
        this.artist = artist;
        this.fullLyrics = fullLyrics;
        this.meaning = meaning;
        this.currentSessionId++;
        clearHistory();
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

    public SongMeaning getMeaning() {
        return meaning;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        if (modelId != null && !modelId.trim().isEmpty()) {
            this.modelId = modelId.trim();
        }
    }

    /**
     * Constructs the contextual system information summarizing the active song.
     */
    public String buildSongContext() {
        return buildSongContext(false);
    }

    /**
     * Constructs the contextual system information summarizing the active song,
     * adding factual Google Search directives when enableWebSearch is true.
     */
    public String buildSongContext(boolean enableWebSearch) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an insightful and helpful music analyst assistant in the LyricSync app.\n");
        sb.append("You are in an interactive conversation with the user about the currently playing song:\n");
        if (title != null && !title.trim().isEmpty()) {
            sb.append("Title: ").append(title.trim()).append("\n");
        }
        if (artist != null && !artist.trim().isEmpty()) {
            sb.append("Artist: ").append(artist.trim()).append("\n");
        }
        if (meaning != null) {
            if (meaning.mood != null && !meaning.mood.trim().isEmpty()) {
                sb.append("Mood: ").append(meaning.mood.trim()).append("\n");
            }
            if (meaning.summary != null && !meaning.summary.trim().isEmpty()) {
                sb.append("Meaning Summary: ").append(meaning.summary.trim()).append("\n");
            }
            if (meaning.webSources != null && !meaning.webSources.isEmpty()) {
                sb.append("Grounding Sources: ");
                for (int i = 0; i < meaning.webSources.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(meaning.webSources.get(i));
                }
                sb.append("\n");
            }
        }
        if (fullLyrics != null && !fullLyrics.trim().isEmpty()) {
            sb.append("Lyrics:\n").append(fullLyrics.trim()).append("\n");
        }
        if (enableWebSearch) {
            sb.append("\n[PENTING] Lakukan penelusuran Google Search secara aktif untuk menemukan fakta riil, latar belakang resmi, wawancara pencipta lagu/musisi, dan konteks album. Berikan jawaban yang akurat dan berbasis fakta, bukan spekulasi atau asumsi.\n");
        }
        sb.append("\nAnswer user questions about the song's meaning, backstory, emotional themes, and lyrics warmly and clearly in the language used by the user.");
        return sb.toString();
    }

    /**
     * Prepares the full sequence of messages (context + multi-turn history) to send to the API,
     * guaranteeing strictly alternating roles (user, model, user, model...).
     */
    public List<AiChatMessage> buildMessagesToSend() {
        return buildMessagesToSend(false);
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
            messages.add(AiChatMessage.model("Understood! I have full context on \"" + (title != null ? title : "the song") + "\" and am ready to discuss it with you."));
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
            if (listener != null) {
                listener.onError(new IllegalArgumentException("User message cannot be empty"));
            }
            return new NoOpCancellable();
        }

        if (apiClient == null) {
            if (listener != null) {
                listener.onError(new IllegalStateException("AntigravityApiClient is null"));
            }
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
                if (sessionHandle.isCancelled() || sessionId != currentSessionId) {
                    return;
                }
                if (listener != null) {
                    try {
                        listener.onDelta(textDelta);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                if (sessionHandle.isCancelled() || sessionId != currentSessionId) {
                    rollbackUserTurnIfOrphaned(userTurn);
                    return;
                }

                synchronized (SongChatManager.this) {
                    if (sessionId == currentSessionId) {
                        history.add(AiChatMessage.model(fullText != null ? fullText : ""));
                        lastWebSources.clear();
                        if (webSources != null) {
                            lastWebSources.addAll(webSources);
                        }
                    } else {
                        rollbackUserTurnIfOrphaned(userTurn);
                        return;
                    }
                }

                if (listener != null) {
                    try {
                        listener.onComplete(fullText, webSources, promptTokens, candidateTokens);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onError(Exception error) {
                // Roll back orphaned user turn to preserve strict alternating role invariant
                rollbackUserTurnIfOrphaned(userTurn);

                if (listener != null) {
                    try {
                        listener.onError(error);
                    } catch (Throwable ignored) {}
                }
            }
        });

        sessionHandle.setInner(innerCall);
        this.activeCall = sessionHandle;
        return sessionHandle;
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
