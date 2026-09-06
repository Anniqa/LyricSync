package com.lyricsync.app.lyrics.translation;

import android.content.SharedPreferences;

import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AiTranslationServiceTest {

    private FakeSharedPreferences fakePrefs;
    private AntigravityAuthManager authManager;
    private MockApiClient mockApiClient;
    private MockFallbackService mockFallbackService;
    private ScheduledExecutorService executorService;
    private AiTranslationService translationService;

    @Before
    public void setUp() {
        fakePrefs = new FakeSharedPreferences();
        // Default authenticated user
        fakePrefs.putString(AntigravityAuthManager.PREF_REFRESH_TOKEN, "mock-refresh-token");
        authManager = new AntigravityAuthManager(fakePrefs);
        mockApiClient = new MockApiClient();
        mockFallbackService = new MockFallbackService();
        executorService = Executors.newSingleThreadScheduledExecutor();

        translationService = new AiTranslationService(
                fakePrefs,
                authManager,
                mockApiClient,
                mockFallbackService,
                executorService,
                200, // 200ms timeout for testing
                null // null handler for direct synchronous test execution
        );
    }

    // ==========================================
    // 1. buildTranslationPrompt Tests
    // ==========================================

    @Test
    public void testBuildTranslationPromptStandard() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Hello world"));
        lines.add(new LyricsData.LyricsLine(1000, 2000, "I see skies of blue"));

        String prompt = AiTranslationService.buildTranslationPrompt(lines, "id");
        assertNotNull(prompt);
        assertTrue(prompt.contains("id"));
        assertTrue(prompt.contains("\"id\":0"));
        assertTrue(prompt.contains("\"text\":\"Hello world\""));
        assertTrue(prompt.contains("\"id\":1"));
        assertTrue(prompt.contains("\"text\":\"I see skies of blue\""));
    }

    @Test
    public void testBuildTranslationPromptEscaping() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "He said \"Hello\" \n & smiled"));

        String prompt = AiTranslationService.buildTranslationPrompt(lines, "es");
        assertNotNull(prompt);
        assertTrue(prompt.contains("es"));
        // Check that quotes and newlines are safely JSON-encoded
        assertTrue(prompt.contains("\\\"Hello\\\""));
        assertTrue(prompt.contains("\\n"));
    }

    @Test
    public void testBuildTranslationPromptEmptyOrNull() {
        String promptNull = AiTranslationService.buildTranslationPrompt(null, "id");
        assertEquals("", promptNull);

        String promptEmpty = AiTranslationService.buildTranslationPrompt(new ArrayList<>(), "id");
        assertEquals("", promptEmpty);
    }

    // ==========================================
    // 2. parseAiTranslation Tests
    // ==========================================

    @Test
    public void testParseAiTranslationCleanJsonArray() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Hello"));
        lines.add(new LyricsData.LyricsLine(1000, 2000, "World"));

        String aiResponse = "[{\"id\": 0, \"text\": \"Halo\"}, {\"id\": 1, \"text\": \"Dunia\"}]";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(2, result.translatedLineCount);
        assertFalse(result.isSameLanguage);
        assertEquals("Halo", lines.get(0).translation);
        assertEquals("Dunia", lines.get(1).translation);
    }

    @Test
    public void testParseAiTranslationMarkdownFenced() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Sun is shining"));
        lines.add(new LyricsData.LyricsLine(1000, 2000, "Weather is sweet"));

        String aiResponse = "```json\n" +
                "[\n" +
                "  {\"id\": 0, \"text\": \"Matahari bersinar\"},\n" +
                "  {\"id\": 1, \"text\": \"Cuaca terasa manis\"}\n" +
                "]\n" +
                "```";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(2, result.translatedLineCount);
        assertEquals("Matahari bersinar", lines.get(0).translation);
        assertEquals("Cuaca terasa manis", lines.get(1).translation);
    }

    @Test
    public void testParseAiTranslationGenericCodeBlock() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Yesterday"));

        String aiResponse = "```\n" +
                "[{\"id\": 0, \"text\": \"Kemarin\"}]\n" +
                "```";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(1, result.translatedLineCount);
        assertEquals("Kemarin", lines.get(0).translation);
    }

    @Test
    public void testParseAiTranslationSurroundedByCommentary() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Let it be"));

        String aiResponse = "Tentu, ini terjemahan liriknya:\n" +
                "```json\n" +
                "[{\"id\": 0, \"text\": \"Biarlah berlalu\"}]\n" +
                "```\n" +
                "Semoga bermanfaat dan menyentuh hati!";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(1, result.translatedLineCount);
        assertEquals("Biarlah berlalu", lines.get(0).translation);
    }

    @Test
    public void testParseAiTranslationAlternativeKeys() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Line 1"));
        lines.add(new LyricsData.LyricsLine(1000, 2000, "Line 2"));

        String aiResponse = "[{\"id\": 0, \"translation\": \"Baris 1\"}, {\"id\": 1, \"translatedText\": \"Baris 2\"}]";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(2, result.translatedLineCount);
        assertEquals("Baris 1", lines.get(0).translation);
        assertEquals("Baris 2", lines.get(1).translation);
    }

    @Test
    public void testParseAiTranslationMissingKeysAndOutOfBounds() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Line 0"));
        lines.add(new LyricsData.LyricsLine(1000, 2000, "Line 1"));

        // id 0 valid, item missing text, id 99 out of bounds, id -5 negative
        String aiResponse = "[\n" +
                "  {\"id\": 0, \"text\": \"Baris Nol\"},\n" +
                "  {\"id\": 1},\n" +
                "  {\"id\": 99, \"text\": \"Hantu\"},\n" +
                "  {\"id\": -5, \"text\": \"Minus\"}\n" +
                "]";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(1, result.translatedLineCount);
        assertEquals("Baris Nol", lines.get(0).translation);
        assertNull(lines.get(1).translation);
    }

    @Test
    public void testParseAiTranslationObjectWrapper() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Line 0"));

        String aiResponse = "{\"lines\": [{\"id\": 0, \"text\": \"Baris Terjemah\"}]}";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(1, result.translatedLineCount);
        assertEquals("Baris Terjemah", lines.get(0).translation);
    }

    @Test
    public void testParseAiTranslationMalformedJson() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Line 0"));

        String aiResponse = "Maaf, saya tidak dapat menerjemahkan lagu ini.";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "id");

        assertNotNull(result);
        assertEquals(0, result.translatedLineCount);
        assertNull(lines.get(0).translation);
    }

    @Test
    public void testParseAiTranslationNullAndEmpty() {
        GoogleTranslateParser.ParseResult res1 = AiTranslationService.parseAiTranslation(null, null, "id");
        assertNotNull(res1);
        assertEquals(0, res1.translatedLineCount);

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        GoogleTranslateParser.ParseResult res2 = AiTranslationService.parseAiTranslation("", lines, "id");
        assertNotNull(res2);
        assertEquals(0, res2.translatedLineCount);
    }

    @Test
    public void testParseAiTranslationSameLanguageDetection() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Hello"));

        String aiResponse = "{\"sourceLang\": \"en\", \"lines\": [{\"id\": 0, \"text\": \"Hello\"}]}";
        GoogleTranslateParser.ParseResult result =
                AiTranslationService.parseAiTranslation(aiResponse, lines, "en");

        assertNotNull(result);
        assertTrue(result.isSameLanguage);
        assertEquals("en", result.detectedSourceLang);
    }

    // ==========================================
    // 3. Fallback Handling & translateLyrics Tests
    // ==========================================

    @Test
    public void testFallbackWhenUserNotLoggedIn() {
        authManager.logout();
        assertFalse(authManager.isAuthenticated());

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        mockFallbackService.onTranslateLines = () -> fallbackCalled.set(true);

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {}
            @Override
            public void onError(Exception error) {}
        });

        assertEquals(0, mockApiClient.generateContentCallCount);
        assertTrue("Fallback service must be called when user is not logged in", fallbackCalled.get());
    }

    @Test
    public void testFallbackWhenAiTranslationDisabled() {
        fakePrefs.putBoolean(AiTranslationService.PREF_AI_TRANSLATION_ENABLED, false);

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        mockFallbackService.onTranslateLines = () -> fallbackCalled.set(true);

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {}
            @Override
            public void onError(Exception error) {}
        });

        assertEquals(0, mockApiClient.generateContentCallCount);
        assertTrue("Fallback service must be called when AI translation is disabled", fallbackCalled.get());
    }

    @Test
    public void testFallbackWhenAiErrors() {
        mockApiClient.mockError = new IOException("PA endpoint 503 unavailable");

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        mockFallbackService.onTranslateLines = () -> fallbackCalled.set(true);

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {}
            @Override
            public void onError(Exception error) {}
        });

        assertEquals(1, mockApiClient.generateContentCallCount);
        assertTrue("Fallback service must be called when AI call errors", fallbackCalled.get());
    }

    @Test
    public void testFallbackWhenAiReturnsZeroLines() {
        mockApiClient.mockCompletionResponse = "Sorry, cannot translate.";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        mockFallbackService.onTranslateLines = () -> fallbackCalled.set(true);

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {}
            @Override
            public void onError(Exception error) {}
        });

        assertEquals(1, mockApiClient.generateContentCallCount);
        assertTrue("Fallback service must be called when AI returns 0 translated lines", fallbackCalled.get());
    }

    @Test
    public void testFallbackOnTimeout() throws Exception {
        mockApiClient.holdResponse = true; // don't reply immediately

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        final CountDownLatch latch = new CountDownLatch(1);
        mockFallbackService.onTranslateLines = latch::countDown;

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {}
            @Override
            public void onError(Exception error) {}
        });

        assertEquals(1, mockApiClient.generateContentCallCount);
        // Wait for timeout (configured to 200ms in setUp)
        boolean signaled = latch.await(1000, TimeUnit.MILLISECONDS);
        assertTrue("Fallback service must be called on timeout", signaled);
        assertTrue("In-flight AI call must be cancelled on timeout", mockApiClient.lastCancellable.isCancelled());
    }

    @Test
    public void testSuccessfulAiTranslation() {
        mockApiClient.mockCompletionResponse = "[{\"id\": 0, \"text\": \"Lirik berhasil diterjemahkan\"}]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        final AtomicReference<GoogleTranslateParser.ParseResult> successResult = new AtomicReference<>();
        final AtomicReference<String> successLang = new AtomicReference<>();

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {
                successResult.set(result);
                successLang.set(targetLang);
            }

            @Override
            public void onError(Exception error) {}
        });

        assertEquals(1, mockApiClient.generateContentCallCount);
        assertEquals(0, mockFallbackService.translateLinesCallCount);
        assertNotNull(successResult.get());
        assertEquals("id", successLang.get());
        assertEquals("Lirik berhasil diterjemahkan", lines.get(0).translation);
    }

    @Test
    public void testCancelAbortsCalls() {
        mockApiClient.holdResponse = true;

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 1000, "Song line"));

        translationService.translateLyrics(lines, "id", new GoogleTranslateService.TranslationCallback() {
            @Override
            public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {}
            @Override
            public void onError(Exception error) {}
        });

        assertNotNull(mockApiClient.lastCancellable);
        assertFalse(mockApiClient.lastCancellable.isCancelled());

        translationService.cancel();

        assertTrue(mockApiClient.lastCancellable.isCancelled());
        assertTrue(mockFallbackService.cancelled);
    }

    // ==========================================
    // Mocks & Fakes
    // ==========================================

    private static class MockApiClient extends AntigravityApiClient {
        int generateContentCallCount = 0;
        String mockCompletionResponse;
        Exception mockError;
        boolean holdResponse = false;
        MockCancellable lastCancellable;

        MockApiClient() {
            super(null, null);
        }

        @Override
        public Cancellable generateContent(String modelId, List<AiChatMessage> messages,
                                          boolean enableWebSearch, String effortLevel,
                                          CompletionCallback callback) {
            generateContentCallCount++;
            lastCancellable = new MockCancellable();

            if (holdResponse) {
                return lastCancellable;
            }

            if (mockError != null) {
                if (callback != null) callback.onError(mockError);
                return lastCancellable;
            }

            if (callback != null) {
                callback.onComplete(mockCompletionResponse != null ? mockCompletionResponse : "[]",
                        new ArrayList<>(), 10, 20);
            }
            return lastCancellable;
        }
    }

    private static class MockFallbackService extends GoogleTranslateService {
        int translateLinesCallCount = 0;
        boolean cancelled = false;
        Runnable onTranslateLines;

        MockFallbackService() {
            super(null, null);
        }

        @Override
        public synchronized void translateLines(List<LyricsData.LyricsLine> allLines,
                                                String targetLang,
                                                TranslationCallback callback) {
            translateLinesCallCount++;
            if (onTranslateLines != null) {
                onTranslateLines.run();
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
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

        public void putString(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        public void remove(String key) {
            values.remove(key);
        }

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
