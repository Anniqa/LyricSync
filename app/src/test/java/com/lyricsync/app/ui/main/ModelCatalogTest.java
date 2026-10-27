package com.lyricsync.app.ui.main;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** ModelCatalog must reproduce the old MainActivity.resolveModelMeta mapping exactly. */
public class ModelCatalogTest {

    private static void check(String id, String name, String desc, ModelCatalog.Icon icon) {
        ModelCatalog.Entry e = ModelCatalog.resolve(id, null);
        assertEquals(id + " name", name, e.displayName);
        assertEquals(id + " desc", desc, e.description);
        assertEquals(id + " icon", icon, e.icon);
    }

    @Test
    public void baseModelsMapLikeBefore() {
        ModelCatalog.Icon g = ModelCatalog.Icon.GEMINI;
        ModelCatalog.Icon c = ModelCatalog.Icon.CLAUDE;
        check("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)", "Extended Thinking · High Effort", g);
        check("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Med)", "Thinking · Balanced Reasoning", g);
        check("gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)", "Ultra Low Latency · Fast Reasoning", g);
        check("gemini-3.8-flash", "Gemini 3.8 Flash", "Thinking · Adaptable Reasoning", g);
        check("gemini-3.7-flash-low", "Gemini 3.7 Flash (Low)", "Low Latency · Fast", g);
        check("gemini-3.6-flash-high", "Gemini 3.6 Flash (High)", "Thinking · High Effort", g);
        check("gemini-3.5-flash-low", "Gemini 3.5 Flash (Med)", "Balanced Reasoning", g);
        check("gemini-3.5-flash-extra-low", "Gemini 3.5 Flash (Low)", "Low Latency · Low Cost", g);
        check("gemini-3-flash-agent", "Gemini 3.5 Flash (High)", "Agentic Reasoning · High Effort", g);
        check("gemini-3-pro-high", "Gemini 3 Pro (High)", "Deep Reasoning · High Effort", g);
        check("gemini-3-pro-low", "Gemini 3 Pro (Low)", "Pro Reasoning · Low Latency", g);
        check("gemini-pro-agent", "Gemini 3.1 Pro (High)", "Deep Analysis · Agentic High Effort", g);
        check("gemini-3.1-pro-low", "Gemini 3.1 Pro (Low)", "Pro Reasoning · Low Latency", g);
        check("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", "Ultra Lightweight · Instant Response", g);
        check("gemini-2.5-pro", "Gemini 2.5 Pro", "Complex Reasoning & Analysis", g);
        check("gemini-2.5-flash-thinking", "Gemini 2.5 Flash Thinking", "Fast & Efficient · High Quota", g);
        check("claude-sonnet-4-6", "Claude Sonnet 4.6", "Extended Thinking · Deep Understanding", c);
        check("claude-opus-4-6-thinking", "Claude Opus 4.6", "Maximum Intelligence · Extended Thinking", c);
        check("claude-3-7-sonnet", "Claude 3.7 Sonnet", "Hybrid Reasoning & Poetic Lyrics", c);
        check("claude-3-5-sonnet", "Claude 3.5 Sonnet", "Nuanced Lyrics Understanding", c);
        check("gpt-oss-120b-medium", "GPT-OSS 120B", "Open Weights · Medium Effort", ModelCatalog.Icon.SPARKLE);
    }

    @Test
    public void blankFallsBackToDefaultAndUnknownKeepsId() {
        assertEquals("gemini-3.8-flash-high", ModelCatalog.resolve(null, null).id);
        assertEquals("gemini-3.8-flash-high", ModelCatalog.resolve("  ", null).id);
        assertEquals(com.lyricsync.app.ai.service.SongChatManager.DEFAULT_MODEL, ModelCatalog.DEFAULT_MODEL);
        check("my-custom-model", "my-custom-model", "Google Antigravity", ModelCatalog.Icon.GEMINI);
        assertEquals("trimmed", ModelCatalog.resolve("  trimmed ", null).id);
    }

    @Test
    public void serverDisplayNameWinsUnlessBlankOrUndefined() {
        assertEquals("Fancy", ModelCatalog.resolve("gemini-3-pro-high", id -> " Fancy ").displayName);
        assertEquals("Gemini 3 Pro (High)", ModelCatalog.resolve("gemini-3-pro-high", id -> "undefined").displayName);
        assertEquals("Gemini 3 Pro (High)", ModelCatalog.resolve("gemini-3-pro-high", id -> " ").displayName);
        assertEquals("Gemini 3 Pro (High)", ModelCatalog.resolve("gemini-3-pro-high", id -> null).displayName);
    }

    @Test
    public void baseListUnchanged() {
        assertEquals(22, ModelCatalog.BASE_MODELS.size());
        assertEquals("gemini-3.8-flash-high", ModelCatalog.BASE_MODELS.get(0));
        assertEquals("gpt-oss-120b-medium", ModelCatalog.BASE_MODELS.get(21));
    }
}
