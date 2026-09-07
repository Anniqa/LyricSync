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
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.cache.LyricsCacheManager;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiSyllableTransliterationService {
    private static final String TAG = "AiTranslitService";
    private static final int BATCH_SIZE = 12;
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private final Context context;
    private final AntigravityApiClient apiClient;
    private final LyricsCacheManager cacheManager;
    private final Handler mainHandler;
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
        this(context, apiClient, context != null ? new LyricsCacheManager(context) : null, createSafeMainHandler());
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient, LyricsCacheManager cacheManager) {
        this(context, apiClient, cacheManager, createSafeMainHandler());
    }

    public AiSyllableTransliterationService(Context context, AntigravityApiClient apiClient, LyricsCacheManager cacheManager, Handler mainHandler) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.apiClient = apiClient;
        this.cacheManager = cacheManager;
        this.mainHandler = mainHandler;
    }

    private static Handler createSafeMainHandler() {
        try {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null) {
                return new Handler(mainLooper);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final java.util.regex.Pattern MORA_PATTERN = java.util.regex.Pattern.compile(
        "(?i)" +
        "(?:(?:t?ch|s?sh|ts|[kscnhmrgzdbptfvwj]y)[aiueoāīūēō](?:n(?![aiueoāīūēōy]))?)|" +
        "(?:[bcdfghjklmnprstvwxyz][aiueoāīūēō](?:n(?![aiueoāīūēōy]))?)|" +
        "(?:[aiueoāīūēō](?:n(?![aiueoāīūēōy]))?)|" +
        "(?:n(?![aiueoāīūēōy]))|" +
        "(?:[bcdfghjklmnprstvwxyz](?=[bcdfghjklmnprstvwxyz]))|" +
        "(?:[a-zA-Z0-9]+)"
    );

    public static List<String> splitRomajiIntoMoras(String text) {
        List<String> moras = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return moras;
        java.util.regex.Matcher m = MORA_PATTERN.matcher(text.trim());
        while (m.find()) {
            String mora = m.group().trim();
            if (!mora.isEmpty()) {
                moras.add(mora);
            }
        }
        return moras;
    }

    private static class Anchor {
        final int tokenIdx;
        final int sylStart;
        final int sylEnd;
        Anchor(int tokenIdx, int sylStart, int sylEnd) {
            this.tokenIdx = tokenIdx;
            this.sylStart = sylStart;
            this.sylEnd = sylEnd;
        }
    }

    private static String getKanaRomaji(char c) {
        switch (c) {
            case 'あ': case 'ア': return "a";
            case 'い': case 'イ': return "i";
            case 'う': case 'ウ': return "u";
            case 'え': case 'エ': return "e";
            case 'お': case 'オ': return "o";
            case 'か': case 'カ': return "ka";
            case 'き': case 'キ': return "ki";
            case 'く': case 'ク': return "ku";
            case 'け': case 'ケ': return "ke";
            case 'こ': case 'コ': return "ko";
            case 'さ': case 'サ': return "sa";
            case 'し': case 'シ': return "shi";
            case 'す': case 'ス': return "su";
            case 'せ': case 'セ': return "se";
            case 'そ': case 'ソ': return "so";
            case 'た': case 'タ': return "ta";
            case 'ち': case 'チ': return "chi";
            case 'つ': case 'ツ': return "tsu";
            case 'て': case 'テ': return "te";
            case 'と': case 'ト': return "to";
            case 'な': case 'ナ': return "na";
            case 'に': case 'ニ': return "ni";
            case 'ぬ': case 'ヌ': return "nu";
            case 'ね': case 'ネ': return "ne";
            case 'の': case 'ノ': return "no";
            case 'は': case 'ハ': return "ha";
            case 'ひ': case 'ヒ': return "hi";
            case 'ふ': case 'フ': return "fu";
            case 'へ': case 'ヘ': return "he";
            case 'ほ': case 'ホ': return "ho";
            case 'ま': case 'マ': return "ma";
            case 'み': case 'ミ': return "mi";
            case 'む': case 'ム': return "mu";
            case 'め': case 'メ': return "me";
            case 'も': case 'モ': return "mo";
            case 'や': case 'ヤ': return "ya";
            case 'ゆ': case 'ユ': return "yu";
            case 'よ': case 'ヨ': return "yo";
            case 'ら': case 'ラ': return "ra";
            case 'り': case 'リ': return "ri";
            case 'る': case 'ル': return "ru";
            case 'れ': case 'レ': return "re";
            case 'ろ': case 'ロ': return "ro";
            case 'わ': case 'ワ': return "wa";
            case 'を': case 'ヲ': return "wo";
            case 'ん': case 'ン': return "n";
            case 'が': case 'ガ': return "ga";
            case 'ぎ': case 'ギ': return "gi";
            case 'ぐ': case 'グ': return "gu";
            case 'げ': case 'ゲ': return "ge";
            case 'ご': case 'ゴ': return "go";
            case 'ざ': case 'ザ': return "za";
            case 'じ': case 'ジ': return "ji";
            case 'ず': case 'ズ': return "zu";
            case 'ぜ': case 'ゼ': return "ze";
            case 'ぞ': case 'ゾ': return "zo";
            case 'だ': case 'ダ': return "da";
            case 'ぢ': case 'ヂ': return "ji";
            case 'づ': case 'ヅ': return "zu";
            case 'で': case 'デ': return "de";
            case 'ど': case 'ド': return "do";
            case 'ば': case 'バ': return "ba";
            case 'び': case 'ビ': return "bi";
            case 'ぶ': case 'ブ': return "bu";
            case 'べ': case 'ベ': return "be";
            case 'ぼ': case 'ボ': return "bo";
            case 'ぱ': case 'パ': return "pa";
            case 'ぴ': case 'ピ': return "pi";
            case 'ぷ': case 'プ': return "pu";
            case 'ぺ': case 'ペ': return "pe";
            case 'ぽ': case 'ポ': return "po";
            case 'ぁ': case 'ァ': return "a";
            case 'ぃ': case 'ィ': return "i";
            case 'ぅ': case 'ゥ': return "u";
            case 'ぇ': case 'ェ': return "e";
            case 'ぉ': case 'ォ': return "o";
            case 'ゃ': case 'ャ': return "ya";
            case 'ゅ': case 'ュ': return "yu";
            case 'ょ': case 'ョ': return "yo";
            case 'っ': case 'ッ': return "";
            default: return null;
        }
    }

    private static boolean matchKanaOrText(String token, List<LyricsData.Word> syllables, int start, int end) {
        if (token == null || token.isEmpty()) return false;
        String cleanTok = token.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        if (cleanTok.isEmpty()) return false;

        // 1. Check direct Latin text concatenation
        StringBuilder textSb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            LyricsData.Word w = syllables.get(i);
            if (w != null && w.text != null) {
                textSb.append(w.text.replaceAll("[^a-zA-Z0-9]", ""));
            }
        }
        if (cleanTok.equalsIgnoreCase(textSb.toString())) {
            return true;
        }

        // 2. Check Kana phonetic concatenation
        StringBuilder kanaSb = new StringBuilder();
        boolean allKana = true;
        for (int i = start; i <= end; i++) {
            LyricsData.Word w = syllables.get(i);
            if (w == null || w.text == null) {
                allKana = false;
                break;
            }
            String t = w.text.trim();
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                String r = getKanaRomaji(c);
                if (r != null) {
                    kanaSb.append(r);
                } else if (!Character.isWhitespace(c)) {
                    allKana = false;
                    break;
                }
            }
            if (!allKana) break;
        }

        if (allKana && kanaSb.length() > 0) {
            String kanaStr = kanaSb.toString();
            if (cleanTok.equals(kanaStr)) return true;
            // Common particle readings: "は" -> "wa", "を" -> "o", "へ" -> "e"
            if (start == end && syllables.get(start).text != null) {
                String single = syllables.get(start).text.trim();
                if ((single.equals("は") || single.equals("ハ")) && cleanTok.equals("wa")) return true;
                if ((single.equals("を") || single.equals("ヲ")) && cleanTok.equals("o")) return true;
                if ((single.equals("へ") || single.equals("ヘ")) && cleanTok.equals("e")) return true;
            }
        }

        return false;
    }

    public static List<String> alignTokens(List<String> rawTokens, int expectedCount) {
        return alignTokens(rawTokens, expectedCount, null);
    }

    public static List<String> alignTokens(List<String> rawTokens, int expectedCount, List<LyricsData.Word> syllables) {
        if (expectedCount <= 0) return Collections.emptyList();
        if (rawTokens == null || rawTokens.isEmpty()) {
            List<String> res = new ArrayList<>();
            for (int i = 0; i < expectedCount; i++) res.add("");
            return res;
        }

        // 1. If rawTokens already matches expectedCount exactly: 1:1
        if (rawTokens.size() == expectedCount) {
            return new ArrayList<>(rawTokens);
        }

        // 2. If more tokens than expectedCount: group proportionally into expectedCount buckets
        if (rawTokens.size() > expectedCount) {
            List<String> result = new ArrayList<>();
            int total = rawTokens.size();
            for (int i = 0; i < expectedCount; i++) {
                int start = (i * total) / expectedCount;
                int end = ((i + 1) * total) / expectedCount;
                StringBuilder sb = new StringBuilder();
                for (int k = start; k < end; k++) {
                    sb.append(rawTokens.get(k));
                }
                result.add(sb.toString());
            }
            return result;
        }

        // 3. Anchor matching when syllables list is provided
        if (syllables != null && syllables.size() == expectedCount) {
            List<Anchor> anchors = new ArrayList<>();
            int currentSyl = 0;
            int prevToken = -1;
            int n = rawTokens.size();
            for (int t = 0; t < n; t++) {
                String tok = rawTokens.get(t);
                if (tok == null || tok.trim().isEmpty()) continue;
                int foundStart = -1;
                int foundEnd = -1;
                for (int sStart = currentSyl; sStart < expectedCount; sStart++) {
                    if (sStart - currentSyl < (t - prevToken - 1)) {
                        continue;
                    }
                    for (int sEnd = sStart; sEnd < Math.min(expectedCount, sStart + 5); sEnd++) {
                        int remainingSyl = expectedCount - 1 - sEnd;
                        int remainingTok = n - 1 - t;
                        if (remainingSyl < remainingTok) {
                            break;
                        }
                        if (matchKanaOrText(tok, syllables, sStart, sEnd)) {
                            foundStart = sStart;
                            foundEnd = sEnd;
                            break;
                        }
                    }
                    if (foundStart != -1) break;
                }
                if (foundStart != -1) {
                    anchors.add(new Anchor(t, foundStart, foundEnd));
                    currentSyl = foundEnd + 1;
                    prevToken = t;
                }
            }

            if (!anchors.isEmpty()) {
                String[] out = new String[expectedCount];
                int lastSylEnd = -1;
                int lastTokIdx = -1;
                for (Anchor a : anchors) {
                    // Fill gap before anchor
                    int gapTokStart = lastTokIdx + 1;
                    int gapTokEnd = a.tokenIdx;
                    int gapSylStart = lastSylEnd + 1;
                    int gapSylEnd = a.sylStart - 1;
                    int numSyl = gapSylEnd - gapSylStart + 1;
                    if (numSyl > 0) {
                        List<String> gapToks = (gapTokStart < gapTokEnd)
                                ? rawTokens.subList(gapTokStart, gapTokEnd)
                                : Collections.emptyList();
                        List<String> sub = alignTokens(gapToks, numSyl, null);
                        for (int k = 0; k < numSyl; k++) {
                            out[gapSylStart + k] = (k < sub.size()) ? sub.get(k) : "";
                        }
                    }
                    // Fill anchor itself
                    int span = a.sylEnd - a.sylStart + 1;
                    String tok = rawTokens.get(a.tokenIdx);
                    if (span == 1) {
                        out[a.sylStart] = tok;
                    } else {
                        List<String> sub = alignTokens(tok.isEmpty() ? Collections.emptyList() : Collections.singletonList(tok), span, null);
                        for (int k = 0; k < span; k++) {
                            out[a.sylStart + k] = (k < sub.size()) ? sub.get(k) : "";
                        }
                    }
                    lastSylEnd = a.sylEnd;
                    lastTokIdx = a.tokenIdx;
                }
                // Fill trailing gap after last anchor
                int trailTokStart = lastTokIdx + 1;
                int trailTokEnd = n;
                int trailSylStart = lastSylEnd + 1;
                int trailSylEnd = expectedCount - 1;
                int numTrailSyl = trailSylEnd - trailSylStart + 1;
                if (numTrailSyl > 0) {
                    List<String> gapToks = (trailTokStart < trailTokEnd)
                            ? rawTokens.subList(trailTokStart, trailTokEnd)
                            : Collections.emptyList();
                    List<String> sub = alignTokens(gapToks, numTrailSyl, null);
                    for (int k = 0; k < numTrailSyl; k++) {
                        out[trailSylStart + k] = (k < sub.size()) ? sub.get(k) : "";
                    }
                }
                List<String> finalRes = new ArrayList<>(expectedCount);
                for (int i = 0; i < expectedCount; i++) {
                    finalRes.add(out[i] != null ? out[i] : "");
                }
                return finalRes;
            }
        }

        // 4. Fallback: allocate slots based on mora decomposition
        int n = rawTokens.size();
        List<List<String>> morasList = new ArrayList<>(n);
        int totalMoras = 0;
        for (String tok : rawTokens) {
            List<String> m = (tok != null && !tok.isEmpty()) ? splitRomajiIntoMoras(tok) : Collections.emptyList();
            morasList.add(m);
            totalMoras += Math.max(1, m.size());
        }

        List<Integer> slots = new ArrayList<>(n);
        for (List<String> m : morasList) {
            slots.add(Math.max(1, m.size()));
        }

        if (totalMoras > expectedCount) {
            int excess = totalMoras - expectedCount;
            // First reduction: 2-mora words (e.g. 2-mora readings) kept as 1 slot
            for (int i = 0; i < n && excess > 0; i++) {
                if (slots.get(i) == 2) {
                    slots.set(i, 1);
                    excess--;
                }
            }
            // Second reduction: largest multi-mora tokens
            while (excess > 0) {
                int maxIdx = -1;
                int maxVal = 1;
                for (int i = 0; i < n; i++) {
                    if (slots.get(i) > maxVal) {
                        maxVal = slots.get(i);
                        maxIdx = i;
                    }
                }
                if (maxIdx >= 0) {
                    slots.set(maxIdx, maxVal - 1);
                    excess--;
                } else {
                    break;
                }
            }
        } else if (totalMoras < expectedCount) {
            int needed = expectedCount - totalMoras;
            while (needed > 0) {
                int maxIdx = -1;
                int maxLen = -1;
                for (int i = 0; i < n; i++) {
                    int len = rawTokens.get(i) != null ? rawTokens.get(i).length() : 0;
                    if (len > maxLen) {
                        maxLen = len;
                        maxIdx = i;
                    }
                }
                if (maxIdx >= 0) {
                    slots.set(maxIdx, slots.get(maxIdx) + 1);
                    needed--;
                } else {
                    slots.set(0, slots.get(0) + needed);
                    break;
                }
            }
        }

        // Emit tokens into the allocated slots
        List<String> result = new ArrayList<>(expectedCount);
        for (int i = 0; i < n; i++) {
            String tok = rawTokens.get(i);
            if (tok == null) tok = "";
            int targetSlots = slots.get(i);
            List<String> moras = morasList.get(i);

            if (targetSlots <= 1) {
                result.add(tok);
            } else if (moras.size() == targetSlots) {
                result.addAll(moras);
            } else if (moras.size() > targetSlots) {
                int mTotal = moras.size();
                for (int s = 0; s < targetSlots; s++) {
                    int start = (s * mTotal) / targetSlots;
                    int end = ((s + 1) * mTotal) / targetSlots;
                    StringBuilder sb = new StringBuilder();
                    for (int k = start; k < end; k++) {
                        sb.append(moras.get(k));
                    }
                    result.add(sb.toString());
                }
            } else {
                // moras.size() < targetSlots: split text proportionally
                int tLen = tok.length();
                for (int s = 0; s < targetSlots; s++) {
                    int start = (s * tLen) / targetSlots;
                    int end = ((s + 1) * tLen) / targetSlots;
                    result.add(tok.substring(start, end));
                }
            }
        }

        // Safety fallback to ensure exact expectedCount size
        while (result.size() < expectedCount) {
            result.add("");
        }
        if (result.size() > expectedCount) {
            return new ArrayList<>(result.subList(0, expectedCount));
        }

        return result;
    }

    public static void alignSyllables(LyricsData.LyricsLine line, List<String> aiWordsRomanji, String fallbackRomanjiLine) {
        if (line == null) return;
        if (fallbackRomanjiLine != null && !fallbackRomanjiLine.trim().isEmpty()) {
            line.romanji = ScriptDetector.stripNonLatinCharacters(fallbackRomanjiLine).trim();
        }

        if (line.words == null || line.words.isEmpty()) {
            return;
        }

        int expectedCount = line.words.size();

        List<String> rawTokens = new ArrayList<>();
        if (aiWordsRomanji != null && !aiWordsRomanji.isEmpty()) {
            for (String tok : aiWordsRomanji) {
                rawTokens.add(tok != null ? ScriptDetector.stripNonLatinCharacters(tok).trim() : "");
            }
        }
        boolean hasAny = false;
        for (String t : rawTokens) {
            if (!t.isEmpty()) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny && line.romanji != null && !line.romanji.isEmpty()) {
            rawTokens.clear();
            String[] split = line.romanji.split("\\s+");
            for (String s : split) {
                String clean = ScriptDetector.stripNonLatinCharacters(s).trim();
                if (!clean.isEmpty()) {
                    rawTokens.add(clean);
                }
            }
        }

        List<String> aligned = alignTokens(rawTokens, expectedCount, line.words);

        for (int i = 0; i < expectedCount; i++) {
            line.words.get(i).romanji = (i < aligned.size()) ? aligned.get(i) : "";
        }

        if (line.romanji == null || line.romanji.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (LyricsData.Word w : line.words) {
                if (w.romanji != null && !w.romanji.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(w.romanji.trim());
                }
            }
            line.romanji = sb.toString();
        }

        computeWordBoundaries(line);
    }

    private static boolean isWordSeparator(char c) {
        return Character.isWhitespace(c) || c == '-' || c == '\'' || c == '\"' || c == '/'
                || c == ',' || c == '.' || c == '!' || c == '?' || c == '~' || c == ';' || c == ':'
                || c == '—' || c == '–' || c == '…';
    }

    private static boolean isSmallKanaOrChouon(char c) {
        return c == 'っ' || c == 'ッ' || c == 'ゃ' || c == 'ャ'
                || c == 'ゅ' || c == 'ュ' || c == 'ょ' || c == 'ョ'
                || c == 'ぁ' || c == 'ァ' || c == 'ぃ' || c == 'ィ'
                || c == 'ぅ' || c == 'ゥ' || c == 'ぇ' || c == 'ェ'
                || c == 'ぉ' || c == 'ォ' || c == 'ー';
    }

    public static void computeWordBoundaries(LyricsData.LyricsLine line) {
        if (line == null || line.words == null || line.words.isEmpty()) return;
        int n = line.words.size();
        if (line.romanji != null && !line.romanji.isEmpty()) {
            String full = line.romanji.trim();
            int cursor = 0;
            boolean hadAnyMatch = false;
            for (int i = 0; i < n; i++) {
                LyricsData.Word w = line.words.get(i);
                String tok = (w.romanji != null) ? w.romanji.trim() : "";
                if (tok.isEmpty()) {
                    w.isPartOfWord = false;
                    continue;
                }
                while (cursor < full.length() && isWordSeparator(full.charAt(cursor))) {
                    cursor++;
                }

                int matchPos = -1;
                if (cursor + tok.length() <= full.length()
                        && full.substring(cursor, cursor + tok.length()).equalsIgnoreCase(tok)) {
                    matchPos = cursor;
                } else {
                    // Lookahead window up to 20 chars to recover from minor omissions, particles, or punctuation
                    int searchLimit = Math.min(full.length(), cursor + 20);
                    for (int s = cursor; s <= searchLimit - tok.length(); s++) {
                        if (isWordSeparator(full.charAt(s))) continue;
                        if (full.regionMatches(true, s, tok, 0, tok.length())) {
                            matchPos = s;
                            break;
                        }
                    }
                }

                if (matchPos >= 0) {
                    hadAnyMatch = true;
                    cursor = matchPos + tok.length();
                    boolean continuesWord = false;
                    if (cursor < full.length() && i < n - 1) {
                        char nextChar = full.charAt(cursor);
                        if (Character.isLetterOrDigit(nextChar)) {
                            continuesWord = true;
                        }
                    }
                    w.isPartOfWord = continuesWord;
                } else {
                    // Mismatched token: keep as independent boundary, do NOT merge blindly
                    w.isPartOfWord = false;
                }
            }
            if (hadAnyMatch) {
                line.words.get(n - 1).isPartOfWord = false;
                return;
            }
        }

        // Fallback: check text-level script continuity or whitespace
        for (int i = 0; i < n; i++) {
            LyricsData.Word w = line.words.get(i);
            if (i == n - 1) {
                w.isPartOfWord = false;
                continue;
            }
            LyricsData.Word next = line.words.get(i + 1);
            String t = w.text != null ? w.text.trim() : "";
            String nt = next.text != null ? next.text.trim() : "";

            if (w.text != null && (w.text.endsWith(" ") || w.text.endsWith("\u3000"))) {
                w.isPartOfWord = false;
            } else if (w.romanji != null && w.romanji.endsWith(" ")) {
                w.isPartOfWord = false;
            } else if (!t.isEmpty() && !nt.isEmpty()) {
                char nextFirst = nt.charAt(0);
                if (isSmallKanaOrChouon(nextFirst)) {
                    // Small kana / chouon always continues the preceding mora
                    w.isPartOfWord = true;
                } else {
                    w.isPartOfWord = false;
                }
            } else {
                w.isPartOfWord = false;
            }
        }
    }

    public static List<LineTransliterationResult> parseJsonResponse(String jsonText) {
        List<LineTransliterationResult> list = new ArrayList<>();
        if (jsonText == null || jsonText.trim().isEmpty()) return list;

        try {
            String clean = jsonText.trim();
            java.util.regex.Pattern fencePattern = java.util.regex.Pattern.compile(
                    "```(?:json)?\\s*([\\s\\S]*?)\\s*```", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher fenceMatcher = fencePattern.matcher(clean);
            if (fenceMatcher.find()) {
                clean = fenceMatcher.group(1).trim();
            }

            int firstObj = clean.indexOf('{');
            int firstArr = clean.indexOf('[');
            if (firstArr >= 0 && (firstObj < 0 || firstArr < firstObj)) {
                int lastArr = clean.lastIndexOf(']');
                if (lastArr > firstArr) clean = clean.substring(firstArr, lastArr + 1);
            } else if (firstObj >= 0) {
                int lastObj = clean.lastIndexOf('}');
                if (lastObj > firstObj) clean = clean.substring(firstObj, lastObj + 1);
            }

            clean = clean.replaceAll(",\\s*([}\\]])", "$1");

            JsonElement parsed = JsonParser.parseString(clean);
            JsonArray arr = null;
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("lines") && root.get("lines").isJsonArray()) {
                    arr = root.getAsJsonArray("lines");
                }
            } else if (parsed.isJsonArray()) {
                arr = parsed.getAsJsonArray();
            }

            if (arr != null) {
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    LineTransliterationResult item = new LineTransliterationResult();
                    item.index = (obj.has("index") && !obj.get("index").isJsonNull()) ? obj.get("index").getAsInt() : -1;
                    item.romanji = (obj.has("romanji") && !obj.get("romanji").isJsonNull())
                            ? ScriptDetector.stripNonLatinCharacters(obj.get("romanji").getAsString()) : "";
                    item.translation = (obj.has("translation") && !obj.get("translation").isJsonNull())
                            ? obj.get("translation").getAsString() : "";

                    if (obj.has("wordsRomanji") && !obj.get("wordsRomanji").isJsonNull() && obj.get("wordsRomanji").isJsonArray()) {
                        JsonArray wr = obj.getAsJsonArray("wordsRomanji");
                        for (JsonElement elWord : wr) {
                            String rawToken = elWord != null && !elWord.isJsonNull() ? elWord.getAsString() : "";
                            item.wordsRomanji.add(ScriptDetector.stripNonLatinCharacters(rawToken));
                        }
                    }
                    list.add(item);
                }
            }
        } catch (Exception e) {
            try {
                Log.e(TAG, "Error parsing AI transliteration JSON", e);
            } catch (Throwable ignored) {}
        }
        return list;
    }

    public static String buildPromptPayload(String title, String artist, List<LyricsData.LyricsLine> batchLines, String targetLang) {
        return buildPromptPayload(title, artist, batchLines, targetLang, true);
    }

    public static String buildPromptPayload(String title, String artist, List<LyricsData.LyricsLine> batchLines, String targetLang, boolean includeTranslation) {
        String safeTargetLang = (targetLang != null && !targetLang.trim().isEmpty()) ? targetLang.trim() : "id";
        JsonObject promptObj = new JsonObject();
        promptObj.addProperty("title", title != null ? title : "");
        promptObj.addProperty("artist", artist != null ? artist : "");
        promptObj.addProperty("targetLanguage", safeTargetLang);
        promptObj.addProperty("includeTranslation", includeTranslation);

        JsonArray linesArr = new JsonArray();
        if (batchLines != null) {
            for (int i = 0; i < batchLines.size(); i++) {
                LyricsData.LyricsLine line = batchLines.get(i);
                JsonObject lineObj = new JsonObject();
                lineObj.addProperty("index", i);
                lineObj.addProperty("text", line != null && line.text != null ? line.text : "");

                JsonArray syllArr = new JsonArray();
                if (line != null && line.words != null) {
                    for (LyricsData.Word w : line.words) {
                        syllArr.add(w != null && w.text != null ? w.text : "");
                    }
                }
                lineObj.addProperty("syllableCount", syllArr.size());
                lineObj.add("syllables", syllArr);
                linesArr.add(lineObj);
            }
        }
        promptObj.add("lines", linesArr);

        String translationRule = includeTranslation
                ? "2. For each line, translate the meaning accurately into targetLanguage (" + safeTargetLang + ").\n"
                : "2. The user requested transliteration ONLY. Leave \"translation\" as empty string \"\" for all lines.\n";

        return "You are an expert multilingual song lyrics transliteration and translation engine.\n" +
                "Given song lines with syllable segmentation, output a strict JSON object with a \"lines\" array.\n" +
                "Rules:\n" +
                "1. Transliterate non-Latin scripts (Japanese Kanji/Kana to Hepburn Romaji, Chinese Hanzi to Pinyin, Korean to Revised Romanization, Russian to Latin). Output 'romanji' and 'wordsRomanji' in STRICT LATIN CHARACTERS ONLY. Under no circumstances should original Japanese Kanji/Kana or Chinese characters appear in transliteration fields.\n" +
                translationRule +
                "3. CRITICAL: For \"wordsRomanji\", you MUST provide an array of strings of EXACTLY THE SAME LENGTH as the input \"syllables\" array (wordsRomanji.length == syllableCount).\n" +
                "   - Each element in \"wordsRomanji\" corresponds 1:1 to the syllable or character at the exact same index in \"syllables\".\n" +
                "   - For Japanese: decompose Kanji words and compounds into individual character/mora readings. For example, if \"syllables\" is [\"君\", \"の\", \"前\", \"前\", \"前\", \"世\", \"か\", \"ら\", \"僕\", \"は\"] (length 10), \"wordsRomanji\" MUST be [\"kimi\", \"no\", \"zen\", \"zen\", \"zen\", \"se\", \"ka\", \"ra\", \"boku\", \"wa\"] (length 10). If \"syllables\" is [\"あ\", \"な\", \"た\", \"の\"] (length 4), \"wordsRomanji\" MUST be [\"a\", \"na\", \"ta\", \"no\"] (length 4). NEVER combine multiple syllables into one token.\n" +
                "   - For Chinese: each Hanzi character gets its Pinyin syllable.\n" +
                "   - For Korean: each Hangul syllable block gets its Romanized syllable.\n" +
                "   - If input syllable is already Latin, keep it as is.\n" +
                "Output ONLY valid JSON with schema:\n" +
                "{\n" +
                "  \"lines\": [\n" +
                "    {\n" +
                "      \"index\": 0,\n" +
                "      \"romanji\": \"full line transliteration\",\n" +
                "      \"translation\": \"full line translation\",\n" +
                "      \"wordsRomanji\": [\"syllable1\", \"syllable2\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "Payload:\n" +
                promptObj.toString();
    }

    public AntigravityApiClient.Cancellable transliterateSyllablesAsync(
            final LyricsData data,
            final String trackTitle,
            final String artist,
            final String targetLang,
            final Callback callback) {
        return transliterateSyllablesAsync(data, trackTitle, artist, targetLang, true, callback);
    }

    public AntigravityApiClient.Cancellable transliterateSyllablesAsync(
            final LyricsData data,
            final String trackTitle,
            final String artist,
            final String targetLang,
            final boolean includeTranslation,
            final Callback callback) {
        final BatchCancellable compositeCancellable = new BatchCancellable();

        if (data == null || data.lines == null || data.lines.isEmpty()) {
            dispatchError("Lyrics data is empty", callback);
            return compositeCancellable;
        }

        if (apiClient == null) {
            dispatchError("AntigravityApiClient is null", callback);
            return compositeCancellable;
        }

        final List<LyricsData.LyricsLine> targetLines = new ArrayList<>();
        for (LyricsData.LyricsLine line : data.lines) {
            if (line != null && !line.isInterlude && line.text != null && !line.text.trim().isEmpty()) {
                targetLines.add(line);
            }
        }

        if (targetLines.isEmpty()) {
            data.hasTransliteration = true;
            data.sourceScript = ScriptDetector.detectScript(collectAllLinesText(data)).name();
            dispatchSuccess(data, callback);
            return compositeCancellable;
        }

        final int totalLines = targetLines.size();
        final List<List<LyricsData.LyricsLine>> batches = new ArrayList<>();
        for (int i = 0; i < totalLines; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, totalLines);
            batches.add(new ArrayList<>(targetLines.subList(i, end)));
        }

        processBatch(0, batches, 0, totalLines, data, trackTitle, artist, targetLang, includeTranslation, callback, compositeCancellable);
        return compositeCancellable;
    }

    private void processBatch(
            final int batchIndex,
            final List<List<LyricsData.LyricsLine>> batches,
            final int processedSoFar,
            final int totalLines,
            final LyricsData data,
            final String trackTitle,
            final String artist,
            final String targetLang,
            final boolean includeTranslation,
            final Callback callback,
            final BatchCancellable cancellable) {
        if (cancellable.isCancelled()) {
            return;
        }

        if (batchIndex >= batches.size()) {
            data.sourceScript = ScriptDetector.detectScript(collectAllLinesText(data)).name();
            data.hasTransliteration = true;

            if (cacheManager != null) {
                try {
                    TrackInfo trackInfo = new TrackInfo();
                    trackInfo.title = trackTitle != null ? trackTitle : "";
                    trackInfo.artist = artist != null ? artist : "";
                    cacheManager.cache(trackInfo, data);
                } catch (Exception e) {
                    try {
                        Log.e(TAG, "Failed to cache transliterated lyrics", e);
                    } catch (Throwable ignored) {}
                }
            }

            dispatchSuccess(data, callback);
            return;
        }

        final List<LyricsData.LyricsLine> currentBatch = batches.get(batchIndex);
        String prompt = buildPromptPayload(trackTitle, artist, currentBatch, targetLang, includeTranslation);
        List<AiChatMessage> messages = Collections.singletonList(AiChatMessage.user(prompt));

        AntigravityApiClient.Cancellable call = apiClient.generateContent(
                DEFAULT_MODEL,
                messages,
                false,
                "low",
                new AntigravityApiClient.CompletionCallback() {
                    @Override
                    public void onComplete(String fullText, List<String> webSources, int promptTokens, int candidateTokens) {
                        if (cancellable.isCancelled()) {
                            return;
                        }

                        List<LineTransliterationResult> results = parseJsonResponse(fullText);
                        for (int i = 0; i < currentBatch.size(); i++) {
                            LyricsData.LyricsLine line = currentBatch.get(i);
                            LineTransliterationResult match = findResultForIndex(results, i);
                            if (match != null) {
                                if (includeTranslation && match.translation != null && !match.translation.trim().isEmpty()) {
                                    line.translation = match.translation.trim();
                                }
                                alignSyllables(line, match.wordsRomanji, match.romanji);
                            }
                        }

                        int updatedProcessed = processedSoFar + currentBatch.size();
                        dispatchProgress(updatedProcessed, totalLines, callback);

                        processBatch(batchIndex + 1, batches, updatedProcessed, totalLines,
                                data, trackTitle, artist, targetLang, includeTranslation, callback, cancellable);
                    }

                    @Override
                    public void onError(Exception error) {
                        if (cancellable.isCancelled()) {
                            return;
                        }
                        String msg = error != null && error.getMessage() != null ? error.getMessage() : "Unknown error";
                        dispatchError("AI Transliteration failed: " + msg, callback);
                    }
                });

        cancellable.setCurrentCall(call);
    }

    private static String collectAllLinesText(LyricsData data) {
        if (data == null || data.lines == null) return "";
        StringBuilder sb = new StringBuilder();
        for (LyricsData.LyricsLine line : data.lines) {
            if (line != null && line.text != null) {
                sb.append(line.text).append(" ");
            }
        }
        return sb.toString();
    }

    private static LineTransliterationResult findResultForIndex(List<LineTransliterationResult> results, int index) {
        if (results == null || results.isEmpty()) return null;
        for (LineTransliterationResult r : results) {
            if (r.index == index) {
                return r;
            }
        }
        boolean hasValidIndices = false;
        for (LineTransliterationResult r : results) {
            if (r.index >= 0) {
                hasValidIndices = true;
                break;
            }
        }
        if (!hasValidIndices && index >= 0 && index < results.size()) {
            return results.get(index);
        }
        return null;
    }

    private void dispatchSuccess(final LyricsData data, final Callback callback) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onSuccess(data));
        } else {
            callback.onSuccess(data);
        }
    }

    private void dispatchError(final String message, final Callback callback) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onError(message));
        } else {
            callback.onError(message);
        }
    }

    private void dispatchProgress(final int processed, final int total, final Callback callback) {
        if (callback == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> callback.onProgress(processed, total));
        } else {
            callback.onProgress(processed, total);
        }
    }

    private static class BatchCancellable implements AntigravityApiClient.Cancellable {
        private volatile boolean cancelled = false;
        private AntigravityApiClient.Cancellable currentCall;

        public synchronized void setCurrentCall(AntigravityApiClient.Cancellable call) {
            this.currentCall = call;
            if (cancelled && call != null) {
                call.cancel();
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            if (currentCall != null) {
                currentCall.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
