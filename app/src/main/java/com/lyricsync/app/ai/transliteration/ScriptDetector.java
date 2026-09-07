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

    private static final Pattern NON_LATIN_CHARS = Pattern.compile(
            "[\\u3040-\\u30FF\\u4E00-\\u9FAF\\uAC00-\\uD7AF\\u1100-\\u11FF\\u0400-\\u04FF\\u0600-\\u06FF\\u0E00-\\u0E7F\\u0370-\\u03FF]"
    );

    /**
     * Strips all non-Latin characters (Kanji, Kana, Hanzi, Hangul, Cyrillic, etc.)
     * and leftover wrapper punctuation, leaving strictly Latin transliteration text.
     */
    public static String stripNonLatinCharacters(String text) {
        if (text == null) return "";
        String stripped = NON_LATIN_CHARS.matcher(text).replaceAll("").trim();
        stripped = stripped.replace("()", "").replace("（）", "").replace("[]", "").replace("【】", "");
        stripped = stripped.replaceAll("^[\\(\\（\\[\\【]+|[\\)\\）\\]\\】]+$", "").trim();
        return stripped;
    }
}
