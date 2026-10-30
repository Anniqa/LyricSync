package com.lyricsync.app.ai.client;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What users paste as a base URL, which web search engine runs, and the /models scan. */
public class AiProviderTest {

    @Test
    public void baseUrlAcceptsWhatUsersPaste() {
        assertEquals("https://api.deepseek.com/v1", AiProvider.normalizeBaseUrl("api.deepseek.com"));
        assertEquals("https://api.river.ai/v1", AiProvider.normalizeBaseUrl(" https://api.river.ai/v1/ "));
        assertEquals("https://openrouter.ai/api/v1", AiProvider.normalizeBaseUrl("https://openrouter.ai/api/v1/chat/completions"));
        assertEquals("http://192.168.1.5:11434/v1", AiProvider.normalizeBaseUrl("http://192.168.1.5:11434"));
        assertEquals("https://api.example.com/openai/v1", AiProvider.normalizeBaseUrl("https://api.example.com/openai/v1/models"));
        assertEquals("", AiProvider.normalizeBaseUrl("not a url at all"));
        assertEquals("", AiProvider.normalizeBaseUrl(""));
    }

    @Test
    public void customProviderNeedsUrlAndModelButNotAKey() {
        assertTrue(new AiProvider.Settings(true, "localhost:11434", "", "llama3", "", false).customReady());
        assertFalse(new AiProvider.Settings(true, "api.deepseek.com", "sk", "", "", false).customReady());
        assertFalse(new AiProvider.Settings(false, "api.deepseek.com", "sk", "deepseek-chat", "", false).customReady());
    }

    @Test
    public void searchUsesGeminiWhenSignedInElseExa() {
        AiProvider.Settings none = new AiProvider.Settings(true, "x.ai", "", "m", "", false);
        AiProvider.Settings exa = new AiProvider.Settings(true, "x.ai", "", "m", "exa-key", false);
        AiProvider.Settings exaFirst = new AiProvider.Settings(true, "x.ai", "", "m", "exa-key", true);
        assertEquals(AiProvider.ENGINE_GEMINI, AiProvider.searchEngine(exa, true));
        assertEquals(AiProvider.ENGINE_EXA, AiProvider.searchEngine(exa, false));
        assertEquals(AiProvider.ENGINE_EXA, AiProvider.searchEngine(exaFirst, true));
        assertEquals(AiProvider.ENGINE_GEMINI, AiProvider.searchEngine(none, true));
        assertNull(AiProvider.searchEngine(none, false));
    }

    @Test
    public void modelScanReadsOpenAiAndOtherShapes() {
        // River's real /models response shape.
        assertEquals(Arrays.asList("deepseek-ai/DeepSeek-V4.1-Flash", "moonshotai/Kimi-K3"), OpenAiModels.parse(
                "{\"object\":\"list\",\"data\":[{\"id\":\"moonshotai/Kimi-K3\",\"object\":\"model\"},"
                        + "{\"id\":\"deepseek-ai/DeepSeek-V4.1-Flash\",\"object\":\"model\"},{\"id\":\"moonshotai/Kimi-K3\"}]}"));
        assertEquals(Arrays.asList("a", "b"), OpenAiModels.parse("{\"models\":[{\"name\":\"b\"},{\"name\":\"a\"}]}"));
        assertEquals(Arrays.asList("x"), OpenAiModels.parse("[\"x\"]"));
        assertTrue(OpenAiModels.parse("<html>502</html>").isEmpty());
    }
}
