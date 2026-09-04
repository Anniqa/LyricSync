# Lyrics Translation (Spotify-Style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [X]`) syntax for tracking.

**Goal:** Add on-demand, real-time Google Translate lyrics translation (Spotify-style) to LyricSync with zero API key configuration, smooth 120Hz scroll rendering, and language-aware caching.

**Architecture:** A lightweight batch-translator service (`GoogleTranslateService`) sends song lyrics in a single HTTP POST request to Google's public translation endpoint (`dict-chrome-ex`), extracts multi-sentence chunks safely into line translations via `GoogleTranslateParser`, caches results into SQLite with target language tagging, and renders secondary subtitles in `SyllableHighlighter` with matching typography and alpha transitions.

**Tech Stack:** Java 11, Android SDK (API 26+), OkHttp 4.12.0, Gson 2.13.1, JUnit / JVM Self-Tests.

**Spec:** docs/superpowers/specs/2026-09-04-lyrics-translation-design.md

## Global Constraints

- HTTP requests to Google Translate MUST use HTTP POST with `FormBody` (`application/x-www-form-urlencoded`), client `dict-chrome-ex`, and a valid mobile User-Agent.
- Translation parsing MUST join all sentence chunks before splitting by `\n` to prevent sentence-splitting desynchronization.
- Subtitle views in `SyllableHighlighter` MUST have static layout dimensions (no dynamic `GONE`<->`VISIBLE` toggling on line change) to prevent layout thrashing and scrolling jitter.
- Cache invalidation MUST be language-aware via `LyricsData.translationLang`.
- UI updates and callback listeners MUST dispatch to `Looper.getMainLooper()`.

---

### Task 1: Data Model (`LyricsData.java`) & Language-Aware Cache Delegation

**Files:**
- Modify: `app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java`
- Modify: `app/src/main/java/com/lyricsync/app/lyrics/LyricsProviderManager.java`
- Test: `app/src/test/java/com/lyricsync/app/lyrics/LyricsDataTranslationSelfTest.java`

**Interfaces:**
- Consumes: `LyricsData`, `LyricsLine`, `TrackInfo`, `LyricsCacheManager`
- Produces: `LyricsData.translationLang`, `LyricsLine.translation`, `LyricsProviderManager.updateCachedLyrics(TrackInfo, LyricsData)`

- [X] **Step 1: Write the failing JVM self-test**

Create `app/src/test/java/com/lyricsync/app/lyrics/LyricsDataTranslationSelfTest.java`:
```java
package com.lyricsync.app.lyrics;

import com.google.gson.Gson;
import com.lyricsync.app.lyrics.model.LyricsData;

public class LyricsDataTranslationSelfTest {

    public static void main(String[] args) {
        LyricsData data = new LyricsData();
        data.type = LyricsData.Type.LINE;
        data.translationLang = "id";

        LyricsData.LyricsLine line1 = new LyricsData.LyricsLine(0, 5000, "Hello world");
        line1.translation = "Halo dunia";
        data.lines.add(line1);

        LyricsData.LyricsLine line2 = new LyricsData.LyricsLine(5000, 10000, "Goodbye world");
        line2.translation = "Selamat tinggal dunia";
        data.lines.add(line2);

        check("id".equals(data.translationLang), "translationLang stored on LyricsData");
        check("Halo dunia".equals(data.lines.get(0).translation), "translation stored on line 0");
        check("Selamat tinggal dunia".equals(data.lines.get(1).translation), "translation stored on line 1");

        // Gson round-trip test
        Gson gson = new Gson();
        String json = gson.toJson(data);
        LyricsData restored = gson.fromJson(json, LyricsData.class);

        check("id".equals(restored.translationLang), "restored translationLang matches");
        check(restored.lines.size() == 2, "restored lines count");
        check("Halo dunia".equals(restored.lines.get(0).translation), "restored line 0 translation");
        check("Selamat tinggal dunia".equals(restored.lines.get(1).translation), "restored line 1 translation");

        System.out.println("LyricsDataTranslationSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
```

- [X] **Step 2: Run test to verify it fails**

Run:
```bash
javac -cp "app/src/main/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  app/src/test/java/com/lyricsync/app/lyrics/LyricsDataTranslationSelfTest.java
```
Expected: FAIL compilation with "cannot find symbol: variable translationLang" and "cannot find symbol: variable translation".

