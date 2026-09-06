# AI Syllable Transliteration & Translation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a 3-layer synchronized lyrics engine for non-Latin songs (Japanese, Chinese, Korean, Cyrillic) featuring 1:1 syllable-timed Romaji/transliteration highlighting, line translation, interactive syllable editing, and persistent SQLite caching.

**Architecture:** Extend `LyricsData` (`Word.romanji`, `LyricsLine.romanji`) and add `ScriptDetector` to classify Unicode scripts. Create `AiSyllableTransliterationService` using Gemini 2.5 Flash with structured JSON schema to align transliterated syllables 1:1 with source timing metadata. Upgrade `SyllableHighlighter` to simultaneously animate Layer 1 (source) and Layer 2 (Romaji) on vocal beats, with Layer 3 (translation) below. Provide interactive editing via `dialog_edit_syllables` and quick toggles on floating overlay.

**Tech Stack:** Java 17, Android SDK 26+, Gson, SQLite (`LyricsCacheManager`), JUnit 4, Antigravity API Client.

**Spec:** [`docs/superpowers/specs/2026-09-06-ai-syllable-transliteration-translation-engine-design.md`](file:///data/data/com.termux/files/home/LyricSync/docs/superpowers/specs/2026-09-06-ai-syllable-transliteration-translation-engine-design.md)

## Global Constraints

- Target git repository: `LyricSync-private` only (`private` remote). NEVER push to public `origin`.
- Pure native Android Java: zero external daemons or Termux CLI dependencies.
- Markdown: zero raw asterisks (`**`) or hashes (`##`) displayed to user.
- Strictly ZERO EMOJIS in all code, layouts, strings, and logs.
- Bilingual support: default English (`res/values/strings.xml`) and Indonesian (`res/values-in/strings.xml`).
- Clean threading: all AI network calls run asynchronously via `AntigravityApiClient.Cancellable` without blocking the main UI thread.

---

### Task 1: Data Model Extensions & Unicode Script Detector

**Files:**
- Modify: `app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java`
- Create: `app/src/main/java/com/lyricsync/app/ai/transliteration/ScriptDetector.java`
- Test: `app/src/test/java/com/lyricsync/app/ai/transliteration/ScriptDetectorTest.java`

**Interfaces:**
- Produces:
  - `LyricsData.Word.romanji` (`String`)
  - `LyricsData.LyricsLine.romanji` (`String`)
  - `LyricsData.hasTransliteration` (`boolean`)
  - `LyricsData.sourceScript` (`String`)
  - `ScriptDetector.ScriptType` (`LATIN, JAPANESE, CHINESE, KOREAN, CYRILLIC, OTHER_NON_LATIN`)
  - `ScriptDetector.detectScript(String text): ScriptType`
  - `ScriptDetector.isNonLatin(String text): boolean`

- [ ] **Step 1: Write the failing unit test for ScriptDetector**

Create `app/src/test/java/com/lyricsync/app/ai/transliteration/ScriptDetectorTest.java`:
```java
package com.lyricsync.app.ai.transliteration;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScriptDetectorTest {

    @Test
    public void testDetectLatin() {
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript("Hello World 123! This is English lyrics."));
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript("Ini adalah lirik bahasa Indonesia."));
        assertFalse(ScriptDetector.isNonLatin("Simple Latin Text"));
    }

    @Test
    public void testDetectJapanese() {
        // Hiragana & Katakana & Kanji
        assertEquals(ScriptDetector.ScriptType.JAPANESE, ScriptDetector.detectScript("前前前世から僕は"));
        assertEquals(ScriptDetector.ScriptType.JAPANESE, ScriptDetector.detectScript("ありがとう"));
        assertEquals(ScriptDetector.ScriptType.JAPANESE, ScriptDetector.detectScript("スパークル"));
        assertTrue(ScriptDetector.isNonLatin("スパークル"));
    }

    @Test
    public void testDetectChinese() {
        // Hanzi without Kana
        assertEquals(ScriptDetector.ScriptType.CHINESE, ScriptDetector.detectScript("我爱你 中国"));
        assertTrue(ScriptDetector.isNonLatin("青花瓷"));
    }

    @Test
    public void testDetectKorean() {
        // Hangul
        assertEquals(ScriptDetector.ScriptType.KOREAN, ScriptDetector.detectScript("사랑해 영원히"));
        assertTrue(ScriptDetector.isNonLatin("강남스타일"));
    }

    @Test
    public void testDetectCyrillic() {
        // Russian / Cyrillic
        assertEquals(ScriptDetector.ScriptType.CYRILLIC, ScriptDetector.detectScript("Группа крови на рукаве"));
        assertTrue(ScriptDetector.isNonLatin("Привет мир"));
    }

    @Test
    public void testEmptyOrNull() {
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript(null));
        assertEquals(ScriptDetector.ScriptType.LATIN, ScriptDetector.detectScript("   "));
        assertFalse(ScriptDetector.isNonLatin(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails (RED phase)**

```bash
CP="/data/data/com.termux/files/home/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib/junit-4.13.2.jar:/data/data/com.termux/files/home/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib/hamcrest-core-1.3.jar"
BIN_DIR="/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/f3c2e094-0a71-468f-8134-4f37264799df/scratch/bin"

javac -cp "$CP:$BIN_DIR" -d "$BIN_DIR" app/src/test/java/com/lyricsync/app/ai/transliteration/ScriptDetectorTest.java
```
Expected: FAIL with compilation error (ScriptDetector not found).

- [ ] **Step 3: Update `LyricsData.java` and implement `ScriptDetector.java`**

In `app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java`:
Add fields to `LyricsData`:
```java
    public boolean hasTransliteration;
    public String sourceScript;
```
Add fields to `LyricsLine`:
```java
    public String romanji;
```
Add fields to `Word`:
```java
    public String romanji;

    public Word(long startTime, long endTime, String text, String romanji) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.text = text;
        this.romanji = romanji;
    }
