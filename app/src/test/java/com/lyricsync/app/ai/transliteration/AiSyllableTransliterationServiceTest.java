package com.lyricsync.app.ai.transliteration;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.model.LyricsData;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AiSyllableTransliterationServiceTest {

    @Test
    public void testAlignSyllablesExact1to1Match() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 5000, "前前前世");
        line.words.add(new LyricsData.Word(0, 1000, "前"));
        line.words.add(new LyricsData.Word(1000, 2000, "前"));
        line.words.add(new LyricsData.Word(2000, 3000, "前"));
        line.words.add(new LyricsData.Word(3000, 5000, "世"));

        List<String> aiTokens = Arrays.asList("zen", "zen", "zen", "se");
        AiSyllableTransliterationService.alignSyllables(line, aiTokens, "zenzenzense");

        assertEquals("zen", line.words.get(0).romanji);
        assertEquals("zen", line.words.get(1).romanji);
        assertEquals("zen", line.words.get(2).romanji);
        assertEquals("se", line.words.get(3).romanji);
        assertEquals("zenzenzense", line.romanji);
    }

    @Test
    public void testAlignSyllablesFallbackTooFewAiTokens() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 5000, "前前前世");
        line.words.add(new LyricsData.Word(0, 1000, "前"));
        line.words.add(new LyricsData.Word(1000, 2000, "前"));
        line.words.add(new LyricsData.Word(2000, 3000, "前"));
        line.words.add(new LyricsData.Word(3000, 5000, "世"));

        // 2 word tokens returned instead of 4 syllables
        List<String> aiTokens = Arrays.asList("zenzen", "zense");
        AiSyllableTransliterationService.alignSyllables(line, aiTokens, "zenzen zense");

        // Mora-aware decomposition splits "zenzen" and "zense" across all 4 syllables
        assertEquals("zen", line.words.get(0).romanji);
        assertEquals("zen", line.words.get(1).romanji);
        assertEquals("zen", line.words.get(2).romanji);
        assertEquals("se", line.words.get(3).romanji);
    }

    @Test
    public void testAlignSyllablesMoraDecompositionWordsToKana() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "あなたのことがすきだ");
        line.words.add(new LyricsData.Word(0, 300, "あ"));
        line.words.add(new LyricsData.Word(300, 600, "な"));
        line.words.add(new LyricsData.Word(600, 900, "た"));
        line.words.add(new LyricsData.Word(900, 1200, "の"));
        line.words.add(new LyricsData.Word(1200, 1500, "こ"));
        line.words.add(new LyricsData.Word(1500, 1800, "と"));
        line.words.add(new LyricsData.Word(1800, 2100, "が"));
        line.words.add(new LyricsData.Word(2100, 2400, "す"));
        line.words.add(new LyricsData.Word(2400, 2700, "き"));
        line.words.add(new LyricsData.Word(2700, 3000, "だ"));

        List<String> aiWordTokens = Arrays.asList("anata", "no", "koto", "ga", "sukida");
        AiSyllableTransliterationService.alignSyllables(line, aiWordTokens, "anata no koto ga sukida");

        assertEquals("a", line.words.get(0).romanji);
        assertEquals("na", line.words.get(1).romanji);
        assertEquals("ta", line.words.get(2).romanji);
        assertEquals("no", line.words.get(3).romanji);
        assertEquals("ko", line.words.get(4).romanji);
        assertEquals("to", line.words.get(5).romanji);
        assertEquals("ga", line.words.get(6).romanji);
        assertEquals("su", line.words.get(7).romanji);
        assertEquals("ki", line.words.get(8).romanji);
        assertEquals("da", line.words.get(9).romanji);
    }

    @Test
    public void testAlignSyllablesMoreTokensThanSyllables() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "君の前世");
        line.words.add(new LyricsData.Word(0, 1500, "君の"));
        line.words.add(new LyricsData.Word(1500, 3000, "前世"));

        List<String> aiTokens = Arrays.asList("kimi", "no", "zen", "se");
        AiSyllableTransliterationService.alignSyllables(line, aiTokens, "kimi no zense");

        assertEquals("kimino", line.words.get(0).romanji);
        assertEquals("zense", line.words.get(1).romanji);
    }

    @Test
    public void testAlignSyllablesKanjiAndKanaLineNoShift() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "君の前世から僕は");
        line.words.add(new LyricsData.Word(0, 300, "君"));
        line.words.add(new LyricsData.Word(300, 600, "の"));
        line.words.add(new LyricsData.Word(600, 900, "前"));
        line.words.add(new LyricsData.Word(900, 1200, "前"));
        line.words.add(new LyricsData.Word(1200, 1500, "前"));
        line.words.add(new LyricsData.Word(1500, 1800, "世"));
        line.words.add(new LyricsData.Word(1800, 2100, "か"));
        line.words.add(new LyricsData.Word(2100, 2400, "ら"));
        line.words.add(new LyricsData.Word(2400, 2700, "僕"));
        line.words.add(new LyricsData.Word(2700, 3000, "は"));

        List<String> aiTokens = Arrays.asList("kimi", "no", "zenzenzense", "kara", "boku", "wa");
        AiSyllableTransliterationService.alignSyllables(line, aiTokens, "kimi no zenzenzense kara boku wa");

        assertEquals("kimi", line.words.get(0).romanji);
        assertEquals("no", line.words.get(1).romanji);
        assertEquals("zen", line.words.get(2).romanji);
        assertEquals("zen", line.words.get(3).romanji);
        assertEquals("zen", line.words.get(4).romanji);
        assertEquals("se", line.words.get(5).romanji);
        assertEquals("ka", line.words.get(6).romanji);
        assertEquals("ra", line.words.get(7).romanji);
        assertEquals("boku", line.words.get(8).romanji);
        assertEquals("wa", line.words.get(9).romanji);
    }

    @Test
    public void testAlignSyllablesFallbackNullOrEmptyTokens() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "こんにちは");
        line.words.add(new LyricsData.Word(0, 1500, "こん"));
        line.words.add(new LyricsData.Word(1500, 3000, "にちは"));

        AiSyllableTransliterationService.alignSyllables(line, null, "kon nichiha");

        assertEquals("kon", line.words.get(0).romanji);
        assertEquals("nichiha", line.words.get(1).romanji);
        assertEquals("kon nichiha", line.romanji);
    }

    @Test
    public void testAlignSyllablesFallbackWithNullInTokens() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "前世");
        line.words.add(new LyricsData.Word(0, 1500, "前"));
        line.words.add(new LyricsData.Word(1500, 3000, "世"));

        List<String> aiTokensFallback = new ArrayList<>();
        aiTokensFallback.add(null);
        aiTokensFallback.add("se");
        AiSyllableTransliterationService.alignSyllables(line, aiTokensFallback, "zen se");

        assertEquals("", line.words.get(0).romanji);
        assertEquals("se", line.words.get(1).romanji);
    }

    @Test
    public void testParseJsonResponse() {
        String json = "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"zenzenzense kara boku wa\",\n" +
                "      \"translation\": \"Bahkan sejak kehidupan masa laluku\",\n" +
                "      \"wordsRomanji\": [\"zen\", \"zen\", \"zen\", \"se\", \"kara\", \"boku\", \"wa\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<AiSyllableTransliterationService.LineTransliterationResult> results =
                AiSyllableTransliterationService.parseJsonResponse(json);

        assertNotNull(results);
        assertEquals(1, results.size());
        AiSyllableTransliterationService.LineTransliterationResult item = results.get(0);
        assertEquals(0, item.index);
        assertEquals("zenzenzense kara boku wa", item.romanji);
        assertEquals("Bahkan sejak kehidupan masa laluku", item.translation);
        assertEquals(7, item.wordsRomanji.size());
        assertEquals("zen", item.wordsRomanji.get(0));
    }

    @Test
    public void testParseJsonResponseWithMarkdownFences() {
        String json = "```json\n" +
                "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"hikari\",\n" +
                "      \"translation\": \"Cahaya\",\n" +
                "      \"wordsRomanji\": [\"hi\", \"ka\", \"ri\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "```";

        List<AiSyllableTransliterationService.LineTransliterationResult> results =
                AiSyllableTransliterationService.parseJsonResponse(json);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("hikari", results.get(0).romanji);
        assertEquals("Cahaya", results.get(0).translation);
        assertEquals(3, results.get(0).wordsRomanji.size());
    }

    @Test
    public void testParseJsonResponseEmptyOrMalformed() {
        List<AiSyllableTransliterationService.LineTransliterationResult> r1 =
                AiSyllableTransliterationService.parseJsonResponse("");
        assertTrue(r1.isEmpty());

        List<AiSyllableTransliterationService.LineTransliterationResult> r2 =
                AiSyllableTransliterationService.parseJsonResponse("not valid json at all");
        assertTrue(r2.isEmpty());
    }

    @Test
    public void testParseJsonResponseWithJsonNullElements() {
        String json = "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"zen se\",\n" +
                "      \"translation\": \"masa lalu\",\n" +
                "      \"wordsRomanji\": [null, \"se\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<AiSyllableTransliterationService.LineTransliterationResult> results =
                AiSyllableTransliterationService.parseJsonResponse(json);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("", results.get(0).wordsRomanji.get(0));
        assertEquals("se", results.get(0).wordsRomanji.get(1));
    }

    @Test
    public void testBuildPromptPayload() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "スパークル");
        line.words.add(new LyricsData.Word(0, 1500, "スパー"));
        line.words.add(new LyricsData.Word(1500, 3000, "クル"));
        lines.add(line);

        String prompt = AiSyllableTransliterationService.buildPromptPayload("Sparkle", "RADWIMPS", lines, "id");
        assertTrue(prompt.contains("Sparkle"));
        assertTrue(prompt.contains("RADWIMPS"));
        assertTrue(prompt.contains("スパー"));
        assertTrue(prompt.contains("wordsRomanji"));
    }

    @Test
    public void testBuildPromptPayloadSanitizeNullTargetLang() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        String promptNull = AiSyllableTransliterationService.buildPromptPayload("Title", "Artist", lines, null);
        assertFalse(promptNull.contains("targetLanguage (null)"));
        assertTrue(promptNull.contains("targetLanguage (id)"));

        String promptEmpty = AiSyllableTransliterationService.buildPromptPayload("Title", "Artist", lines, "   ");
        assertFalse(promptEmpty.contains("targetLanguage (null)"));
        assertTrue(promptEmpty.contains("targetLanguage (id)"));
    }

    @Test
    public void testBuildPromptPayloadWithAndWithoutTranslation() {
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "前世");
        line.words.add(new LyricsData.Word(0, 1500, "前"));
        line.words.add(new LyricsData.Word(1500, 3000, "世"));
        lines.add(line);

        String promptWithTrans = AiSyllableTransliterationService.buildPromptPayload("Title", "Artist", lines, "id", true);
        assertTrue(promptWithTrans.contains("\"includeTranslation\": true") || promptWithTrans.contains("\"includeTranslation\":true"));
        assertTrue(promptWithTrans.contains("translate the meaning accurately"));

        String promptOnlyTranslit = AiSyllableTransliterationService.buildPromptPayload("Title", "Artist", lines, "id", false);
        assertTrue(promptOnlyTranslit.contains("\"includeTranslation\": false") || promptOnlyTranslit.contains("\"includeTranslation\":false"));
        assertTrue(promptOnlyTranslit.contains("transliteration ONLY"));
    }

    @Test
    public void testTransliterateSyllablesAsyncSuccess() {
        MockApiClient mockApi = new MockApiClient();
        mockApi.mockResponse = "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"supaaku\",\n" +
                "      \"translation\": \"Percikan\",\n" +
                "      \"wordsRomanji\": [\"supaa\", \"ku\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        AiSyllableTransliterationService service = new AiSyllableTransliterationService(null, mockApi, null, null);

        LyricsData data = new LyricsData();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "スパークル");
        line.words.add(new LyricsData.Word(0, 1500, "スパー"));
        line.words.add(new LyricsData.Word(1500, 3000, "クル"));
        data.lines.add(line);

        final AtomicBoolean successCalled = new AtomicBoolean(false);
        final AtomicInteger progressCount = new AtomicInteger(0);

        service.transliterateSyllablesAsync(data, "Sparkle", "RADWIMPS", "id", new AiSyllableTransliterationService.Callback() {
            @Override
            public void onSuccess(LyricsData enrichedData) {
                successCalled.set(true);
            }

            @Override
            public void onError(String message) {
                fail("onError should not be called: " + message);
            }

            @Override
            public void onProgress(int processedLines, int totalLines) {
                progressCount.set(processedLines);
            }
        });

        assertTrue(successCalled.get());
        assertTrue(data.hasTransliteration);
        assertEquals("JAPANESE", data.sourceScript);
        assertEquals("supaaku", line.romanji);
        assertEquals("Percikan", line.translation);
        assertEquals("supaa", line.words.get(0).romanji);
        assertEquals("ku", line.words.get(1).romanji);
        assertEquals(1, progressCount.get());
    }

    @Test
    public void testTransliterateSyllablesAsyncApiError() {
        MockApiClient mockApi = new MockApiClient();
        mockApi.mockError = new IOException("PA endpoint unavailable");

        AiSyllableTransliterationService service = new AiSyllableTransliterationService(null, mockApi, null, null);

        LyricsData data = new LyricsData();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "スパークル");
        data.lines.add(line);

        final AtomicReference<String> errorMsg = new AtomicReference<>();

        service.transliterateSyllablesAsync(data, "Sparkle", "RADWIMPS", "id", new AiSyllableTransliterationService.Callback() {
            @Override
            public void onSuccess(LyricsData enrichedData) {
                fail("onSuccess should not be called on error");
            }

            @Override
            public void onError(String message) {
                errorMsg.set(message);
            }
        });

        assertNotNull(errorMsg.get());
        assertTrue(errorMsg.get().contains("PA endpoint unavailable"));
    }

    @Test
    public void testTransliterateSyllablesAsyncCancellation() {
        MockApiClient mockApi = new MockApiClient();
        mockApi.holdResponse = true;

        AiSyllableTransliterationService service = new AiSyllableTransliterationService(null, mockApi, null, null);

        LyricsData data = new LyricsData();
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "スパークル");
        data.lines.add(line);

        final AtomicBoolean called = new AtomicBoolean(false);

        AntigravityApiClient.Cancellable handle = service.transliterateSyllablesAsync(data, "Sparkle", "RADWIMPS", "id", new AiSyllableTransliterationService.Callback() {
            @Override
            public void onSuccess(LyricsData enrichedData) {
                called.set(true);
            }

            @Override
            public void onError(String message) {
                called.set(true);
            }
        });

        assertNotNull(handle);
        assertFalse(handle.isCancelled());

        handle.cancel();

        assertTrue(handle.isCancelled());
        assertFalse(called.get());
    }

    private static class MockApiClient extends AntigravityApiClient {
        int callCount = 0;
        String mockResponse;
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
            callCount++;
            lastCancellable = new MockCancellable();
            if (holdResponse) {
                return lastCancellable;
            }
            if (mockError != null) {
                if (callback != null) callback.onError(mockError);
                return lastCancellable;
            }
            if (callback != null) {
                callback.onComplete(mockResponse != null ? mockResponse : "{\"lines\":[]}",
                        new ArrayList<>(), 10, 20);
            }
            return lastCancellable;
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
}
