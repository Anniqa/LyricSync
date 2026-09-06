package com.lyricsync.app.ai.service;

import android.content.SharedPreferences;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SongMeaningServiceTest {

    private FakeSharedPreferences fakePrefs;
    private MockApiClient mockApiClient;
    private SongMeaningService meaningService;
    private SongChatManager chatManager;

    @Before
    public void setUp() {
        fakePrefs = new FakeSharedPreferences();
        mockApiClient = new MockApiClient();
        meaningService = new SongMeaningService(fakePrefs, mockApiClient);
        chatManager = new SongChatManager(mockApiClient);
    }

    // ==========================================
    // SongMeaningService: Cache Key Tests
    // ==========================================

    @Test
    public void testGetCacheKeyStandardAndFormatting() {
        // MD5 of "coldplay_fix you" is 88047cd6d2453b4aaa6fee3d140331e1
        String key1 = SongMeaningService.getCacheKey("Coldplay", "Fix You");
        assertEquals("88047cd6d2453b4aaa6fee3d140331e1", key1);

        // Case insensitivity and whitespace trimming
        String key2 = SongMeaningService.getCacheKey("   COLDPLAY   ", "  fix you  ");
        assertEquals("88047cd6d2453b4aaa6fee3d140331e1", key2);
        assertEquals(key1, key2);

        // Different song produces different hash
        String keyYellow = SongMeaningService.getCacheKey("Coldplay", "Yellow");
        assertNotEquals(key1, keyYellow);
    }

    @Test
    public void testGetCacheKeyNullHandling() {
        String keyNull = SongMeaningService.getCacheKey(null, null);
        assertNotNull(keyNull);
        assertEquals(32, keyNull.length());

        String keyNullArtist = SongMeaningService.getCacheKey(null, "Fix You");
        assertEquals(SongMeaningService.getCacheKey("", "Fix You"), keyNullArtist);
    }

    // ==========================================
    // SongMeaningService: JSON Cleaner Tests
    // ==========================================

    @Test
    public void testCleanJsonFenceStandard() {
        String raw = "```json\n{\n  \"mood\": \"Melancholic\",\n  \"summary\": \"A song about grief and healing.\"\n}\n```";
        String cleaned = SongMeaningService.cleanJsonFence(raw);
        assertEquals("{\n  \"mood\": \"Melancholic\",\n  \"summary\": \"A song about grief and healing.\"\n}", cleaned);
    }

    @Test
    public void testCleanJsonFenceGenericCodeBlock() {
        String raw = "```\n{\"mood\": \"Uplifting\", \"summary\": \"Hope and renewal.\"}\n```";
        String cleaned = SongMeaningService.cleanJsonFence(raw);
        assertEquals("{\"mood\": \"Uplifting\", \"summary\": \"Hope and renewal.\"}", cleaned);
    }

    @Test
    public void testCleanJsonFenceEmbeddedInText() {
        String raw = "Here is the analysis:\n```json\n{\"mood\": \"Energetic\", \"summary\": \"High energy dance rock.\"}\n```\nHope this helps!";
        String cleaned = SongMeaningService.cleanJsonFence(raw);
        assertEquals("{\"mood\": \"Energetic\", \"summary\": \"High energy dance rock.\"}", cleaned);
    }

    @Test
    public void testCleanJsonFenceCaseInsensitiveAndPreamblePostamble() {
        String raw = "Berikut analisis lagu:\n```JSON\n{\n  \"mood\": \"Melankolis\",\n  \"summary\": \"Lagu tentang perpisahan.\"\n}\n```\nSemoga bermanfaat!";
        String cleaned = SongMeaningService.cleanJsonFence(raw);
        assertEquals("{\n  \"mood\": \"Melankolis\",\n  \"summary\": \"Lagu tentang perpisahan.\"\n}", cleaned);
    }

    @Test
    public void testCleanJsonFenceNoFencesJustObjectWithText() {
        String raw = "Analisis: {\"mood\": \"Ceria\", \"summary\": \"Lagu ceria.\"} Selesai.";
        String cleaned = SongMeaningService.cleanJsonFence(raw);
        assertEquals("{\"mood\": \"Ceria\", \"summary\": \"Lagu ceria.\"}", cleaned);
    }

    @Test
    public void testCleanJsonFenceRawJsonAndEmpty() {
        String raw = "{\"mood\": \"Calm\", \"summary\": \"Peaceful acoustic ballad.\"}";
        assertEquals(raw, SongMeaningService.cleanJsonFence(raw));

        assertEquals("", SongMeaningService.cleanJsonFence(null));
        assertEquals("", SongMeaningService.cleanJsonFence("   "));
    }

    @Test
    public void testBuildMeaningPromptContainsIndonesianInstruction() {
        String prompt = SongMeaningService.buildMeaningPrompt("Fix You", "Coldplay", "When you try your best...");
        assertTrue(prompt.contains("Tuliskan analisis dalam Bahasa Indonesia."));
        assertTrue(prompt.contains("Fix You"));
        assertTrue(prompt.contains("Coldplay"));
    }

    // ==========================================
    // SongMeaningService: getSongMeaning Tests
    // ==========================================

    @Test
    public void testGetSongMeaningCacheMissFetchesFromApiAndCaches() {
        mockApiClient.mockCompletionResponse = "```json\n" +
                "{\n" +
                "  \"mood\": \"Melancholic & Healing\",\n" +
                "  \"summary\": \"Written by Chris Martin for Gwyneth Paltrow after her father passed away.\"\n" +
                "}\n" +
                "```";
        mockApiClient.mockWebSources = Arrays.asList("Coldplay Fix You history", "Rolling Stone Fix You Review");

        final AtomicReference<SongMeaningService.SongMeaning> resultRef = new AtomicReference<>();
        final AtomicBoolean fromCacheRef = new AtomicBoolean(true);
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        meaningService.getSongMeaning("Fix You", "Coldplay", "When you try your best but you don't succeed...", true,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaningService.SongMeaning meaning, boolean fromCache) {
                        resultRef.set(meaning);
                        fromCacheRef.set(fromCache);
                    }

                    @Override
                    public void onError(Exception e) {
                        errorRef.set(e);
                    }
                });

        assertNull(errorRef.get());
        assertNotNull(resultRef.get());
        assertFalse("Initial fetch should not be from cache", fromCacheRef.get());
        assertEquals("Melancholic & Healing", resultRef.get().mood);
        assertTrue(resultRef.get().summary.contains("Chris Martin"));
        assertEquals(2, resultRef.get().webSources.size());
        assertEquals(1, mockApiClient.generateContentCallCount);

        // Verify it was stored in fake SharedPreferences
        String cacheKey = SongMeaningService.getCacheKey("Coldplay", "Fix You");
        assertTrue(fakePrefs.contains("pref_song_meaning_" + cacheKey));
    }

    @Test
    public void testGetSongMeaningMemoryCacheHitReturnsImmediately() {
        // Pre-populate memory cache
        SongMeaningService.SongMeaning preCached = new SongMeaningService.SongMeaning(
                "Nostalgic", "Song about memories", Collections.singletonList("Source 1")
        );
        meaningService.putMemoryCache("Coldplay", "Fix You", preCached);

        final AtomicReference<SongMeaningService.SongMeaning> resultRef = new AtomicReference<>();
        final AtomicBoolean fromCacheRef = new AtomicBoolean(false);

        meaningService.getSongMeaning("Fix You", "Coldplay", "Lyrics...", false,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaningService.SongMeaning meaning, boolean fromCache) {
                        resultRef.set(meaning);
                        fromCacheRef.set(fromCache);
                    }

                    @Override
                    public void onError(Exception e) {
                        fail("onError should not be called on cache hit");
                    }
                });

        assertNotNull(resultRef.get());
        assertTrue("Should return from cache", fromCacheRef.get());
        assertEquals("Nostalgic", resultRef.get().mood);
        assertEquals(0, mockApiClient.generateContentCallCount);
    }

    @Test
    public void testGetSongMeaningPrefsCacheHitPopulatesMemoryCache() {
        String cacheKey = SongMeaningService.getCacheKey("Coldplay", "Yellow");
        String cachedJson = "{\"mood\":\"Romantic & Radiant\",\"summary\":\"Written during a starry night in Wales.\",\"webSources\":[\"NME Yellow\"]}";
        fakePrefs.edit().putString("pref_song_meaning_" + cacheKey, cachedJson).apply();

        // New service instance with empty memory cache but populated SharedPreferences
        SongMeaningService serviceWithPrefs = new SongMeaningService(fakePrefs, mockApiClient);

        final AtomicReference<SongMeaningService.SongMeaning> resultRef = new AtomicReference<>();
        final AtomicBoolean fromCacheRef = new AtomicBoolean(false);

        serviceWithPrefs.getSongMeaning("Yellow", "Coldplay", "Look at the stars...", true,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaningService.SongMeaning meaning, boolean fromCache) {
                        resultRef.set(meaning);
                        fromCacheRef.set(fromCache);
                    }

                    @Override
                    public void onError(Exception e) {
                        fail("onError should not be called: " + e.getMessage());
                    }
                });

        assertNotNull(resultRef.get());
        assertTrue(fromCacheRef.get());
        assertEquals("Romantic & Radiant", resultRef.get().mood);
        assertEquals("Written during a starry night in Wales.", resultRef.get().summary);
        assertEquals(1, resultRef.get().webSources.size());
        assertEquals(0, mockApiClient.generateContentCallCount);

        // Now memory cache should be populated
        assertNotNull(serviceWithPrefs.getMemoryCache(cacheKey));
    }

    @Test
    public void testGetSongMeaningApiErrorDispatchesOnError() {
        mockApiClient.mockError = new IOException("Network connection dropped");

        final AtomicReference<Exception> errorRef = new AtomicReference<>();
        final AtomicBoolean onResultCalled = new AtomicBoolean(false);

        meaningService.getSongMeaning("Viva La Vida", "Coldplay", "I used to rule the world...", false,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaningService.SongMeaning meaning, boolean fromCache) {
                        onResultCalled.set(true);
                    }

                    @Override
                    public void onError(Exception e) {
                        errorRef.set(e);
                    }
                });

        assertFalse(onResultCalled.get());
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("Network connection dropped"));
    }

    @Test
    public void testGetSongMeaningMalformedJsonResponseDispatchesOnError() {
        mockApiClient.mockCompletionResponse = "This is plain text without any JSON structure.";

        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        meaningService.getSongMeaning("Clocks", "Coldplay", "Lights go out and I can't be saved...", false,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaningService.SongMeaning meaning, boolean fromCache) {
                        fail("onResult should not be called for malformed JSON");
                    }

                    @Override
                    public void onError(Exception e) {
                        errorRef.set(e);
                    }
                });

        assertNotNull(errorRef.get());
    }

    @Test
    public void testCallerExceptionInOnResultDoesNotTriggerOnError() {
        mockApiClient.mockCompletionResponse = "{\"mood\": \"Senang\", \"summary\": \"Lagu ceria\"}";

        final AtomicBoolean onErrorCalled = new AtomicBoolean(false);

        meaningService.getSongMeaning("Adventure of a Lifetime", "Coldplay", "Turn your magic on...", false,
                new SongMeaningService.MeaningCallback() {
                    @Override
                    public void onResult(SongMeaningService.SongMeaning meaning, boolean fromCache) {
                        throw new RuntimeException("Simulated exception in consumer onResult");
                    }

                    @Override
                    public void onError(Exception e) {
                        onErrorCalled.set(true);
                    }
                });

        assertFalse("Caller runtime exception in onResult must NOT cascade into onError", onErrorCalled.get());
    }

    @Test
    public void testClearCachePurgesBothMemoryAndPrefs() {
        SongMeaningService.SongMeaning preCached = new SongMeaningService.SongMeaning("Mood", "Summary", null);
        meaningService.putMemoryCache("Coldplay", "Fix You", preCached);

        String cacheKey = SongMeaningService.getCacheKey("Coldplay", "Fix You");
        fakePrefs.edit().putString("pref_song_meaning_" + cacheKey, "{\"mood\":\"Mood\"}").apply();

        meaningService.clearCache();

        assertNull(meaningService.getMemoryCache(cacheKey));
        assertFalse(fakePrefs.contains("pref_song_meaning_" + cacheKey));
    }

    // ==========================================
    // SongChatManager Tests
    // ==========================================

    @Test
    public void testSongChatManagerQuickPrompts() {
        assertNotNull(SongChatManager.QUICK_PROMPTS);
        assertEquals(4, SongChatManager.QUICK_PROMPTS.size());
        assertEquals("Kisah di balik lagu ini", SongChatManager.QUICK_PROMPTS.get(0));
        assertEquals("Makna reff / chorus", SongChatManager.QUICK_PROMPTS.get(1));
        assertEquals("Simbolisme tersembunyi", SongChatManager.QUICK_PROMPTS.get(2));
        assertEquals("Pesan moral bagi pendengar", SongChatManager.QUICK_PROMPTS.get(3));
    }

    @Test
    public void testSongChatManagerInitializationAndSetCurrentSong() {
        SongMeaningService.SongMeaning meaning = new SongMeaningService.SongMeaning(
                "Melancholic", "Fix You summary", Collections.singletonList("Source")
        );

        chatManager.setCurrentSong("Fix You", "Coldplay", "Lights will guide you home...", meaning);

        assertEquals("Fix You", chatManager.getTitle());
        assertEquals("Coldplay", chatManager.getArtist());
        assertEquals("Lights will guide you home...", chatManager.getFullLyrics());
        assertEquals(meaning, chatManager.getMeaning());
        assertTrue(chatManager.getHistory().isEmpty());
    }

    @Test
    public void testSongChatManagerSendMessageStreamSuccess() {
        SongMeaningService.SongMeaning meaning = new SongMeaningService.SongMeaning(
                "Melancholic & Healing", "Backstory of Fix You", null
        );
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lights will guide you home...", meaning);

        mockApiClient.mockStreamDeltas = Arrays.asList("Lagu ini ", "ditulis untuk ", "menghibur seseorang.");
        mockApiClient.mockStreamFullText = "Lagu ini ditulis untuk menghibur seseorang.";
        mockApiClient.mockWebSources = Collections.singletonList("Chris Martin interview");

        final List<String> receivedDeltas = new ArrayList<>();
        final AtomicReference<String> completedText = new AtomicReference<>();
        final AtomicReference<List<String>> completedSources = new AtomicReference<>();

        chatManager.sendMessage("Kisah di balik lagu ini", true, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {
                receivedDeltas.add(textDelta);
            }

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                completedText.set(fullText);
                completedSources.set(webSources);
            }

            @Override
            public void onError(Exception error) {
                fail("Stream onError should not be called: " + error.getMessage());
            }
        });

        // Verify stream deltas and completion
        assertEquals(3, receivedDeltas.size());
        assertEquals("Lagu ini ditulis untuk menghibur seseorang.", completedText.get());
        assertEquals(1, completedSources.get().size());

        // Verify history contains User turn and Model turn
        List<AiChatMessage> history = chatManager.getHistory();
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role);
        assertEquals("Kisah di balik lagu ini", history.get(0).content);
        assertEquals("model", history.get(1).role);
        assertEquals("Lagu ini ditulis untuk menghibur seseorang.", history.get(1).content);

        // Verify that the prompt sent to the API contained the song context
        assertNotNull(mockApiClient.lastStreamMessages);
        assertFalse(mockApiClient.lastStreamMessages.isEmpty());
        String fullCombinedPrompt = mockApiClient.lastStreamMessages.get(0).content;
        assertTrue(fullCombinedPrompt.contains("Fix You"));
        assertTrue(fullCombinedPrompt.contains("Coldplay"));
        assertTrue(fullCombinedPrompt.contains("Melancholic & Healing"));
    }

    @Test
    public void testSongChatManagerMultiTurnConversation() {
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lyrics...", null);

        // Turn 1
        mockApiClient.mockStreamFullText = "Jawaban pertama.";
        chatManager.sendMessage("Pertanyaan satu", false, null);

        // Turn 2
        mockApiClient.mockStreamFullText = "Jawaban kedua.";
        chatManager.sendMessage("Pertanyaan dua", false, null);

        List<AiChatMessage> history = chatManager.getHistory();
        assertEquals(4, history.size());
        assertEquals("user", history.get(0).role);
        assertEquals("Pertanyaan satu", history.get(0).content);
        assertEquals("model", history.get(1).role);
        assertEquals("Jawaban pertama.", history.get(1).content);
        assertEquals("user", history.get(2).role);
        assertEquals("Pertanyaan dua", history.get(2).content);
        assertEquals("model", history.get(3).role);
        assertEquals("Jawaban kedua.", history.get(3).content);
    }

    @Test
    public void testSongChatManagerRollbackUserTurnOnErrorPreservesAlternatingRoleInvariant() {
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lyrics...", null);
        mockApiClient.mockError = new IOException("Streaming interrupted");

        final AtomicReference<Exception> errorRef = new AtomicReference<>();
        chatManager.sendMessage("Pertanyaan gagal", false, new AntigravityApiClient.StreamListener() {
            @Override
            public void onDelta(String textDelta) {}

            @Override
            public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {}

            @Override
            public void onError(Exception error) {
                errorRef.set(error);
            }
        });

        assertNotNull(errorRef.get());
        // History must have rolled back so there is NO orphaned trailing user message
        assertTrue("History must be rolled back on error to prevent consecutive user turns", chatManager.getHistory().isEmpty());

        // Now subsequent message succeeds
        mockApiClient.mockError = null;
        mockApiClient.mockStreamFullText = "Jawaban sukses.";
        chatManager.sendMessage("Pertanyaan baru", false, null);

        List<AiChatMessage> history = chatManager.getHistory();
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role);
        assertEquals("Pertanyaan baru", history.get(0).content);
        assertEquals("model", history.get(1).role);
        assertEquals("Jawaban sukses.", history.get(1).content);
    }

    @Test
    public void testSongChatManagerRollbackUserTurnOnCancellation() {
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lyrics...", null);
        mockApiClient.autoCompleteStream = false; // keep stream open

        AntigravityApiClient.Cancellable handle = chatManager.sendMessage("Pertanyaan yang dicancel", false, null);
        assertNotNull(handle);
        assertFalse(handle.isCancelled());

        // Cancel stream in-flight
        handle.cancel();
        assertTrue(handle.isCancelled());

        // History must be empty due to rollback
        assertTrue("History must be empty after cancellation rollback", chatManager.getHistory().isEmpty());

        // Next message proceeds cleanly without consecutive user turns
        mockApiClient.autoCompleteStream = true;
        mockApiClient.mockStreamFullText = "Jawaban.";
        chatManager.sendMessage("Pertanyaan selanjutnya", false, null);

        assertEquals(2, chatManager.getHistory().size());
        assertEquals("user", chatManager.getHistory().get(0).role);
        assertEquals("model", chatManager.getHistory().get(1).role);
    }

    @Test
    public void testSongChatManagerTrackChangeCancelsStreamAndPreventsContamination() {
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lyrics 1...", null);
        mockApiClient.autoCompleteStream = false; // Hold stream open

        chatManager.sendMessage("Tentang Fix You", false, null);
        assertEquals(1, chatManager.getHistory().size());

        // Track changes to "Yellow" before Fix You stream completes
        chatManager.setCurrentSong("Yellow", "Coldplay", "Lyrics 2...", null);
        assertTrue(chatManager.getHistory().isEmpty());

        // Late completion arrives for Fix You
        mockApiClient.triggerLateStreamCompletion("Jawaban Fix You yang terlambat");

        // The new song history must remain empty, unaffected by the previous song's late response
        assertTrue("New song history must not be contaminated by cancelled previous song stream",
                chatManager.getHistory().isEmpty());
    }

    @Test
    public void testSongChatManagerClearHistory() {
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lyrics...", null);
        mockApiClient.mockStreamFullText = "Jawaban.";
        chatManager.sendMessage("Pertanyaan", false, null);

        assertEquals(2, chatManager.getHistory().size());

        chatManager.clearHistory();
        assertTrue(chatManager.getHistory().isEmpty());
        // Song context should remain intact
        assertEquals("Fix You", chatManager.getTitle());
    }

    @Test
    public void testSongChatManagerSetCurrentSongResetsHistory() {
        chatManager.setCurrentSong("Fix You", "Coldplay", "Lyrics...", null);
        mockApiClient.mockStreamFullText = "Jawaban.";
        chatManager.sendMessage("Pertanyaan", false, null);
        assertEquals(2, chatManager.getHistory().size());

        // Switching song
        chatManager.setCurrentSong("Yellow", "Coldplay", "Look at the stars...", null);
        assertTrue(chatManager.getHistory().isEmpty());
        assertEquals("Yellow", chatManager.getTitle());
    }

    // ==========================================
    // Test Doubles & Helpers
    // ==========================================

    private static class MockApiClient extends AntigravityApiClient {
        int generateContentCallCount = 0;
        int generateContentStreamCallCount = 0;

        String mockCompletionResponse;
        List<String> mockWebSources = new ArrayList<>();
        List<String> mockStreamDeltas = new ArrayList<>();
        String mockStreamFullText;
        Exception mockError;
        boolean autoCompleteStream = true;

        List<AiChatMessage> lastStreamMessages;
        StreamListener activeListener;

        MockApiClient() {
            super(null, null);
        }

        @Override
        public Cancellable generateContent(String modelId, List<AiChatMessage> messages,
                                          boolean enableWebSearch, String effortLevel,
                                          CompletionCallback callback) {
            generateContentCallCount++;
            final MockCancellable handle = new MockCancellable();

            if (mockError != null) {
                if (callback != null) callback.onError(mockError);
                return handle;
            }

            if (callback != null) {
                callback.onComplete(mockCompletionResponse != null ? mockCompletionResponse : "{}",
                        mockWebSources, 10, 20);
            }
            return handle;
        }

        @Override
        public Cancellable generateContentStream(String modelId, List<AiChatMessage> messages,
                                                boolean enableWebSearch, String effortLevel,
                                                final StreamListener listener) {
            generateContentStreamCallCount++;
            this.lastStreamMessages = messages != null ? new ArrayList<>(messages) : null;
            this.activeListener = listener;
            final MockCancellable handle = new MockCancellable();

            if (mockError != null) {
                if (listener != null) listener.onError(mockError);
                return handle;
            }

            if (autoCompleteStream && listener != null) {
                if (mockStreamDeltas != null) {
                    for (String delta : mockStreamDeltas) {
                        if (handle.isCancelled()) break;
                        listener.onDelta(delta);
                    }
                }
                if (!handle.isCancelled()) {
                    listener.onComplete(
                            mockStreamFullText != null ? mockStreamFullText : "",
                            mockWebSources,
                            15,
                            30
                    );
                }
            }

            return handle;
        }

        void triggerLateStreamCompletion(String fullText) {
            if (activeListener != null) {
                activeListener.onComplete(fullText, mockWebSources, 10, 20);
            }
        }
    }

    private static class MockCancellable implements AntigravityApiClient.Cancellable {
        private volatile boolean cancelled = false;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object val = values.get(key);
            return val instanceof String ? (String) val : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return null;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object val = values.get(key);
            return val instanceof Integer ? (Integer) val : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object val = values.get(key);
            return val instanceof Long ? (Long) val : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object val = values.get(key);
            return val instanceof Float ? (Float) val : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object val = values.get(key);
            return val instanceof Boolean ? (Boolean) val : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        class FakeEditor implements Editor {
            private final Map<String, Object> changes = new HashMap<>(values);

            @Override
            public Editor putString(String key, String value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                changes.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                changes.clear();
                return this;
            }

            @Override
            public boolean commit() {
                values.clear();
                values.putAll(changes);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