- [X] **Step 3: Implement model changes and cache delegation**

Modify `app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java`:
Add `public String translationLang;` to `LyricsData`.
Add `public String translation;` to `LyricsData.LyricsLine`.

Modify `app/src/main/java/com/lyricsync/app/lyrics/LyricsProviderManager.java`:
Add method:
```java
public void updateCachedLyrics(TrackInfo track, LyricsData lyrics) {
    if (track == null || lyrics == null) return;
    executor.execute(() -> cacheManager.cache(track, lyrics));
}
```

- [X] **Step 4: Run test to verify it passes**

Run:
```bash
javac -cp "app/src/main/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  app/src/test/java/com/lyricsync/app/lyrics/LyricsDataTranslationSelfTest.java
java -cp "app/src/main/java:app/src/test/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  com.lyricsync.app.lyrics.LyricsDataTranslationSelfTest
```
Expected: PASS with output `LyricsDataTranslationSelfTest OK`.

- [X] **Step 5: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java \
        app/src/main/java/com/lyricsync/app/lyrics/LyricsProviderManager.java \
        app/src/test/java/com/lyricsync/app/lyrics/LyricsDataTranslationSelfTest.java
git commit -m "feat(lyrics): add translation fields and cache update delegation"
```

---

### Task 2: Robust Google Translate Response Parser (`GoogleTranslateParser.java`)

**Files:**
- Create: `app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParser.java`
- Test: `app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParserSelfTest.java`

**Interfaces:**
- Consumes: JSON string from Google Translate response, `List<LyricsData.LyricsLine>`
- Produces: `GoogleTranslateParser.ParseResult`, updates `line.translation` safely

- [X] **Step 1: Write the failing JVM self-test**

Create `app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParserSelfTest.java`:
```java
package com.lyricsync.app.lyrics.translation;

import com.lyricsync.app.lyrics.model.LyricsData;
import java.util.ArrayList;
import java.util.List;

public class GoogleTranslateParserSelfTest {

    public static void main(String[] args) {
        testSentenceSplitMapping();
        testCJKMultiLineMergedChunk();
        testInterludeSkipping();
        testSameLanguageDetection();
        System.out.println("GoogleTranslateParserSelfTest OK");
    }

    private static void testSentenceSplitMapping() {
        // Line 0 had punctuation and was split into 2 chunks by Google
        String mockJson = "[[[\"Halo! \",\"Hello! \",null,null,3],[\"Apa kabarmu?\\n\",\"How are you?\\n\",null,null,3],"
                + "[\"Saya baik-baik saja.\\n\",\"I am fine.\\n\",null,null,3],"
                + "[\"Terima kasih.\",\"Thank you.\",null,null,3]],null,\"en\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 3000, "Hello! How are you?"));
        lines.add(new LyricsData.LyricsLine(3000, 6000, "I am fine."));
        lines.add(new LyricsData.LyricsLine(6000, 9000, "Thank you."));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        check("en".equals(res.detectedSourceLang), "detected en");
        check(!res.isSameLanguage, "not same language");
        check("Halo! Apa kabarmu?".equals(lines.get(0).translation), "line 0 reconstructed properly across chunks");
        check("Saya baik-baik saja.".equals(lines.get(1).translation), "line 1 translation");
        check("Terima kasih.".equals(lines.get(2).translation), "line 2 translation");
    }

    private static void testCJKMultiLineMergedChunk() {
        // Korean lyrics returned as 1 single segment containing newlines
        String mockJson = "[[[\"aku merindukanmu\\nSekarang aku ingin melihatmu\\nBahkan fotomu\\naku merindukanmu\",\"...\",null,null,3]],null,\"ko\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "line 1"));
        lines.add(new LyricsData.LyricsLine(2000, 4000, "line 2"));
        lines.add(new LyricsData.LyricsLine(4000, 6000, "line 3"));
        lines.add(new LyricsData.LyricsLine(6000, 8000, "line 4"));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        check("ko".equals(res.detectedSourceLang), "detected ko");
        check(lines.size() == 4, "4 lines");
        check("aku merindukanmu".equals(lines.get(0).translation), "korean line 0");
        check("Sekarang aku ingin melihatmu".equals(lines.get(1).translation), "korean line 1");
        check("Bahkan fotomu".equals(lines.get(2).translation), "korean line 2");
        check("aku merindukanmu".equals(lines.get(3).translation), "korean line 3");
    }

