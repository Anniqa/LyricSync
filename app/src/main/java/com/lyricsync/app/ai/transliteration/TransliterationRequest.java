package com.lyricsync.app.ai.transliteration;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a transliteration request should show: the Latin line is always on; the original
 * script and the translation are each on, off, or left as they are (null). One place
 * for the overlay chips, the AI tool and plain chat commands ("romaji saja").
 */
public final class TransliterationRequest {

    /** null = leave the current setting. */
    public final Boolean showOriginal;
    /** null = leave the current setting. */
    public final Boolean showTranslation;

    public TransliterationRequest(Boolean showOriginal, Boolean showTranslation) {
        this.showOriginal = showOriginal;
        this.showTranslation = showTranslation;
    }

    /** Latin + original + translation. */
    public static final TransliterationRequest WITH_TRANSLATION = new TransliterationRequest(true, true);
    /** Latin above the original, no translation. */
    public static final TransliterationRequest WITH_ORIGINAL = new TransliterationRequest(true, false);
    /** Latin only: no original script, no translation. */
    public static final TransliterationRequest LATIN_ONLY = new TransliterationRequest(false, false);
    /** Just turn the Latin line on. */
    public static final TransliterationRequest KEEP = new TransliterationRequest(null, null);

    private static final Pattern TRIGGER = Pattern.compile(
            "\\b(romaji|romanji|romaja|romanisasi|pinyin|latin|transliterasi|transliterate|transliteration)\\b");
    /** "saja", "aja", "doang", "only", "hanya": nothing but Latin. */
    private static final Pattern ONLY = Pattern.compile("\\b(saja|aja|doang|only|hanya|cuma|just)\\b");
    private static final Pattern HIDE_ORIGINAL = Pattern.compile(
            "(tanpa|hilangkan|sembunyikan|buang|hapus|hide|without|no)\\s+(huruf|aksara|tulisan|kanji|hangul|hanzi|original|jepang|korea|mandarin|cina|china)"
                    + "|(jepang|korea|kanji|hangul|aksara asli|huruf asli)\\s+(buang|hapus|hilangkan)");
    private static final Pattern KEEP_ORIGINAL = Pattern.compile(
            "(dengan|pakai|tetap|plus|with|keep)\\s+(huruf|aksara|tulisan)\\s*(asli|aslinya)?|with original|keep original");
    private static final Pattern TRANSLATION = Pattern.compile(
            "\\b(arti|artinya|terjemah\\w*|translat\\w*|makna|meaning)\\b");
    private static final Pattern NO_TRANSLATION = Pattern.compile(
            "(tanpa|without|no|gak|ga|nggak|tidak|jangan)\\s+(pakai\\s+)?(arti|artinya|terjemah\\w*|translat\\w*|makna|meaning)");
    private static final int MAX_COMMAND_WORDS = 10;

    /**
     * A chat message that is a display command ("romaji saja", "latin tanpa huruf asli",
     * "romaji dengan arti"), or null for anything else — including questions ("apa arti
     * romaji ini?"), which go to the AI as usual.
     */
    public static TransliterationRequest fromChat(String message) {
        if (message == null) return null;
        String m = message.toLowerCase(Locale.ROOT).trim();
        if (m.isEmpty() || m.contains("?") || m.split("\\s+").length > MAX_COMMAND_WORDS) return null;
        if (!TRIGGER.matcher(m).find()) return null;

        boolean noTranslation = NO_TRANSLATION.matcher(m).find();
        boolean translation = !noTranslation && TRANSLATION.matcher(m).find();
        boolean keepOriginal = KEEP_ORIGINAL.matcher(m).find();
        boolean hideOriginal = !keepOriginal && (HIDE_ORIGINAL.matcher(m).find() || ONLY.matcher(m).find());

        Boolean showTranslation = translation ? Boolean.TRUE
                : (noTranslation || ONLY.matcher(m).find()) ? Boolean.FALSE : null;
        Boolean showOriginal = hideOriginal ? Boolean.FALSE : keepOriginal ? Boolean.TRUE : null;
        // Without any modifier only a bare "romaji"/"tampilkan romaji" is a command;
        // a longer sentence ("ceritakan musik latin") is a question for the AI.
        if (showOriginal == null && showTranslation == null && m.split("\\s+").length > 3) return null;
        return new TransliterationRequest(showOriginal, showTranslation);
    }

    /**
     * The AI tool's arguments. mode: "romaji_with_translation", "romaji_only" (Latin above
     * the original) or "pure_romaji"/"latin_only" (Latin alone). An explicit
     * show_original_script wins over the mode.
     */
    public static TransliterationRequest fromTool(String mode, Boolean showOriginalScript) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        TransliterationRequest base;
        switch (m) {
            case "romaji_with_translation":
                base = WITH_TRANSLATION;
                break;
            case "romaji_only":
                base = WITH_ORIGINAL;
                break;
            case "pure_romaji":
            case "latin_only":
                base = LATIN_ONLY;
                break;
            default:
                base = KEEP;
        }
        return showOriginalScript == null ? base : new TransliterationRequest(showOriginalScript, base.showTranslation);
    }

    /** Short description (Indonesian) for the model's tool result and logs. */
    public String describe() {
        if (Boolean.FALSE.equals(showOriginal) && !Boolean.TRUE.equals(showTranslation)) return "Latin saja";
        StringBuilder sb = new StringBuilder("Latin");
        if (!Boolean.FALSE.equals(showOriginal)) sb.append(" + aksara asli");
        if (Boolean.TRUE.equals(showTranslation)) sb.append(" + terjemahan");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TransliterationRequest)) return false;
        TransliterationRequest r = (TransliterationRequest) o;
        return java.util.Objects.equals(showOriginal, r.showOriginal)
                && java.util.Objects.equals(showTranslation, r.showTranslation);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(showOriginal, showTranslation);
    }

    @Override
    public String toString() {
        return "TransliterationRequest{original=" + showOriginal + ", translation=" + showTranslation + "}";
    }
}