```

Create `app/src/main/java/com/lyricsync/app/ai/transliteration/ScriptDetector.java`:
```java
package com.lyricsync.app.ai.transliteration;

import java.util.regex.Pattern;

public final class ScriptDetector {

    public enum ScriptType {
        LATIN,
        JAPANESE,
        CHINESE,
        KOREAN,
        CYRILLIC,
        OTHER_NON_LATIN
    }

    private static final Pattern HIRAGANA = Pattern.compile("[\\u3040-\\u309F]");
    private static final Pattern KATAKANA = Pattern.compile("[\\u30A0-\\u30FF]");
    private static final Pattern CJK_IDEOGRAPHS = Pattern.compile("[\\u4E00-\\u9FAF]");
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7AF\\u1100-\\u11FF]");
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");
    private static final Pattern THAI = Pattern.compile("[\\u0E00-\\u0E7F]");
    private static final Pattern GREEK = Pattern.compile("[\\u0370-\\u03FF]");

    private ScriptDetector() {}

    public static ScriptType detectScript(String text) {
        if (text == null || text.trim().isEmpty()) {
            return ScriptType.LATIN;
        }

        boolean hasKana = HIRAGANA.matcher(text).find() || KATAKANA.matcher(text).find();
        boolean hasCjk = CJK_IDEOGRAPHS.matcher(text).find();

        if (hasKana) {
            return ScriptType.JAPANESE;
        }
        if (hasCjk) {
            return ScriptType.CHINESE;
        }
        if (HANGUL.matcher(text).find()) {
            return ScriptType.KOREAN;
        }
        if (CYRILLIC.matcher(text).find()) {
            return ScriptType.CYRILLIC;
        }
        if (ARABIC.matcher(text).find() || THAI.matcher(text).find() || GREEK.matcher(text).find()) {
            return ScriptType.OTHER_NON_LATIN;
        }

        return ScriptType.LATIN;
    }

    public static boolean isNonLatin(String text) {
        return detectScript(text) != ScriptType.LATIN;
    }
}
```

- [ ] **Step 4: Run test to verify it passes (GREEN phase)**

```bash
javac -cp "$CP:$BIN_DIR" -d "$BIN_DIR" \
  app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java \
  app/src/main/java/com/lyricsync/app/ai/transliteration/ScriptDetector.java \
  app/src/test/java/com/lyricsync/app/ai/transliteration/ScriptDetectorTest.java

java -cp "$CP:$BIN_DIR" org.junit.runner.JUnitCore com.lyricsync.app.ai.transliteration.ScriptDetectorTest
```
Expected: PASS (6 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java \
        app/src/main/java/com/lyricsync/app/ai/transliteration/ScriptDetector.java \
        app/src/test/java/com/lyricsync/app/ai/transliteration/ScriptDetectorTest.java
git commit -m "feat(ai): extend LyricsData with romanji fields and add ScriptDetector"
```

