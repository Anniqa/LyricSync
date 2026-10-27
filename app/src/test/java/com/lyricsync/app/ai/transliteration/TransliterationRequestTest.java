package com.lyricsync.app.ai.transliteration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Chat commands and tool modes → what the overlay shows. */
public class TransliterationRequestTest {

    private static final TransliterationRequest LATIN_ONLY = TransliterationRequest.LATIN_ONLY;

    @Test
    public void onlyMeansLatinAlone() {
        assertEquals(LATIN_ONLY, TransliterationRequest.fromChat("romaji saja"));
        assertEquals(LATIN_ONLY, TransliterationRequest.fromChat("Romanji aja"));
        assertEquals(LATIN_ONLY, TransliterationRequest.fromChat("latin doang"));
        assertEquals(LATIN_ONLY, TransliterationRequest.fromChat("romaji only"));
        assertEquals(LATIN_ONLY, TransliterationRequest.fromChat("hanya pinyin"));
    }

    @Test
    public void hideOriginalScript() {
        assertEquals(Boolean.FALSE, TransliterationRequest.fromChat("romaji tanpa huruf asli").showOriginal);
        assertEquals(Boolean.FALSE, TransliterationRequest.fromChat("romaji, jepang buang").showOriginal);
    }

    @Test
    public void withTranslationOrOriginal() {
        assertEquals(TransliterationRequest.WITH_TRANSLATION.showTranslation,
                TransliterationRequest.fromChat("romaji dengan arti").showTranslation);
        TransliterationRequest keep = TransliterationRequest.fromChat("romaji saja tapi tetap huruf asli");
        assertEquals(Boolean.TRUE, keep.showOriginal);
        assertEquals(Boolean.FALSE, keep.showTranslation);
        assertEquals(Boolean.FALSE, TransliterationRequest.fromChat("romaji tanpa arti").showTranslation);
    }

    @Test
    public void bareCommandKeepsOtherSettings() {
        assertEquals(TransliterationRequest.KEEP, TransliterationRequest.fromChat("tampilkan romaji"));
    }

    @Test
    public void questionsGoToTheAi() {
        assertNull(TransliterationRequest.fromChat("apa arti romaji di baris ini?"));
        assertNull(TransliterationRequest.fromChat("ceritakan sejarah musik latin di amerika"));
        assertNull(TransliterationRequest.fromChat("siapa penyanyinya"));
        assertNull(TransliterationRequest.fromChat(null));
    }

    @Test
    public void toolModes() {
        assertEquals(LATIN_ONLY, TransliterationRequest.fromTool("pure_romaji", null));
        assertEquals(TransliterationRequest.WITH_ORIGINAL, TransliterationRequest.fromTool("romaji_only", null));
        assertEquals(TransliterationRequest.WITH_TRANSLATION, TransliterationRequest.fromTool("romaji_with_translation", null));
        // An explicit flag beats the mode.
        assertEquals(Boolean.FALSE, TransliterationRequest.fromTool("romaji_only", false).showOriginal);
    }
}