    private static void testInterludeSkipping() {
        String mockJson = "[[[\"Baris satu\\n\",\"Line one\\n\",null,null,3],[\"Baris dua\",\"Line two\",null,null,3]],null,\"en\"]";

        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Line one"));
        LyricsData.LyricsLine interlude = new LyricsData.LyricsLine(2000, 5000, "");
        interlude.isInterlude = true;
        lines.add(interlude);
        lines.add(new LyricsData.LyricsLine(5000, 8000, "Line two"));

        // Parser only receives translatable lines
        List<LyricsData.LyricsLine> translatable = new ArrayList<>();
        for (LyricsData.LyricsLine l : lines) {
            if (!l.isInterlude) translatable.add(l);
        }

        GoogleTranslateParser.parse(mockJson, translatable, "id");
        check("Baris satu".equals(lines.get(0).translation), "translatable line 0");
        check(lines.get(1).translation == null, "interlude remains null");
        check("Baris dua".equals(lines.get(2).translation), "translatable line 2");
    }

    private static void testSameLanguageDetection() {
        String mockJson = "[[[\"Halo dunia\",\"Halo dunia\",null,null,3]],null,\"id\"]";
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Halo dunia"));

        GoogleTranslateParser.ParseResult res = GoogleTranslateParser.parse(mockJson, lines, "id");
        check("id".equals(res.detectedSourceLang), "detected id");
        check(res.isSameLanguage, "flagged as same language");
        check(lines.get(0).translation == null, "translation skipped when same language");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
```

- [X] **Step 2: Run test to verify it fails**

Run:
```bash
javac -cp "app/src/main/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParserSelfTest.java
```
Expected: FAIL compilation with "cannot find symbol: class GoogleTranslateParser".

- [X] **Step 3: Implement `GoogleTranslateParser.java`**

Create `app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParser.java`:
```java
package com.lyricsync.app.lyrics.translation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.lyricsync.app.lyrics.model.LyricsData;

import java.util.List;

public final class GoogleTranslateParser {

    public static class ParseResult {
        public String detectedSourceLang = "auto";
        public boolean isSameLanguage = false;
        public int translatedLineCount = 0;
    }

    private GoogleTranslateParser() {}

    public static ParseResult parse(String jsonResponse,
                                    List<LyricsData.LyricsLine> translatableLines,
                                    String targetLang) {
        ParseResult result = new ParseResult();
        if (jsonResponse == null || jsonResponse.trim().isEmpty() || translatableLines == null) {
            return result;
        }

        JsonElement rootElement = JsonParser.parseString(jsonResponse);
        if (!rootElement.isJsonArray()) return result;
        JsonArray rootArray = rootElement.getAsJsonArray();

        // 1. Detect source language (index 2)
        if (rootArray.size() > 2 && !rootArray.get(2).isJsonNull()) {
            result.detectedSourceLang = rootArray.get(2).getAsString();
        }

        if (targetLang != null && !targetLang.isEmpty()
                && !targetLang.equalsIgnoreCase("auto")
                && targetLang.equalsIgnoreCase(result.detectedSourceLang)) {
            result.isSameLanguage = true;
            return result;
        }

        // 2. Concatenate all sentence segments (index 0)
        if (rootArray.size() == 0 || !rootArray.get(0).isJsonArray()) return result;
        JsonArray segments = rootArray.get(0).getAsJsonArray();

        StringBuilder fullText = new StringBuilder();
        for (JsonElement segElem : segments) {
            if (segElem.isJsonArray()) {
                JsonArray seg = segElem.getAsJsonArray();
                if (seg.size() > 0 && !seg.get(0).isJsonNull()) {
                    fullText.append(seg.get(0).getAsString());
                }
            }
        }

        // 3. Split by newline to match original lines
        String[] splitLines = fullText.toString().split("\n", -1);

        // 4. Map sequentially to translatable lines
        int count = Math.min(translatableLines.size(), splitLines.length);
        for (int i = 0; i < count; i++) {
            String translated = splitLines[i].trim();
            translatableLines.get(i).translation = translated.isEmpty() ? null : translated;
        }
        result.translatedLineCount = count;
        return result;
    }
}
```

- [X] **Step 4: Run test to verify it passes**

Run:
```bash
javac -cp "app/src/main/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParser.java \
  app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParserSelfTest.java
java -cp "app/src/main/java:app/src/test/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  com.lyricsync.app.lyrics.translation.GoogleTranslateParserSelfTest
```
Expected: PASS with output `GoogleTranslateParserSelfTest OK`.

- [X] **Step 5: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParser.java \
        app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateParserSelfTest.java
git commit -m "feat(lyrics): add robust Google Translate response parser"
```

---

### Task 3: Translation Network Service (`GoogleTranslateService.java`)

**Files:**
- Create: `app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateService.java`
- Test: `app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateServiceSelfTest.java`

**Interfaces:**
- Consumes: OkHttp `OkHttpClient`, `GoogleTranslateParser`, `LyricsData.LyricsLine`
- Produces: `GoogleTranslateService.translateLines()`, `cancel()`, preference keys

- [X] **Step 1: Write the failing JVM self-test**

Create `app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateServiceSelfTest.java`:
```java
package com.lyricsync.app.lyrics.translation;

import com.lyricsync.app.lyrics.model.LyricsData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GoogleTranslateServiceSelfTest {

    public static void main(String[] args) {
        // Test query text building with interlude and empty lines filtering
        List<LyricsData.LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsData.LyricsLine(0, 2000, "Line 1"));
        LyricsData.LyricsLine interlude = new LyricsData.LyricsLine(2000, 4000, "");
        interlude.isInterlude = true;
        lines.add(interlude);
        lines.add(new LyricsData.LyricsLine(4000, 6000, "   "));
        lines.add(new LyricsData.LyricsLine(6000, 8000, "Line 2\nwith newline"));

        List<LyricsData.LyricsLine> translatable = GoogleTranslateService.filterTranslatableLines(lines);
        check(translatable.size() == 2, "filtered translatable count");
        check("Line 1".equals(translatable.get(0).text), "line 1 text");
        check("Line 2\nwith newline".equals(translatable.get(1).text), "line 2 text");

        String query = GoogleTranslateService.buildQueryText(translatable);
        check("Line 1\nLine 2 with newline\n".equals(query), "query text normalized newlines");

        // Test language resolution
        check("en".equals(GoogleTranslateService.resolveTargetLang("en", Locale.FRENCH)), "explicit en");
        check("fr".equals(GoogleTranslateService.resolveTargetLang("auto", Locale.FRENCH)), "auto resolved to system");
        check("id".equals(GoogleTranslateService.resolveTargetLang(null, new Locale("id"))), "null resolved to system");

        System.out.println("GoogleTranslateServiceSelfTest OK");
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
    }
}
```

- [X] **Step 2: Run test to verify it fails**

Run:
```bash
javac -cp "app/src/main/java" \
  app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateServiceSelfTest.java
```
Expected: FAIL compilation with "cannot find symbol: class GoogleTranslateService".

- [X] **Step 3: Implement `GoogleTranslateService.java`**

Create `app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateService.java`:
```java
package com.lyricsync.app.lyrics.translation;

import android.os.Handler;
import android.os.Looper;

import com.lyricsync.app.lyrics.model.LyricsData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GoogleTranslateService {
    public static final String PREF_TRANSLATION_ENABLED = "pref_translation_enabled";
    public static final String PREF_TARGET_LANG = "pref_translation_target_lang";
    public static final String DEFAULT_TARGET_LANG = "auto";

    private static final String API_URL = "https://translate.googleapis.com/translate_a/single";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient client;
    private final Handler mainHandler;
    private Call activeCall;

    public interface TranslationCallback {
        void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang);
        void onError(Exception error);
    }

    public GoogleTranslateService() {
        this(new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
                new Handler(Looper.getMainLooper()));
    }

    public GoogleTranslateService(OkHttpClient client, Handler mainHandler) {
        this.client = client;
        this.mainHandler = mainHandler;
    }

    public static List<LyricsData.LyricsLine> filterTranslatableLines(List<LyricsData.LyricsLine> allLines) {
        List<LyricsData.LyricsLine> result = new ArrayList<>();
        if (allLines == null) return result;
        for (LyricsData.LyricsLine line : allLines) {
            if (!line.isInterlude && line.text != null && !line.text.trim().isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    public static String buildQueryText(List<LyricsData.LyricsLine> translatableLines) {
        StringBuilder sb = new StringBuilder();
        for (LyricsData.LyricsLine line : translatableLines) {
            String sanitized = line.text.replace("\r", "").replace("\n", " ").trim();
            sb.append(sanitized).append("\n");
        }
        return sb.toString();
    }

    public static String resolveTargetLang(String prefLang, Locale systemLocale) {
        if (prefLang != null && !prefLang.isEmpty() && !DEFAULT_TARGET_LANG.equalsIgnoreCase(prefLang)) {
            return prefLang.trim().toLowerCase(Locale.ROOT);
        }
        if (systemLocale != null && systemLocale.getLanguage() != null && !systemLocale.getLanguage().isEmpty()) {
            return systemLocale.getLanguage().toLowerCase(Locale.ROOT);
        }
        return "en";
    }

    public synchronized void cancel() {
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    public synchronized void translateLines(List<LyricsData.LyricsLine> allLines,
                                           String targetLang,
                                           TranslationCallback callback) {
        cancel();

        List<LyricsData.LyricsLine> translatable = filterTranslatableLines(allLines);
        if (translatable.isEmpty()) {
            dispatchSuccess(new GoogleTranslateParser.ParseResult(), targetLang, callback);
            return;
        }

        String finalTargetLang = resolveTargetLang(targetLang, Locale.getDefault());
        String queryText = buildQueryText(translatable);

        RequestBody formBody = new FormBody.Builder()
                .add("client", "dict-chrome-ex")
                .add("sl", "auto")
                .add("tl", finalTargetLang)
                .add("dt", "t")
                .add("q", queryText)
                .build();

        Request request = new Request.Builder()
                .url(API_URL)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build();

        activeCall = client.newCall(request);
        activeCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                dispatchError(e, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (call.isCanceled()) return;
                if (!response.isSuccessful() || response.body() == null) {
                    dispatchError(new IOException("Google Translate error: HTTP " + response.code()), callback);
                    return;
                }

                try {
                    String json = response.body().string();
                    GoogleTranslateParser.ParseResult parseResult =
                            GoogleTranslateParser.parse(json, translatable, finalTargetLang);
                    dispatchSuccess(parseResult, finalTargetLang, callback);
                } catch (Exception ex) {
                    dispatchError(ex, callback);
                }
            }
        });
    }

    private void dispatchSuccess(GoogleTranslateParser.ParseResult result,
                                String targetLang,
                                TranslationCallback callback) {
        if (mainHandler == null) {
            callback.onSuccess(result, targetLang);
        } else {
            mainHandler.post(() -> callback.onSuccess(result, targetLang));
        }
    }

    private void dispatchError(Exception error, TranslationCallback callback) {
        if (mainHandler == null) {
            callback.onError(error);
        } else {
            mainHandler.post(() -> callback.onError(error));
        }
    }
}
```

- [X] **Step 4: Run test to verify it passes**

Run:
```bash
javac -cp "app/src/main/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp -name '*.jar' 2>/dev/null | head -n 1):$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateService.java \
  app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateServiceSelfTest.java
java -cp "app/src/main/java:app/src/test/java" \
  com.lyricsync.app.lyrics.translation.GoogleTranslateServiceSelfTest
```
Expected: PASS with output `GoogleTranslateServiceSelfTest OK`.

- [X] **Step 5: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/lyrics/translation/GoogleTranslateService.java \
        app/src/test/java/com/lyricsync/app/lyrics/translation/GoogleTranslateServiceSelfTest.java
git commit -m "feat(lyrics): add GoogleTranslateService with OkHttp POST and cancelation"
```

---

### Task 4: Subtitle Typography & Rendering in `SyllableHighlighter.java`

**Files:**
- Modify: `app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java:40-115,190-240,390-480`

**Interfaces:**
- Consumes: `LineView`, `LyricsLine.translation`
- Produces: `LineView.translationView`, `SyllableHighlighter.setTranslationEnabled(boolean)`, `updateTranslationText(int, String)`

- [X] **Step 1: Inspect `LineView` fields and add `translationView`**

In `app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java`:
Inside `public static class LineView`:
Add:
```java
public TextView translationView;
```

- [X] **Step 2: Add translation view creation in `createPerWordView` and `createPerLineView`**

In `createPerWordView`:
Right before returning `container`:
```java
TextView tv = createTranslationTextView(line);
if (tv != null) {
    container.addView(tv);
    lv.translationView = tv;
}
```

In `createPerLineView`:
Right before returning `container`:
```java
TextView tv = createTranslationTextView(line);
if (tv != null) {
    container.addView(tv);
    lv.translationView = tv;
}
```

Add helper method:
```java
private boolean translationEnabled = false;

public void setTranslationEnabled(boolean enabled) {
    this.translationEnabled = enabled;
    for (LineView lv : lineViews) {
        if (lv.translationView != null) {
            lv.translationView.setVisibility(enabled && lv.line != null && lv.line.translation != null
                    ? View.VISIBLE : View.GONE);
        }
    }
}

public void updateLineTranslation(int index, String translation) {
    if (index < 0 || index >= lineViews.size()) return;
    LineView lv = lineViews.get(index);
    if (lv.translationView != null) {
        lv.translationView.setText(translation != null ? translation : "");
        lv.translationView.setVisibility(translationEnabled && translation != null ? View.VISIBLE : View.GONE);
    }
}

private TextView createTranslationTextView(LyricsData.LyricsLine line) {
    TextView tv = new TextView(context);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 0.72f);
    tv.setTypeface(fontMedium);
    tv.setTextColor(INACTIVE_COLOR);
    tv.setLineSpacing(0, 1.15f);
    tv.setPadding(0, dpToPx(2), 0, dpToPx(2));
    if (line.translation != null) {
        tv.setText(line.translation);
    }
    tv.setVisibility(translationEnabled && line.translation != null ? View.VISIBLE : View.GONE);
    return tv;
}
```

- [X] **Step 3: Update subtitle styling on line state transitions**

In `updatePerWordHighlight` and `updatePerLineHighlight`:
When `stateChanged`:
- On `isActive`:
  ```java
  if (lv.translationView != null) {
      lv.translationView.setTextColor(ACTIVE_COLOR);
  }
  ```
- On `isPast`:
  ```java
  if (lv.translationView != null) {
      lv.translationView.setTextColor(PAST_COLOR);
  }
  ```
- On inactive/upcoming:
  ```java
  if (lv.translationView != null) {
      lv.translationView.setTextColor(INACTIVE_COLOR);
  }
  ```

- [X] **Step 4: Verify compilation and tests**

Run:
```bash
./gradlew compileDebugJavaWithJavac
```
Expected: BUILD SUCCESSFUL with 0 errors.

- [X] **Step 5: Commit**

```bash
git add app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java
git commit -m "feat(renderer): add subtitle translation rendering with matching alpha states"
```

---

### Task 5: Overlay Controls & Service Integration (`FloatingOverlayService.java` & Layout)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/overlay_lyrics.xml`
- Modify: `app/src/main/java/com/lyricsync/app/overlay/FloatingOverlayService.java`

**Interfaces:**
- Consumes: `GoogleTranslateService`, `SyllableHighlighter`, `LyricsProviderManager.updateCachedLyrics`
- Produces: Toggleable translation switch in overlay settings panel and background translation lifecycle

- [X] **Step 1: Add translation strings**

In `app/src/main/res/values/strings.xml`:
Add:
```xml
    <!-- Translation -->
    <string name="lyrics_translation_title">Terjemahan Lirik</string>
    <string name="lyrics_translation_desc">Tampilkan terjemahan subtitle di bawah lirik</string>
    <string name="lyrics_translation_target">Bahasa Target</string>
```

- [X] **Step 2: Add translation switch row in `overlay_lyrics.xml`**

In `app/src/main/res/layout/overlay_lyrics.xml`:
Inside `overlay_settings_panel`, right above `<View android:layout_height="1dp" android:background="@color/divider" ...>`:
Add:
```xml
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingTop="4dp"
            android:paddingBottom="8dp">

            <ImageView
                android:layout_width="20dp"
                android:layout_height="20dp"
                android:src="@drawable/ic_music_note"
                app:tint="@color/accent"
                android:contentDescription="@null" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="8dp"
                android:text="@string/lyrics_translation_title"
                android:textColor="@color/text_primary"
                android:textSize="13sp"
                android:textStyle="bold" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/overlay_translate_switch"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />
        </LinearLayout>
```

- [X] **Step 3: Wire translation in `FloatingOverlayService.java`**

In `FloatingOverlayService.java`:
1. Add field:
```java
private GoogleTranslateService translateService;
private com.google.android.material.materialswitch.MaterialSwitch overlayTranslateSwitch;
private boolean translationEnabled = false;
```
2. In `onCreate()`:
```java
translateService = new GoogleTranslateService();
translationEnabled = sharedPrefs.getBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, false);
```
3. In `initViews()` / settings panel binding:
```java
overlayTranslateSwitch = overlayView.findViewById(R.id.overlay_translate_switch);
if (overlayTranslateSwitch != null) {
    overlayTranslateSwitch.setChecked(translationEnabled);
    overlayTranslateSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
        translationEnabled = isChecked;
        sharedPrefs.edit().putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, isChecked).apply();
        if (highlighter != null) {
            highlighter.setTranslationEnabled(isChecked);
        }
        if (isChecked && currentLyrics != null && currentLyrics.translationLang == null) {
            fetchTranslationForCurrentLyrics();
        }
    });
}
```
4. Add method `fetchTranslationForCurrentLyrics()`:
```java
private void fetchTranslationForCurrentLyrics() {
    if (currentLyrics == null || currentLyrics.isEmpty() || translateService == null) return;
    String targetLang = sharedPrefs.getString(GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG);

    final String fetchKey = pendingFetchKey;
    translateService.translateLines(currentLyrics.lines, targetLang, new GoogleTranslateService.TranslationCallback() {
        @Override
        public void onSuccess(GoogleTranslateParser.ParseResult result, String resolvedLang) {
            if (isDestroyed || !fetchKey.equals(pendingFetchKey)) return;
            if (result.isSameLanguage) {
                AppLog.i(TAG, "Song is already in target language (" + result.detectedSourceLang + ")");
                return;
            }
            currentLyrics.translationLang = resolvedLang;
            if (highlighter != null) {
                for (int i = 0; i < currentLyrics.lines.size(); i++) {
                    highlighter.updateLineTranslation(i, currentLyrics.lines.get(i).translation);
                }
                highlighter.setTranslationEnabled(translationEnabled);
            }
            if (lyricsManager != null && currentTrack != null) {
                lyricsManager.updateCachedLyrics(currentTrack, currentLyrics);
            }
        }

        @Override
        public void onError(Exception error) {
            AppLog.w(TAG, "Translation error: " + error.getMessage());
        }
    });
}
```
5. In `renderOverlayLyrics(LyricsData lyrics)`:
```java
highlighter.setTranslationEnabled(translationEnabled);
if (translationEnabled && lyrics.translationLang == null) {
    fetchTranslationForCurrentLyrics();
}
```
6. In `onDestroy()`:
```java
if (translateService != null) {
    translateService.cancel();
}
```

- [X] **Step 4: Verify compilation**

Run:
```bash
./gradlew compileDebugJavaWithJavac
```
Expected: BUILD SUCCESSFUL.

- [X] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/layout/overlay_lyrics.xml \
        app/src/main/java/com/lyricsync/app/overlay/FloatingOverlayService.java
git commit -m "feat(overlay): integrate translation switch and background fetch lifecycle"
```

---

### Task 6: Main Activity Settings Card (`MainActivity.java` & Layout)

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/lyricsync/app/MainActivity.java`

**Interfaces:**
- Consumes: `GoogleTranslateService.PREF_TRANSLATION_ENABLED`, `PREF_TARGET_LANG`
- Produces: Persistent translation master switch and target language chooser in main app

- [X] **Step 1: Add Translation Settings Card in `activity_main.xml`**

In `app/src/main/res/layout/activity_main.xml`:
Under the Appearance or Sync card, add:
```xml
            <!-- ── Translation Card ───────────────────────────────────── -->
            <com.google.android.material.card.MaterialCardView
                android:id="@+id/card_translation"
                style="@style/Widget.LyricSync.Card"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="16dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="18dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:gravity="center_vertical">

                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:orientation="vertical">

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/lyrics_translation_title"
                                android:textColor="@color/text_primary"
                                android:textSize="16sp"
                                android:textStyle="bold" />

                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/lyrics_translation_desc"
                                android:textColor="@color/text_secondary"
                                android:textSize="12sp"
                                android:layout_marginTop="2dp" />
                        </LinearLayout>

                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/switch_main_translation"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                    </LinearLayout>

                    <LinearLayout
                        android:id="@+id/layout_target_lang"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:gravity="center_vertical"
                        android:layout_marginTop="12dp">

                        <TextView
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="@string/lyrics_translation_target"
                            android:textColor="@color/text_secondary"
                            android:textSize="14sp" />

                        <Spinner
                            android:id="@+id/spinner_target_lang"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:popupBackground="@color/card_background" />
                    </LinearLayout>
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
```

- [X] **Step 2: Wire Translation Card in `MainActivity.java`**

In `app/src/main/java/com/lyricsync/app/MainActivity.java`:
Inside `initViews()`:
```java
com.google.android.material.materialswitch.MaterialSwitch switchTrans = findViewById(R.id.switch_main_translation);
Spinner spinnerLang = findViewById(R.id.spinner_target_lang);

String[] langNames = {"Otomatis (Sistem)", "Bahasa Indonesia", "English", "日本語 (Japanese)", "한국어 (Korean)", "Español", "简体中文", "Français", "Deutsch"};
String[] langCodes = {"auto", "id", "en", "ja", "ko", "es", "zh-CN", "fr", "de"};

ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, langNames);
adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
spinnerLang.setAdapter(adapter);

String currentLang = prefs.getString(GoogleTranslateService.PREF_TARGET_LANG, GoogleTranslateService.DEFAULT_TARGET_LANG);
for (int i = 0; i < langCodes.length; i++) {
    if (langCodes[i].equalsIgnoreCase(currentLang)) {
        spinnerLang.setSelection(i);
        break;
    }
}

switchTrans.setChecked(prefs.getBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, false));
switchTrans.setOnCheckedChangeListener((btn, isChecked) -> {
    prefs.edit().putBoolean(GoogleTranslateService.PREF_TRANSLATION_ENABLED, isChecked).apply();
});

spinnerLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        prefs.edit().putString(GoogleTranslateService.PREF_TARGET_LANG, langCodes[position]).apply();
    }
    @Override
    public void onNothingSelected(AdapterView<?> parent) {}
});
```

- [X] **Step 3: Verify compilation**

Run:
```bash
./gradlew compileDebugJavaWithJavac
```
Expected: BUILD SUCCESSFUL.

- [X] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml \
        app/src/main/java/com/lyricsync/app/MainActivity.java
git commit -m "feat(settings): add lyrics translation card and target language picker"
```

---

### Task 7: Comprehensive Verification & Self-Test Suite

**Files:**
- All tests in `app/src/test/java/com/lyricsync/app/`

- [X] **Step 1: Execute all JVM self-tests**

Run:
```bash
./gradlew test
```
Or run each self-test suite directly:
```bash
java -cp "app/src/main/java:app/src/test/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  com.lyricsync.app.lyrics.LyricsDataTranslationSelfTest
java -cp "app/src/main/java:app/src/test/java:$(find ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson -name '*.jar' 2>/dev/null | head -n 1)" \
  com.lyricsync.app.lyrics.translation.GoogleTranslateParserSelfTest
java -cp "app/src/main/java:app/src/test/java" \
  com.lyricsync.app.lyrics.translation.GoogleTranslateServiceSelfTest
java -cp "app/src/main/java:app/src/test/java" \
  com.lyricsync.app.ui.AnimSelectionSelfTest
java -cp "app/src/main/java:app/src/test/java" \
  com.lyricsync.app.renderer.AnimConfigSelfTest
```
Expected: All tests print `... OK` and exit with code 0.

- [X] **Step 2: Run full project build**

Run:
```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.