---

### Task 2: AI Syllable Transliteration & Alignment Service

**Files:**
- Create: `app/src/main/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationService.java`
- Test: `app/src/test/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationServiceTest.java`

**Interfaces:**
- Consumes:
  - `LyricsData`, `LyricsData.LyricsLine`, `LyricsData.Word`
  - `ScriptDetector`
  - `AntigravityApiClient.Cancellable`
- Produces:
  - `AiSyllableTransliterationService.Callback`: `onSuccess(LyricsData enrichedData)`, `onError(String message)`
  - `AiSyllableTransliterationService.alignSyllables(LyricsLine line, List<String> wordsRomanji, String fallbackRomanji)`
  - `AiSyllableTransliterationService.buildPromptPayload(String title, String artist, List<LyricsLine> batchLines, String targetLang): String`
  - `AiSyllableTransliterationService.parseJsonResponse(String jsonText): List<LineTransliterationResult>`
  - `AiSyllableTransliterationService.transliterateSyllablesAsync(LyricsData data, String trackTitle, String artist, String targetLang, Callback callback): Cancellable`

- [ ] **Step 1: Write the failing unit tests for prompt building, JSON parsing, and syllable alignment**

Create `app/src/test/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationServiceTest.java`:
```java
package com.lyricsync.app.ai.transliteration;

import com.lyricsync.app.lyrics.model.LyricsData;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

        // Only 2 tokens returned instead of 4
        List<String> aiTokens = Arrays.asList("zenzen", "zense");
        AiSyllableTransliterationService.alignSyllables(line, aiTokens, "zenzen zense");

        assertEquals("zenzen", line.words.get(0).romanji);
        assertEquals("zense", line.words.get(1).romanji);
        assertEquals("", line.words.get(2).romanji);
        assertEquals("", line.words.get(3).romanji);
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
}
```

- [ ] **Step 2: Run test to verify it fails (RED phase)**

```bash
javac -cp "$CP:$BIN_DIR" -d "$BIN_DIR" app/src/test/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationServiceTest.java
```
Expected: FAIL with compilation error.

- [ ] **Step 3: Implement `AiSyllableTransliterationService.java`**

