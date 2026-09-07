package com.lyricsync.app.ai.service;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.history.AiChatHistoryManager;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.ai.service.SongMeaningService.SongMeaning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Manages interactive, multi-turn AI chat sessions discussing the active song.
 * Maintains conversation history, constructs contextual song prompts, and
 * streams responses via AntigravityApiClient with alternating role guarantees
 * and race condition protection across track changes.
 */
public class SongChatManager {

    public static class PromptTemplate {
        public final String id;
        public final String title;
        public final String description;
        public final String promptText;
        public final String iconName;

        public PromptTemplate(String id, String title, String description, String promptText, String iconName) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.promptText = promptText;
            this.iconName = iconName;
        }
    }

    public static final String DEFAULT_MODEL = "gemini-3.8-flash-high";

    public static final List<String> QUICK_PROMPTS = Collections.unmodifiableList(Arrays.asList(
            "Song Meaning & Story",
            "Line-by-Line Breakdown",
            "Songwriter's Intent",
            "Emotional Tone & Mood",
            "Trivia & Context",
            "Musical Composition"
    ));

    public static List<PromptTemplate> getPromptTemplates(String langCode) {
        boolean isIndo = "id".equalsIgnoreCase(langCode) || "in".equalsIgnoreCase(langCode);
        if (isIndo) {
            return Collections.unmodifiableList(Arrays.asList(
                    new PromptTemplate("meaning", "Makna & Kisah Lagu", "Eksplorasi cerita, tema utama, dan narasi lagu", "Jelaskan kisah, tema, dan makna mendalam di balik lagu ini.", "ic_sparkles"),
                    new PromptTemplate("breakdown", "Bedah Lirik & Metafora", "Analisis baris lirik penting dan bahasa kiasan", "Bedah baris-baris lirik penting dan metafora yang digunakan dalam lagu ini.", "ic_lyrics_lines"),
                    new PromptTemplate("intent", "Tujuan & Inspirasi Penulis", "Inspirasi artis dan pesan yang ingin disampaikan", "Apa yang menginspirasi pencipta lagu ini dan apa pesan yang ingin disampaikan?", "ic_person_artist"),
                    new PromptTemplate("mood", "Nuansa Emosi & Mood", "Suasana emosional, vibe, dan resonansi perasaan", "Analisis nuansa emosional, mood, dan suasana dalam lagu ini.", "ic_heart_mood"),
                    new PromptTemplate("trivia", "Trivia & Konteks Budaya", "Fakta menarik, proses rekaman, dan pengaruh budaya", "Ceritakan fakta menarik, latar belakang pembuatan, dan dampak budaya lagu ini.", "ic_bulb_info"),
                    new PromptTemplate("composition", "Komposisi Musik", "Akord, melodi, aransemen, dan gaya produksi", "Analisis struktur melodi, tempo, instrumen, dan aransemen musiknya.", "ic_music_note")
            ));
        } else {
            return Collections.unmodifiableList(Arrays.asList(
                    new PromptTemplate("meaning", "Song Meaning & Story", "Deep dive into themes and narrative", "Explain the story, themes, and deeper meaning behind this song.", "ic_sparkles"),
                    new PromptTemplate("breakdown", "Line-by-Line Breakdown", "Analyze metaphors and poetic lyrics", "Break down the key lines and metaphors in these lyrics.", "ic_lyrics_lines"),
                    new PromptTemplate("intent", "Songwriter's Intent", "Artist inspiration and backstory", "What inspired the artist to write this song and what was their intent?", "ic_person_artist"),
                    new PromptTemplate("mood", "Emotional Tone & Mood", "Mood, vibe, and emotional resonance", "Analyze the emotional mood, tone, and feel of this song.", "ic_heart_mood"),
                    new PromptTemplate("trivia", "Trivia & Context", "Behind-the-scenes facts and impact", "Tell me interesting trivia, background facts, and cultural context about this song.", "ic_bulb_info"),
                    new PromptTemplate("composition", "Musical Composition", "Chords, melody, and arrangement", "Analyze the musical structure, tempo, instrumentation, and composition of this song.", "ic_music_note")
            ));
        }
    }

    private final AntigravityApiClient apiClient;
    private volatile String modelId;
    private volatile String appLanguage = "en";
    private volatile String title;
    private volatile String artist;
    private volatile String fullLyrics;
    private volatile SongMeaning meaning;
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

    public AiChatHistoryManager getHistoryManager() {
        return this.historyManager;
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
        this.activeSessionUuid = UUID.randomUUID().toString();
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

    public String getAppLanguage() {
        return appLanguage;
    }

    public void setAppLanguage(String lang) {
        this.appLanguage = (lang != null && (lang.equalsIgnoreCase("id") || lang.equalsIgnoreCase("in"))) ? "id" : "en";
    }

    /**
     * Constructs the contextual system information summarizing the active song,
     * adding factual Google Search directives when enableWebSearch is true.
     */
    public String buildSongContext(boolean enableWebSearch) {
        StringBuilder sb = new StringBuilder();
        boolean isIndo = "id".equalsIgnoreCase(appLanguage);

        if (isIndo) {
            sb.append("Anda adalah asisten analis musik yang berwawasan luas dan membantu di aplikasi LyricSync.\n");
            sb.append("Anda sedang berdiskusi interaktif dengan pengguna mengenai lagu yang sedang diputar:\n");
        } else {
            sb.append("You are an insightful and helpful music analyst assistant in the LyricSync app.\n");
            sb.append("You are in an interactive conversation with the user about the currently playing song:\n");
        }

        if (title != null && !title.trim().isEmpty()) {
            sb.append(isIndo ? "Judul: " : "Title: ").append(title.trim()).append("\n");
        }
        if (artist != null && !artist.trim().isEmpty()) {
            sb.append(isIndo ? "Artis: " : "Artist: ").append(artist.trim()).append("\n");
        }
        if (meaning != null) {
            if (meaning.mood != null && !meaning.mood.trim().isEmpty()) {
                sb.append(isIndo ? "Nuansa / Mood: " : "Mood: ").append(meaning.mood.trim()).append("\n");
            }
            if (meaning.summary != null && !meaning.summary.trim().isEmpty()) {
                sb.append(isIndo ? "Ringkasan Makna: " : "Meaning Summary: ").append(meaning.summary.trim()).append("\n");
            }
            if (meaning.webSources != null && !meaning.webSources.isEmpty()) {
                sb.append(isIndo ? "Sumber Rujukan: " : "Grounding Sources: ");
                for (int i = 0; i < meaning.webSources.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(meaning.webSources.get(i));
                }
                sb.append("\n");
            }
        }
        if (fullLyrics != null && !fullLyrics.trim().isEmpty()) {
            sb.append(isIndo ? "Lirik:\n" : "Lyrics:\n").append(fullLyrics.trim()).append("\n");
        }
        if (enableWebSearch) {
            if (isIndo) {
                sb.append("\n[PENTING] Lakukan penelusuran Google Search secara aktif untuk menemukan fakta riil, latar belakang resmi, wawancara pencipta lagu/musisi, dan konteks album. Berikan jawaban yang akurat dan berbasis fakta, bukan spekulasi atau asumsi.\n");
            } else {
                sb.append("\n[IMPORTANT] Actively perform Google Search queries to discover verified facts, official background stories, artist interviews, and album context. Ground your answers in real facts rather than speculation.\n");
            }
        }
        if (isIndo) {
            sb.append("\n[PANDUAN ALAT LIRIK]\n");
            sb.append("Anda memiliki alat fungsi (tools) untuk mengontrol dan memodifikasi tampilan lirik secara langsung di layar overlay pengguna:\n");
            sb.append("- 'translate_lyrics': Gunakan jika pengguna meminta menerjemahkan lirik lagu ke bahasa lain (misal: Indonesia, Inggris, Jepang, Spanyol).\n");
            sb.append("- 'transliterate_lyrics': Gunakan jika pengguna meminta transliterasi (Romaji/Pinyin/Romaja). Parameter 'mode': 'romaji_with_translation' (dengan arti), 'romaji_only' (hanya romaji), atau 'pure_romaji' (hanya romaji). Parameter 'show_original_script': false jika pengguna meminta membuang/menyembunyikan huruf asli (misal: 'Jepang buang').\n");
            sb.append("- 'set_sync_offset': Gunakan jika pengguna mengeluh lirik terlalu cepat/lambat atau meminta memajukan/menunda lirik.\n");
            sb.append("- 'modify_lyric_line': Gunakan jika pengguna meminta mengoreksi teks, arti, atau bacaan baris lirik tertentu.\n");
            sb.append("- 'toggle_display': Gunakan jika pengguna meminta mematikan/menyalakan tampilan terjemahan, romaji, atau aksara asli.\n");
            sb.append("Jika permintaan pengguna berkaitan dengan tindakan di atas, SEGERA panggil alat tersebut!\n");
            sb.append("Setelah memanggil alat, selalu berikan konfirmasi ringkas dan ramah mengenai perubahan yang telah berhasil Anda terapkan di layar overlay.\n");
            sb.append("Jawab pertanyaan pengguna mengenai makna lagu, kisah di balik lagu, tema emosi, dan lirik secara ramah, mendalam, dan terstruktur dalam Bahasa Indonesia.");
            sb.append("\n[LYRIC TOOLS DIRECTIVE]\n");
            sb.append("You have native tools to control and modify the overlay lyrics in real-time:\n");
            sb.append("- 'translate_lyrics': Call when user asks to translate lyrics.\n");
            sb.append("- 'transliterate_lyrics': Call when user asks for romaji, pinyin, or latin transliteration.\n");
            sb.append("- 'set_sync_offset': Call when user asks to adjust sync timing/offset.\n");
            sb.append("- 'modify_lyric_line': Call when user asks to fix or edit a specific lyric line.\n");
            sb.append("- 'toggle_display': Call when user asks to toggle translation, romaji, or script display.\n");
            sb.append("If user requests any of these actions, execute the respective tool immediately!\n");
            sb.append("Answer user questions about the song's meaning, backstory, emotional themes, and lyrics warmly, insightfully, and clearly in English.");
        }
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
            public void onToolExecuting(String toolName, String queryOrUrl) {
                if (sessionHandle.isCancelled() || sessionId != currentSessionId) {
                    return;
                }
                if (listener != null) {
                    try {
                        listener.onToolExecuting(toolName, queryOrUrl);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onToolCompleted(String toolName, int resultsCount) {
                if (sessionHandle.isCancelled() || sessionId != currentSessionId) {
                    return;
                }
                if (listener != null) {
                    try {
                        listener.onToolCompleted(toolName, resultsCount);
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                if (sessionHandle.isCancelled() || sessionId != currentSessionId) {
                    rollbackUserTurnIfOrphaned(userTurn);
                    return;
                }

                AiChatHistoryManager.ChatSession savedSnapshot = null;
                synchronized (SongChatManager.this) {
                    if (sessionId == currentSessionId) {
                        history.add(AiChatMessage.model(fullText != null ? fullText : ""));
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
