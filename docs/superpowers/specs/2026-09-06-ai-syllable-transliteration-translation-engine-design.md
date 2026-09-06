# Design Specification: AI Syllable Transliteration & Translation Engine (3-Layer Synchronized Lyrics)

**Author**: Antigravity AI Assistant & Anniqa  
**Date**: 2026-09-06  
**Status**: In Review (Superpowers Architectural Spec)  
**Target Repository**: `LyricSync-private` (`com.lyricsync.app`)

---

## 1. Overview & Problem Statement

Music listeners frequently listen to international songs written in non-Latin scripts (e.g. Japanese Kanji/Kana, Chinese Hanzi, Korean Hangul, Russian Cyrillic, Arabic, Greek, Thai). While listeners enjoy the rhythm and melody, they face two key obstacles:
1. **Inability to Read or Sing Along**: Without knowing the script phonetics, users cannot pronounce or sing the lyrics.
2. **Lack of Meaning Understanding**: Users do not know the emotional or literal meaning of the lyrics.

Existing music players often provide static full-line romanization (if available), but they do not provide **syllable-level synchronization**. With the arrival of syllable-timed lyrics (`LyricsData.Type.SYLLABLE`) from providers like SpicyLyrics, TTML, and LRCLIB, each word or syllable already possesses precise millisecond start and end timestamps (`startTime`, `endTime`).

This specification defines an **AI-driven, 3-Layer Synchronized Lyrics Engine**:
- **Layer 1 (Original Script)**: Original characters (e.g., Japanese Kanji/Kana, Chinese Hanzi) highlighted beat-by-beat via `WordFlowLayout`.
- **Layer 2 (Phonetic Transliteration)**: Hepburn Romaji, Pinyin, Revised Romanization, or Latin transliteration mapped 1:1 to syllables, glowing and scaling simultaneously on the exact same vocal beat.
- **Layer 3 (Meaning Translation)**: Natural language translation (Indonesian or English) displayed in a distinct soft typography underneath the syllable flow.
- **Interactive Editing**: Full capability to review, edit per-syllable transliterations, and update translations directly through AI chat and a dedicated syllable editor dialog.
- **Persistent Offline Caching**: Transliterated lyrics are cached permanently in SQLite via `LyricsCacheManager`, eliminating duplicate AI API calls.

---

## 2. Architecture & Data Flow

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 LyricSync Architecture                                 │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                        │
│   Track Changed (Non-Latin Song Detected via Unicode Block Check)                      │
│                                │                                                       │
│                                ▼                                                       │
│                   LyricsCacheManager.getCached()                                       │
│                    ├── Hit (hasTransliteration == true) ───► Instant 3-Layer Render    │
│                    └── Miss (or manual generate requested)                             │
│                                │                                                       │
│                                ▼                                                       │
│               AiSyllableTransliterationService                                         │
│               (Batching 10-15 lines per request)                                       │
│                                │                                                       │
│               Gemini 2.5 Flash (Structured JSON Schema)                                │
│               Input:  ["前", "前", "前", "世", "から", "僕", "は"]                    │
│               Output: ["zen", "zen", "zen", "se", "kara", "boku", "wa"]                │
│                                │                                                       │
│                                ▼                                                       │
│               Token Alignment & Reconciliation Engine                                  │
│               (1:1 token matching with robust heuristic fallback)                      │
│                                │                                                       │
│                                ▼                                                       │
│               LyricsData (Word.romanji + LyricsLine.romanji)                           │
│                                │                                                       │
│        ┌───────────────────────┴───────────────────────┐                               │
│        ▼                                               ▼                               │
│  LyricsCacheManager.cache()                  SyllableHighlighter                       │
│  (SQLite JSON persistence)                   (3-Layer Synchronized Renderer)           │
│                                              - Layer 1: Original Syllable Glow         │
│                                              - Layer 2: Romaji Syllable Glow (Synced)  │
│                                              - Layer 3: Translation Subtitle           │
│                                                                                        │
│                                ▼                                                       │
│                   Interactive Syllable Editor Dialog                                   │
│                   - Edit per-syllable transliteration token                            │
│                   - Edit line translation                                              │
│                   - Single-line AI regenerate                                          │
│                   - Live memory update + cache commit                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Data Model Extensions (`LyricsData.java` & Cache)