Create `app/src/main/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationService.java`:
```java
package com.lyricsync.app.ai.transliteration;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiSyllableTransliterationService {
    private static final String TAG = "AiTranslitService";
    private static final int BATCH_SIZE = 12;

    private final Context context;
    private final AntigravityApiClient apiClient;
    private final LyricsCacheManager cacheManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Gson GSON = new Gson();

    public interface Callback {
        void onSuccess(LyricsData enrichedData);
        void onError(String message);
        default void onProgress(int processedLines, int totalLines) {}
    }

    public static class LineTransliterationResult {
        public int index;
        public String romanji;
        public String translation;
        public List<String> wordsRomanji = new ArrayList<>();
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient) {
        this.context = context.getApplicationContext();
        this.apiClient = apiClient;
        this.cacheManager = new LyricsCacheManager(this.context);
    }

    public static void alignSyllables(LyricsData.LyricsLine line, List<String> aiWordsRomanji, String fallbackRomanjiLine) {
        if (line == null) return;
        if (fallbackRomanjiLine != null && !fallbackRomanjiLine.trim().isEmpty()) {
            line.romanji = fallbackRomanjiLine.trim();
        }

        if (line.words == null || line.words.isEmpty()) {
            return;
        }

        int expectedCount = line.words.size();

        if (aiWordsRomanji != null && aiWordsRomanji.size() == expectedCount) {
            for (int i = 0; i < expectedCount; i++) {
                String token = aiWordsRomanji.get(i);
                line.words.get(i).romanji = token != null ? token.trim() : "";
            }
            return;
        }

        List<String> tokens = new ArrayList<>();
        if (aiWordsRomanji != null && !aiWordsRomanji.isEmpty()) {
            tokens.addAll(aiWordsRomanji);
        } else if (fallbackRomanjiLine != null && !fallbackRomanjiLine.trim().isEmpty()) {
            String[] split = fallbackRomanjiLine.trim().split("\\s+");
            Collections.addAll(tokens, split);
        }

        for (int i = 0; i < expectedCount; i++) {
            if (i < tokens.size()) {
                line.words.get(i).romanji = tokens.get(i).trim();
            } else {
                line.words.get(i).romanji = "";
            }
        }
    }

    public static List<LineTransliterationResult> parseJsonResponse(String jsonText) {
        List<LineTransliterationResult> list = new ArrayList<>();
        if (jsonText == null || jsonText.trim().isEmpty()) return list;

        try {
            String clean = jsonText.trim();
            if (clean.startsWith("```json")) {
                clean = clean.substring(7);
            }
            if (clean.startsWith("```")) {
                clean = clean.substring(3);
            }
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3);
            }
            clean = clean.trim();

            JsonObject root = JsonParser.parseString(clean).getAsJsonObject();
            if (!root.has("lines") || !root.get("lines").isJsonArray()) {
                return list;
            }

            JsonArray arr = root.getAsJsonArray("lines");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                LineTransliterationResult item = new LineTransliterationResult();
                item.index = obj.has("index") ? obj.get("index").getAsInt() : -1;
                item.romanji = obj.has("romanji") ? obj.get("romanji").getAsString() : "";
                item.translation = obj.has("translation") ? obj.get("translation").getAsString() : "";

                if (obj.has("wordsRomanji") && obj.get("wordsRomanji").isJsonArray()) {
                    JsonArray wr = obj.getAsJsonArray("wordsRomanji");
                    for (JsonElement w : wr) {
                        item.wordsRomanji.add(w.getAsString());
                    }
                }
                list.add(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing AI transliteration JSON", e);
        }
        return list;
    }

    public static String buildPromptPayload(String title, String artist, List<LyricsData.LyricsLine> batchLines, String targetLang) {
        JsonObject promptObj = new JsonObject();
        promptObj.addProperty("title", title != null ? title : "");
        promptObj.addProperty("artist", artist != null ? artist : "");
        promptObj.addProperty("targetLanguage", targetLang != null ? targetLang : "id");

        JsonArray linesArr = new JsonArray();
        for (int i = 0; i < batchLines.size(); i++) {
            LyricsData.LyricsLine line = batchLines.get(i);
            JsonObject lineObj = new JsonObject();
            lineObj.addProperty("index", i);
            lineObj.addProperty("text", line.text != null ? line.text : "");

            JsonArray syllArr = new JsonArray();
            if (line.words != null) {
                for (LyricsData.Word w : line.words) {
                    syllArr.add(w.text != null ? w.text : "");
                }
            }
            lineObj.add("syllables", syllArr);
            linesArr.add(lineObj);
        }
        promptObj.add("lines", linesArr);

        return "You are an expert multilingual song lyrics transliteration and translation engine.\n" +
                "Given song lines with syllable segmentation, output a strict JSON object with a \"lines\" array.\n" +
                "Rules:\n" +
                "1. Transliterate non-Latin scripts (Japanese Kanji/Kana to Hepburn Romaji, Chinese to Pinyin, Korean to Revised Romanization, Russian to Latin).\n" +
                "2. For each line, translate the meaning accurately into targetLanguage (" + targetLang + ").\n" +
                "3. CRITICAL: For \"wordsRomanji\", provide an array of strings of EXACTLY THE SAME LENGTH as input \"syllables\".\n" +
                "Output ONLY valid JSON with schema:\n" +
                "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"line transliteration\",\n" +
                "      \"translation\": \"line translation\",\n" +
                "      \"wordsRomanji\": [\"syllable1\", \"syllable2\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "Payload:\n" +
                promptObj.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes (GREEN phase)**

```bash
CP="/data/data/com.termux/files/home/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib/junit-4.13.2.jar:/data/data/com.termux/files/home/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/lib/hamcrest-core-1.3.jar:/data/data/com.termux/files/home/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10/dd9b193aef96e973d5a11ab13cd17430c2e4306b/gson-2.10.jar:/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/f3c2e094-0a71-468f-8134-4f37264799df/scratch/libs/*"

javac -cp "$CP:$BIN_DIR" -d "$BIN_DIR" \
  app/src/main/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationService.java \
  app/src/test/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationServiceTest.java

java -cp "$CP:$BIN_DIR" org.junit.runner.JUnitCore com.lyricsync.app.ai.transliteration.AiSyllableTransliterationServiceTest
```
Expected: PASS (4 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationService.java \
        app/src/test/java/com/lyricsync/app/ai/transliteration/AiSyllableTransliterationServiceTest.java
git commit -m "feat(ai): add AiSyllableTransliterationService with structured 1:1 token alignment"
```

---

### Task 3: Multi-Layer Rendering in `SyllableHighlighter`

**Files:**
- Modify: `app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java`
- Test: `app/src/test/java/com/lyricsync/app/renderer/SyllableHighlighterLayerTest.java`

**Interfaces:**
- Consumes:
  - `LyricsData.LyricsLine.romanji`, `LyricsData.Word.romanji`
- Produces:
  - `LineView.romajiWordFlowLayout` (`WordFlowLayout`)
  - `LineView.romajiWordViews` (`List<GradientWordView>`)
  - `SyllableHighlighter.setTransliterationEnabled(boolean enabled)`
  - `SyllableHighlighter.isTransliterationEnabled(): boolean`
  - `SyllableHighlighter.updateLineTransliteration(int index, List<String> wordsRomanji, String lineRomanji)`

- [ ] **Step 1: Write unit test for SyllableHighlighter layer creation & toggling**

Create `app/src/test/java/com/lyricsync/app/renderer/SyllableHighlighterLayerTest.java`:
```java
package com.lyricsync.app.renderer;

import com.lyricsync.app.lyrics.model.LyricsData;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyllableHighlighterLayerTest {

    @Test
    public void testTransliterationToggleDefaults() {
        LyricsData.LyricsLine line = new LyricsData.LyricsLine(0, 3000, "前前");
        line.romanji = "zen zen";
        line.words.add(new LyricsData.Word(0, 1500, "前", "zen"));
        line.words.add(new LyricsData.Word(1500, 3000, "前", "zen"));

        assertEquals("zen", line.words.get(0).romanji);
        assertEquals("zen", line.words.get(1).romanji);
        assertEquals("zen zen", line.romanji);
    }
}
```

- [ ] **Step 2: Update `SyllableHighlighter.java`**

In `app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java`:
1. In `LineView`, add:
   ```java
   public WordFlowLayout romajiWordFlowLayout;
   public List<GradientWordView> romajiWordViews;
   ```
2. Add fields and getters/setters:
   ```java
   private boolean transliterationEnabled = true;

   public void setTransliterationEnabled(boolean enabled) {
       this.transliterationEnabled = enabled;
       for (LineView lv : lineViews) {
           if (lv.romajiWordFlowLayout != null) {
               boolean hasRomanji = lv.line != null && hasAnyWordRomanji(lv.line);
               lv.romajiWordFlowLayout.setVisibility(enabled && hasRomanji ? View.VISIBLE : View.GONE);
           }
       }
   }

   public boolean isTransliterationEnabled() {
       return transliterationEnabled;
   }

   private boolean hasAnyWordRomanji(LyricsData.LyricsLine line) {
       if (line == null || line.words == null) return false;
       for (LyricsData.Word w : line.words) {
           if (w.romanji != null && !w.romanji.trim().isEmpty()) return true;
       }
       return false;
   }
   ```
3. In `createPerWordView`:
   After creating Layer 1 `flow` for original words:
   Check if `hasAnyWordRomanji(line)`:
   Construct Layer 2 `WordFlowLayout romajiFlow = new WordFlowLayout(context);`
   Add `GradientWordView` for each `word.romanji` (or `word.text` fallback):
   - Font size: `fontSizeSp * 0.82f`
   - Typeface: `fontMedium`
   - Matching `startTime` and `endTime`
   - Add `romajiFlow` to `container`
   - Assign `lv.romajiWordFlowLayout = romajiFlow; lv.romajiWordViews = romajiWordViews;`
4. In `updateProgress`:
   Animate both `lv.wordViews` and `lv.romajiWordViews` simultaneously with the exact same progress values.

- [ ] **Step 3: Run compile and test**

```bash
javac -cp "$CP:$BIN_DIR" -d "$BIN_DIR" \
  app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java \
  app/src/test/java/com/lyricsync/app/renderer/SyllableHighlighterLayerTest.java

java -cp "$CP:$BIN_DIR" org.junit.runner.JUnitCore com.lyricsync.app.renderer.SyllableHighlighterLayerTest
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java \
        app/src/test/java/com/lyricsync/app/renderer/SyllableHighlighterLayerTest.java
git commit -m "feat(renderer): add 3-layer synchronized syllable transliteration rendering in SyllableHighlighter"
```

---

### Task 4: Interactive Syllable Editor Dialog & UI Controls

**Files:**
- Create: `app/src/main/res/layout/dialog_edit_syllables.xml`
- Create: `app/src/main/res/layout/item_edit_syllable_chip.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-in/strings.xml`
- Modify: `app/src/main/java/com/lyricsync/app/MainActivity.java`
- Modify: `app/src/main/java/com/lyricsync/app/overlay/FloatingOverlayService.java`

**Interfaces:**
- Consumes:
  - `ScriptDetector`
  - `AiSyllableTransliterationService`
  - `LyricsData`
- Produces:
  - Quick action suggestion chip in AI Studio and overlay for non-Latin songs
  - Long press on lyric line opens `dialog_edit_syllables`
  - Floating overlay quick toggle for Romaji & Translation

- [ ] **Step 1: Add strings for transliteration and editing**

In `app/src/main/res/values/strings.xml`:
```xml
    <string name="ai_chip_transliterate">Transliterate &amp; Translate Syllables</string>
    <string name="ai_transliterating">Generating Romaji and translation...</string>
    <string name="ai_transliteration_complete">Syllable transliteration ready</string>
    <string name="edit_syllables_title">Edit Syllables &amp; Translation</string>
    <string name="edit_syllables_original">Original Script</string>
    <string name="edit_syllables_romaji">Romaji / Transliteration</string>
    <string name="edit_syllables_translation">Line Meaning</string>
    <string name="btn_ai_regenerate">AI Regenerate</string>
    <string name="toggle_romaji">Romaji</string>
```

In `app/src/main/res/values-in/strings.xml`:
```xml
    <string name="ai_chip_transliterate">Transliterasi &amp; Terjemahkan Suku Kata</string>
    <string name="ai_transliterating">Membuat Romaji dan terjemahan...</string>
    <string name="ai_transliteration_complete">Transliterasi suku kata siap</string>
    <string name="edit_syllables_title">Edit Suku Kata &amp; Terjemahan</string>
    <string name="edit_syllables_original">Aksara Asli</string>
    <string name="edit_syllables_romaji">Romaji / Transliterasi</string>
    <string name="edit_syllables_translation">Arti Baris</string>
    <string name="btn_ai_regenerate">Buat Ulang dengan AI</string>
    <string name="toggle_romaji">Romaji</string>
```

- [ ] **Step 2: Create layouts `item_edit_syllable_chip.xml` and `dialog_edit_syllables.xml`**

`item_edit_syllable_chip.xml`: Vertical chip with `TextView` for original syllable (`word.text`) above `EditText` for `word.romanji`.
`dialog_edit_syllables.xml`: Material dialog with title, original full line, horizontal scroll of syllable chips, translation `EditText`, AI Regenerate button, Cancel, and Save.

- [ ] **Step 3: Connect UI in `MainActivity.java` and `FloatingOverlayService.java`**

1. In `onNowPlayingChanged(NowPlaying track)`:
   - Check if lyrics contain non-Latin script via `ScriptDetector.isNonLatin(firstFewLines)`.
   - If non-Latin, add the Quick Suggestion Chip: `R.string.ai_chip_transliterate`.
   - Tapping it triggers `AiSyllableTransliterationService.transliterateSyllablesAsync`.
2. On long press on a line:
   - Inflate `dialog_edit_syllables.xml`.
   - Populate per-syllable inputs.
   - On Save: update `line.words[i].romanji` and `line.translation`, notify highlighter, and save to `LyricsCacheManager`.
3. In `FloatingOverlayService.java`:
   - Add Romaji toggle button to toggle `syllableHighlighter.setTransliterationEnabled(...)`.

- [ ] **Step 4: Verify test suite and compilation**

```bash
java -cp "$CP:$BIN_DIR" org.junit.runner.JUnitCore \
  com.lyricsync.app.ai.transliteration.ScriptDetectorTest \
  com.lyricsync.app.ai.transliteration.AiSyllableTransliterationServiceTest \
  com.lyricsync.app.renderer.SyllableHighlighterLayerTest
```
Expected: All tests pass 100%.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/dialog_edit_syllables.xml \
        app/src/main/res/layout/item_edit_syllable_chip.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-in/strings.xml \
        app/src/main/java/com/lyricsync/app/MainActivity.java \
        app/src/main/java/com/lyricsync/app/overlay/FloatingOverlayService.java
git commit -m "feat(ui): add interactive syllable editor dialog and Romaji quick toggles"
```
