package com.lyricsync.app.ai.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A model does not always type arguments the way the schema asks — an INTEGER index
 * commonly arrives as {@code "3"}. These cover the lenient reads that keep one mistyped
 * argument from failing the whole tool call.
 */
public class ToolArgsTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void intValueAcceptsNumberAndNumericString() {
        JsonObject a = args("{\"line_index\":3,\"other\":\"7\"}");
        assertEquals(3, ToolArgs.intValue(a, "line_index", -1));
        assertEquals(7, ToolArgs.intValue(a, "other", -1));
    }

    @Test
    public void intValueFallsBackWhenAbsentOrUnusable() {
        JsonObject a = args("{\"text\":\"abc\"}");
        assertEquals(-1, ToolArgs.intValue(a, "missing", -1));
        assertEquals(-1, ToolArgs.intValue(a, "text", -1));
        assertEquals(-1, ToolArgs.intValue(a, "n", -1));
    }

    @Test
    public void intValueTreatsJsonNullAsAbsent() {
        JsonObject a = args("{\"line_index\":null}");
        assertEquals(-1, ToolArgs.intValue(a, "line_index", -1));
        assertNull(ToolArgs.intOrNull(a, "line_index"));
    }

    @Test
    public void boolAcceptsBooleanAndItsStringForm() {
        JsonObject a = args("{\"enabled\":true,\"off\":false,\"on\":\"true\",\"no\":\"FALSE\"}");
        assertTrue(ToolArgs.bool(a, "enabled", false));
        assertFalse(ToolArgs.bool(a, "off", true));
        assertTrue(ToolArgs.bool(a, "on", false));
        assertFalse(ToolArgs.bool(a, "no", true));
    }

    @Test
    public void boolFallsBackOnAnythingElse() {
        JsonObject a = args("{\"feature\":\"romaji\"}");
        assertTrue(ToolArgs.bool(a, "feature", true));
        assertFalse(ToolArgs.bool(a, "missing", false));
    }

    @Test
    public void strKeepsAbsentDistinguishableFromEmpty() {
        JsonObject a = args("{\"text\":\"\",\"n\":5}");
        // null fallback is how callers say "leave unchanged"
        assertNull(ToolArgs.str(a, "missing", null));
        assertEquals("", ToolArgs.str(a, "text", "fallback"));
        assertEquals("5", ToolArgs.str(a, "n", null));
    }

    @Test
    public void nullableReadsReturnNullRatherThanAFallback() {
        JsonObject a = args("{\"font_scale\":1.5,\"width_percent\":80}");
        assertEquals(Float.valueOf(1.5f), ToolArgs.floatOrNull(a, "font_scale"));
        assertEquals(Integer.valueOf(80), ToolArgs.intOrNull(a, "width_percent"));
        assertNull(ToolArgs.floatOrNull(a, "anim_style"));
        assertNull(ToolArgs.intOrNull(a, "missing"));
    }

    @Test
    public void nullArgsNeverThrows() {
        assertEquals(-1, ToolArgs.intValue(null, "line_index", -1));
        assertEquals(0L, ToolArgs.longValue(null, "shift_ms", 0L));
        assertFalse(ToolArgs.bool(null, "enabled", false));
        assertNull(ToolArgs.str(null, "text", null));
        assertNull(ToolArgs.floatOrNull(null, "font_scale"));
    }
}
