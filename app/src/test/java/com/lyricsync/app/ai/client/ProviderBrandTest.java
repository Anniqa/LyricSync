package com.lyricsync.app.ai.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Every provider row shows its own logo; unknown hosts get the generic API mark. */
public class ProviderBrandTest {

    @Test
    public void logosFollowTheBaseUrl() {
        assertEquals(ProviderBrand.Logo.DEEPSEEK, ProviderBrand.forUrl("https://api.deepseek.com/v1").logo);
        assertEquals(ProviderBrand.Logo.RIVER, ProviderBrand.forUrl("api.river.ai").logo);
        assertEquals(ProviderBrand.Logo.OPENROUTER, ProviderBrand.forUrl("https://openrouter.ai/api/v1").logo);
        assertEquals(ProviderBrand.Logo.OPENAI, ProviderBrand.forUrl("https://api.openai.com/v1").logo);
        assertEquals(ProviderBrand.Logo.OLLAMA, ProviderBrand.forUrl("http://192.168.1.5:11434").logo);
        assertEquals(ProviderBrand.Logo.OLLAMA, ProviderBrand.forUrl("http://127.0.0.1:8787/v1").logo);
        assertEquals(ProviderBrand.Logo.API, ProviderBrand.forUrl("https://llm.example.com/v1").logo);
        assertEquals(ProviderBrand.Logo.API, ProviderBrand.forUrl("").logo);
        for (AiProfiles.Preset p : AiProfiles.PRESETS) {
            assertEquals("presets never fall back to the generic mark: " + p.name, false,
                    ProviderBrand.forUrl(p.baseUrl).logo == ProviderBrand.Logo.API);
        }
    }
}
