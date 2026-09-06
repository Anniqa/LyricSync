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
 * streams responses via AntigravityApiClient.
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
    private String modelId;
    private String title;
    private String artist;
    private String fullLyrics;
    private SongMeaning meaning;
    private final List<AiChatMessage> history = new ArrayList<>();

    public SongChatManager(AntigravityApiClient apiClient) {
        this(apiClient, DEFAULT_MODEL);
    }

    public SongChatManager(AntigravityApiClient apiClient, String modelId) {
        this.apiClient = apiClient;
        this.modelId = (modelId != null && !modelId.trim().isEmpty()) ? modelId.trim() : DEFAULT_MODEL;
    }

    /**
     * Initializes or resets the active song context and clears prior conversation history.
     */
    public synchronized void setCurrentSong(String title, String artist, String fullLyrics, SongMeaning meaning) {
        this.title = title;
        this.artist = artist;
        this.fullLyrics = fullLyrics;
        this.meaning = meaning;
        clearHistory();
    }

    /**
     * Returns a copy of the conversation history.
     */
    public synchronized List<AiChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Clears conversational history while keeping the active song context intact.
     */
    public synchronized void clearHistory() {
        history.clear();
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
        }
        if (fullLyrics != null && !fullLyrics.trim().isEmpty()) {
            sb.append("Lyrics:\n").append(fullLyrics.trim()).append("\n");
        }
        sb.append("\nAnswer user questions about the song's meaning, backstory, emotional themes, and lyrics warmly and clearly in the language used by the user.");
        return sb.toString();
    }

    /**
     * Prepares the full sequence of messages (context + multi-turn history) to send to the API.
     */
    public List<AiChatMessage> buildMessagesToSend() {
        List<AiChatMessage> messages = new ArrayList<>();
        String context = buildSongContext();
        if (!context.trim().isEmpty()) {
            messages.add(AiChatMessage.user("[System Song Context & Instructions]\n" + context));
            messages.add(AiChatMessage.model("Understood! I have full context on \"" + (title != null ? title : "the song") + "\" and am ready to discuss it with you."));
        }
        synchronized (this) {
            messages.addAll(history);
        }
        return messages;
    }

    /**
     * Appends user message, initiates streaming response from the AI model, and appends model
     * response upon completion.
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

        final String trimmedUserMessage = userMessage.trim();
        synchronized (this) {
            history.add(AiChatMessage.user(trimmedUserMessage));
        }

        List<AiChatMessage> messagesToSend = buildMessagesToSend();

        return apiClient.generateContentStream(modelId, messagesToSend, enableWebSearch, null, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                if (listener != null) {
                    try {
                        listener.onDelta(textDelta);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                synchronized (SongChatManager.this) {
                    history.add(AiChatMessage.model(fullText != null ? fullText : ""));
                }
                if (listener != null) {
                    try {
                        listener.onComplete(fullText, webSources, promptTokens, candidateTokens);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onError(Exception error) {
                if (listener != null) {
                    try {
                        listener.onError(error);
                    } catch (Throwable ignored) {}
                }
            }
        });
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
