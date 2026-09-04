package com.lyricsync.app.lyrics.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;

public class SpicyLyricsObjPackTest {

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Expected: " + expected + " but got: " + actual);
    }

    private static void assertTrue(String msg, boolean condition) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void assertTrue(boolean condition) {
        assertTrue("Condition was false", condition);
    }

    private static void assertNotNull(String msg, Object obj) {
        if (obj == null) throw new AssertionError(msg);
    }

    @Test
    public void testDecodeEmptyObject() {
        JsonArray values = new JsonArray();
        JsonArray stream = new JsonArray();
        stream.add(-6);

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);

        JsonElement result = SpicyLyricsObjPack.unpack(packed);
        assertTrue(result.isJsonObject());
        assertEquals(0, result.getAsJsonObject().size());
    }

    @Test
    public void testDecodeEmptyArray() {
        JsonArray values = new JsonArray();
        JsonArray stream = new JsonArray();
        stream.add(-4);

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);

        JsonElement result = SpicyLyricsObjPack.unpack(packed);
        assertTrue(result.isJsonArray());
        assertEquals(0, result.getAsJsonArray().size());
    }

    @Test
    public void testDecodePrimitivePointer() {
        JsonArray values = new JsonArray();
        values.add("hello world");
        JsonArray stream = new JsonArray();
        stream.add(0);

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);

        JsonElement result = SpicyLyricsObjPack.unpack(packed);
        assertTrue(result.isJsonPrimitive());
        assertEquals("hello world", result.getAsString());
    }

    @Test
    public void testDecodeStandardObject() {
        JsonArray values = new JsonArray();
        values.add("title");
        values.add("Never Gonna Give You Up");
        values.add("year");
        values.add(1987);

        JsonArray stream = new JsonArray();
        stream.add(-1); // object
        stream.add(2);  // numKeys = 2
        stream.add(0);  // key "title"
        stream.add(2);  // key "year"
        stream.add(1);  // value "Never Gonna Give You Up"
        stream.add(3);  // value 1987

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);

        JsonElement result = SpicyLyricsObjPack.unpack(packed);
        assertTrue(result.isJsonObject());
        JsonObject obj = result.getAsJsonObject();
        assertEquals("Never Gonna Give You Up", obj.get("title").getAsString());
        assertEquals(1987, obj.get("year").getAsInt());
    }

    @Test
    public void testDecodeSchemaArray() {
        // schema array: 2 objects with keys "startTime" and "text"
        JsonArray values = new JsonArray();
        values.add("startTime"); // 0
        values.add("text");      // 1
        values.add(1000);        // 2
        values.add("Never");     // 3
        values.add(2000);        // 4
        values.add("gonna");     // 5

        JsonArray stream = new JsonArray();
        stream.add(-3); // schema array
        stream.add(2);  // numItems = 2
        stream.add(2);  // numKeys = 2
        stream.add(0);  // key 0: startTime
        stream.add(1);  // key 1: text
        // item 0:
        stream.add(2);  // startTime = 1000
        stream.add(3);  // text = "Never"
        // item 1:
        stream.add(4);  // startTime = 2000
        stream.add(5);  // text = "gonna"

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);

        JsonElement result = SpicyLyricsObjPack.unpack(packed);
        assertTrue(result.isJsonArray());
        JsonArray arr = result.getAsJsonArray();
        assertEquals(2, arr.size());

        JsonObject item0 = arr.get(0).getAsJsonObject();
        assertEquals(1000, item0.get("startTime").getAsInt());
        assertEquals("Never", item0.get("text").getAsString());

        JsonObject item1 = arr.get(1).getAsJsonObject();
        assertEquals(2000, item1.get("startTime").getAsInt());
        assertEquals("gonna", item1.get("text").getAsString());
    }

    @Test
    public void testDecodeRealPayloadSample() throws Exception {
        File sampleFile = new File("/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/f3c2e094-0a71-468f-8134-4f37264799df/scratch/sample_spicy.json");
        if (!sampleFile.exists()) {
            System.out.println("sample file not found, skipping real sample test");
            return;
        }

        try (FileReader reader = new FileReader(sampleFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray queries = root.getAsJsonArray("queries");
            JsonArray data = null;
            for (JsonElement q : queries) {
                if (q.isJsonObject() && q.getAsJsonObject().has("result")) {
                    JsonObject res = q.getAsJsonObject().getAsJsonObject("result");
                    if (res.has("data") && res.get("data").isJsonArray()) {
                        data = res.getAsJsonArray("data");
                        break;
                    }
                }
            }

            assertNotNull("data must be present in sample", data);
            JsonElement unpacked = SpicyLyricsObjPack.unpack(data);
            assertTrue("unpacked must be JsonObject", unpacked.isJsonObject());
            JsonObject resultObj = unpacked.getAsJsonObject();

            assertTrue("Result must have Type", resultObj.has("Type"));
            assertEquals("Syllable", resultObj.get("Type").getAsString());
            assertTrue("Result must have Content", resultObj.has("Content"));
            JsonArray content = resultObj.getAsJsonArray("Content");
            assertTrue("Content must not be empty", content.size() > 0);

            JsonObject firstLine = content.get(0).getAsJsonObject();
            assertTrue("First line must have Lead or Syllables or Text",
                    firstLine.has("Lead") || firstLine.has("Syllables") || firstLine.has("Text"));
        }
    }

    public static void main(String[] args) throws Exception {
        SpicyLyricsObjPackTest test = new SpicyLyricsObjPackTest();
        System.out.println("Running testDecodeEmptyObject...");
        test.testDecodeEmptyObject();
        System.out.println("Running testDecodeEmptyArray...");
        test.testDecodeEmptyArray();
        System.out.println("Running testDecodePrimitivePointer...");
        test.testDecodePrimitivePointer();
        System.out.println("Running testDecodeStandardObject...");
        test.testDecodeStandardObject();
        System.out.println("Running testDecodeSchemaArray...");
        test.testDecodeSchemaArray();
        System.out.println("Running testDecodeRealPayloadSample...");
        test.testDecodeRealPayloadSample();
        System.out.println("ALL TESTS PASSED!");
    }
}