### 3.1 `LyricsData.java` Model Schema

In [`com.lyricsync.app.lyrics.model.LyricsData`](file:///data/data/com.termux/files/home/LyricSync/app/src/main/java/com/lyricsync/app/lyrics/model/LyricsData.java):

```java
public class LyricsData {
    public enum Type { SYLLABLE, LINE, STATIC }

    public Type type;
    public List<LyricsLine> lines = new ArrayList<>();
    public String provider;
    public String translationLang;

    // Transliteration metadata
    public boolean hasTransliteration;
    public String sourceScript; // "LATIN", "JAPANESE", "CHINESE", "KOREAN", "CYRILLIC", "OTHER"

    public static class LyricsLine {
        public long startTime;
        public long endTime;
        public String text;
        public String romanji; // Full-line transliteration (e.g. for line-mode or fallback)
        public String translation; // Line-level meaning translation
        public List<Word> words = new ArrayList<>();
        public boolean isInterlude;
        public List<LyricsLine> backgroundVocals;
        ...
    }

    public static class Word {
        public long startTime;
        public long endTime;
        public String text;
        public String romanji; // Syllable-level transliteration aligned to this exact time window
        ...
    }
}
```

### 3.2 Persistent Caching in `LyricsCacheManager`

[`LyricsCacheManager.java`](file:///data/data/com.termux/files/home/LyricSync/app/src/main/java/com/lyricsync/app/lyrics/cache/LyricsCacheManager.java) stores the serialized `LyricsData` object as a JSON string inside the SQLite table `lyrics` (`json_data` column).

- Because `Gson` serializes all fields dynamically, adding `romanji` to `Word` and `LyricsLine` requires **no database schema alterations** (`DB_VERSION` remains unchanged, no destructive table recreation).
- Once a track's syllable transliteration and translation are populated, `lyricsCacheManager.cache(track, lyricsData)` saves the enriched object.
- Subsequent playback loads the 3-layer data instantly and offline.

---

## 4. AI Transliteration & Alignment Engine (`AiSyllableTransliterationService`)

### 4.1 Unicode Script Detection

Prior to making any network call, the engine determines whether the track contains non-Latin scripts:

```java
public enum ScriptType {
    LATIN,
    JAPANESE,   // Hiragana \u3040-\u309F, Katakana \u30A0-\u30FF, CJK \u4E00-\u9FAF
    CHINESE,    // CJK \u4E00-\u9FFF (without Kana)
    KOREAN,     // Hangul Syllables \uAC00-\uD7AF, Jamo \u1100-\u11FF
    CYRILLIC,   // Russian/Slavic \u0400-\u04FF
    OTHER_NON_LATIN
}
```

If the entire lyric text consists solely of Latin characters and common punctuation, `ScriptType.LATIN` is detected:
- Layer 2 (Transliteration) is automatically bypassed to prevent redundant duplicate lines.
- Only translation (Layer 3) is processed if requested by the user.

### 4.2 Prompt Engineering & Structured JSON Output

To guarantee high phonetic precision and eliminate model hallucination or token count drift:
1. Lines are sent in batches of **10 to 15 lines** per API call.
2. The prompt explicitly feeds the exact array of segmented syllables from `line.words`.
3. Gemini is instructed using strict system constraints:

```text
You are an expert multilingual lyric transliteration and translation engine.
Target Language for translation: %s (id = Indonesian, en = English)

Rules:
1. Transliterate non-Latin text into phonetic Latin script:
   - Japanese: Hepburn Romaji reflecting contextual song pronunciation.
   - Chinese: Pinyin with tone marks or rhythmically natural.
   - Korean: Revised Romanization.
   - Russian/Cyrillic: Standard Latin transliteration.
2. For each line, provide an accurate, natural translation of the lyrical meaning.
3. CRITICAL CONSTRAINT: For "wordsRomanji", you MUST output a JSON array of strings whose length EXACTLY MATCHES the length of the input "syllables" array. Each index i in "wordsRomanji" must correspond phonetically to "syllables[i]".
```

**JSON Schema Request Payload:**
```json
{
  "lines": [
    {
      "index": 0,
      "text": "前前前世から僕は",
      "syllables": ["前", "前", "前", "世", "から", "僕", "は"]
    }
  ]
}
```

**Expected JSON Response:**
```json
{
  "lines": [
    {
      "index": 0,
      "romanji": "zenzenzense kara boku wa",
      "translation": "Bahkan sejak kehidupan masa laluku",
      "wordsRomanji": ["zen", "zen", "zen", "se", "kara", "boku", "wa"]
    }
  ]
}
```

### 4.3 Resilient Token Alignment Algorithm

Even with strict prompting, LLMs can occasionally return an array with `N ± 1` items. The system must guarantee **zero crashes** and preserve timing synchronization under all conditions:

```java
public static void alignSyllables(LyricsData.LyricsLine line, List<String> aiWordsRomanji, String fallbackRomanjiLine) {
    if (line.words == null || line.words.isEmpty()) return;

    int expectedCount = line.words.size();

    // Path A: Perfect 1:1 Match
    if (aiWordsRomanji != null && aiWordsRomanji.size() == expectedCount) {
        for (int i = 0; i < expectedCount; i++) {
            line.words.get(i).romanji = aiWordsRomanji.get(i).trim();
        }
        return;
    }

    // Path B: Fallback Alignment via Proportional Distribution or Whitespace Tokenizer
    List<String> tokens = new ArrayList<>();
    if (aiWordsRomanji != null && !aiWordsRomanji.isEmpty()) {
        tokens.addAll(aiWordsRomanji);
    } else if (fallbackRomanjiLine != null && !fallbackRomanjiLine.trim().isEmpty()) {
        String[] split = fallbackRomanjiLine.trim().split("\\s+");
        Collections.addAll(tokens, split);
    }

    if (tokens.isEmpty()) {
        for (LyricsData.Word w : line.words) {
            w.romanji = "";
        }
        return;
    }

    // Distribute tokens safely across expectedCount slots
    for (int i = 0; i < expectedCount; i++) {
        if (i < tokens.size()) {
            line.words.get(i).romanji = tokens.get(i);
        } else {
            line.words.get(i).romanji = "";
        }
    }
}
```

---

## 5. 3-Layer Rendering Engine in `SyllableHighlighter.java`

### 5.1 View Hierarchy per Lyric Line

In [`SyllableHighlighter.java`](file:///data/data/com.termux/files/home/LyricSync/app/src/main/java/com/lyricsync/app/renderer/SyllableHighlighter.java):

```java
public static class LineView {
    public View rootView; // Vertical LinearLayout
    public WordFlowLayout originalWordFlowLayout; // Layer 1
    public List<GradientWordView> originalWordViews;

    public WordFlowLayout romajiWordFlowLayout;   // Layer 2
    public List<GradientWordView> romajiWordViews;

    public TextView translationView;              // Layer 3
    ...
}
```

### 5.2 Simultaneous Beat Animation Synchronization

In `updateProgress(long currentTimeMs)`:
- Both `originalWordViews.get(i)` and `romajiWordViews.get(i)` receive the same progress calculations based on `word.startTime` and `word.endTime`.
- When a syllable is sung:
  - Original script word view animates (glow effect, scale pop, color fill from dim to bright white).
  - Romaji word view below animates **in exact synchronization** with matching duration, scale pop, and gradient fill.
- Visual hierarchy:
  - Layer 1 font size: `fontSizeSp` (Bold).
  - Layer 2 font size: `0.82f * fontSizeSp` (Medium / Semi-bold, readable Latin font).
  - Layer 3 font size: `0.72f * fontSizeSp` (Dim inactive color, italicized or medium subtitle style).

### 5.3 Dynamic Visibility Toggles

- `setTransliterationEnabled(boolean enabled)`: Toggles Layer 2 visibility.
- `setTranslationEnabled(boolean enabled)`: Toggles Layer 3 visibility.
- If the track's script is Latin, Layer 2 is hidden automatically.

---

## 6. Interactive UI & Editing Capabilities

### 6.1 Smart AI Suggestion Chips

In `MainActivity` and `FloatingOverlayService`:
- When a track with `ScriptType != LATIN` is loaded:
  - Add quick action chip: `"Transliterasi & Terjemahkan Suku Kata"` (English: `"Transliterate & Translate Syllables"`).
  - Tapping triggers `AiSyllableTransliterationService` with non-blocking progress display.
- Chat commands supported:
  - User can ask: `"Koreksi romaji baris ke-2 menjadi..."` or `"Perbaiki arti baris 4"`.
  - Chat manager applies updates and re-renders the highlighter.

### 6.2 Manual Syllable Editor Dialog (`dialog_edit_syllables.xml`)

Accessible via long-press on an active lyric line or "Edit Romaji / Terjemahan" button:
- Displays:
  - Original line text.
  - Horizontal chip list / flow layout of editable syllable pairs:
    `[ 原: gen ] [ 詞: shi ] [ か: ka ] [ ら: ra ]`
  - Editable `EditText` for line meaning translation.
  - Action buttons:
    - **"Perbaiki dengan AI"**: Invokes single-line Gemini transliteration.
    - **"Batal"**: Discards changes.
    - **"Simpan"**: Updates `LyricsData` in memory, refreshes `SyllableHighlighter`, and commits changes to `LyricsCacheManager`.

### 6.3 Quick Overlay Toggles

In the floating overlay menu:
- Toggle Button: **"Romaji"** (active/inactive state saved in `SharedPreferences`).
- Toggle Button: **"Terjemahan"** (active/inactive state saved in `SharedPreferences`).

---

## 7. Error Handling, Performance & Rate Limiting

1. **Quota & Rate-Limiting Protection**:
   - Songs are processed in chunks of 10-15 lines.
   - If HTTP 429 or 503 is encountered, the service gracefully pauses or informs the user without freezing the UI or playback.
2. **Offline-First**:
   - Once generated, lyrics are cached in SQLite. Subsequent song plays require 0 network calls.
3. **Threading & Cancellation**:
   - All network calls execute on background threads via `AntigravityApiClient.Cancellable`.
   - Switching songs immediately cancels any in-flight transliteration jobs.

---

## 8. Testing Strategy

1. **Unit Tests**:
   - `ScriptDetectorTest`: Verify Latin, Japanese (Hiragana/Katakana/Kanji), Chinese (Hanzi), Korean (Hangul), and Cyrillic classification.
   - `AiSyllableTransliterationServiceTest`:
     - Test prompt payload construction.
     - Test JSON response parsing for `wordsRomanji` and `translation`.
     - Test `alignSyllables`:
       - Perfect 1:1 match.
       - Token count mismatch (too few tokens).
       - Token count mismatch (too many tokens).
       - Empty response fallback.
   - `LyricsCacheManagerTest`: Verify `LyricsData` serialization with `Word.romanji` and `LyricsLine.romanji` without database errors.
2. **UI & Rendering Tests**:
   - Verify `SyllableHighlighter` creates both original and romaji views when transliteration is enabled.
   - Verify toggle visibility switching works without recreation glitches.
