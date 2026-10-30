package com.lyricsync.app.ai.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Exa results become the same sources + JSON the model gets from Gemini grounding. */
public class ExaSearchTest {

    @Test
    public void resultsBecomeSourcesWithHighlights() {
        AntigravityToolExecutor.ToolResult r = ExaSearch.parse("song meaning", "{\"results\":["
                + "{\"title\":\"Interview\",\"url\":\"https://a.com/x\",\"publishedDate\":\"2026-01-02T00:00:00Z\",\"highlights\":[\"It is about loss.\",\"Written in 2006.\"]},"
                + "{\"title\":\"\",\"url\":\"https://b.com\",\"text\":\"Body text\"},"
                + "{\"title\":\"no url\"}]}");
        assertEquals(false, r.isError);
        assertEquals(2, r.sources.size());
        assertEquals("https://b.com", r.sources.get(1).title);
        JsonObject out = JsonParser.parseString(r.content).getAsJsonObject();
        JsonObject first = out.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("It is about loss. … Written in 2006.", first.get("highlights").getAsString());
        assertEquals("2026-01-02", first.get("published").getAsString());
    }

    @Test
    public void emptyOrBrokenIsAToolError() {
        assertTrue(ExaSearch.parse("q", "{\"results\":[]}").isError);
        assertTrue(ExaSearch.parse("q", "<html>").isError);
    }
}
