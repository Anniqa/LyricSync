package com.lyricsync.app.ai.transliteration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AiSyllableTransliterationServiceTest {

    @Test
    public void exactAnswerMapsOneToOne() {
        LyricsData.LyricsLine line = line("前", "前", "前", "世");
        AiSyllableTransliterationService.alignSyllables(line, Arrays.asList("zen", "zen", "zen", "se"), "zenzenzense");
        assertEquals(Arrays.asList("zen", "zen", "zen", "se"), readings(line));
        assertEquals("zenzenzense", line.romanji);
    }

    @Test
    public void wordLevelAnswerIsSplitOntoKanaSyllables() {
        LyricsData.LyricsLine line = line("あ", "な", "た", "の", "こ", "と", "が", "す", "き", "だ");
        AiSyllableTransliterationService.alignSyllables(line,
                Arrays.asList("anata", "no", "koto", "ga", "sukida"), "anata no koto ga suki da");
        assertEquals(Arrays.asList("a", "na", "ta", "no", "ko", "to", "ga", "su", "ki", "da"), readings(line));
    }

    @Test
    public void kanaAnchorsKeepKanjiReadingsInPlace() {
        LyricsData.LyricsLine line = line("君", "の", "前", "前", "前", "世", "か", "ら", "僕", "は");
        AiSyllableTransliterationService.alignSyllables(line,
                Arrays.asList("kimi", "no", "zenzenzense", "kara", "boku", "wa"), "kimi no zenzenzense kara boku wa");
        assertEquals(Arrays.asList("kimi", "no", "zen", "zen", "zen", "se", "ka", "ra", "boku", "wa"), readings(line));
    }

    @Test
    public void tooManyTokensAreGrouped() {
        LyricsData.LyricsLine line = line("君の", "前世");
        AiSyllableTransliterationService.alignSyllables(line, Arrays.asList("kimi", "no", "zen", "se"), null);
        assertEquals(Arrays.asList("kimino", "zense"), readings(line));
    }

    @Test
    public void wordBoundariesFollowTheLineReading() {
        LyricsData.LyricsLine line = line("中", "国", "亲");
        line.words.get(0).romanji = "zhong";
        line.words.get(1).romanji = "guo";
        line.words.get(2).romanji = "qin";
        line.romanji = "zhongguo qin";
        AiSyllableTransliterationService.computeWordBoundaries(line);
        assertTrue(line.words.get(0).isPartOfWord);
        assertFalse(line.words.get(1).isPartOfWord);
        assertFalse(line.words.get(2).isPartOfWord);
    }

    @Test
    public void promptAsksForOneReadingPerSyllable() {
        String p = AiSyllableTransliterationService.buildPrompt("Sparkle", "RADWIMPS", Arrays.asList(line("スパー", "クル")), null);
        assertTrue(p.contains("Sparkle by RADWIMPS"));
        assertTrue(p.contains("\"syllables\":[\"スパー\",\"クル\"]"));
        assertFalse(p.contains("\"translation\""));
        assertTrue(AiSyllableTransliterationService.buildPrompt("t", "a", Arrays.asList(line("前")), "Indonesian")
                .contains("Indonesian translation"));
    }

    @Test
    public void serviceFillsReadingsAndMarksTheLyrics() {
        LyricsData data = lyrics(line("スパー", "クル"));
        FakeApi api = new FakeApi("```json\n{\"lines\":[{\"index\":0,\"romanized\":\"supaakuru\","
                + "\"syllables\":[\"supaa\",\"kuru\"],\"translation\":\"x\"}]}\n```");
        Outcome out = run(api, data, null);
        assertTrue(out.ok);
        assertTrue(data.hasTransliteration);
        assertEquals(Arrays.asList("supaa", "kuru"), readings(data.lines.get(0)));
        assertNull("no translation unless asked", data.lines.get(0).translation);
    }

    @Test
    public void badAnswerIsRetriedOnceThenFails() {
        FakeApi api = new FakeApi("not json");
        Outcome out = run(api, lyrics(line("前")), null);
        assertEquals(2, api.calls);
        assertFalse(out.ok);
        assertTrue(out.error != null);

        FakeApi down = new FakeApi(null);
        down.error = new IOException("down");
        assertFalse(run(down, lyrics(line("前")), null).ok);
        assertEquals(2, down.calls);
    }

    @Test
    public void cancelStopsDelivery() {
        FakeApi api = new FakeApi(null);
        api.hold = true;
        Outcome out = new Outcome();
        AntigravityApiClient.Cancellable job = new AiSyllableTransliterationService(api, null)
                .transliterateSyllablesAsync(lyrics(line("前")), "t", "a", null, out);
        job.cancel();
        api.callback.onComplete("{\"lines\":[{\"index\":0,\"syllables\":[\"zen\"]}]}", null, 0, 0);
        assertFalse(out.ok);
        assertNull(out.error);
    }

    // ── helpers ──

    private static LyricsData.LyricsLine line(String... syllables) {
        LyricsData.LyricsLine l = new LyricsData.LyricsLine(0, syllables.length * 300L, String.join("", syllables));
        for (int i = 0; i < syllables.length; i++) {
            l.words.add(new LyricsData.Word(i * 300L, (i + 1) * 300L, syllables[i]));
        }
        return l;
    }

    private static LyricsData lyrics(LyricsData.LyricsLine... lines) {
        LyricsData d = new LyricsData(LyricsData.Type.SYLLABLE);
        d.lines.addAll(Arrays.asList(lines));
        return d;
    }

    private static List<String> readings(LyricsData.LyricsLine line) {
        List<String> r = new ArrayList<>();
        for (LyricsData.Word w : line.words) r.add(w.romanji);
        return r;
    }

    private static Outcome run(FakeApi api, LyricsData data, String translateTo) {
        Outcome out = new Outcome();
        new AiSyllableTransliterationService(api, null).transliterateSyllablesAsync(data, "t", "a", translateTo, out);
        return out;
    }

    private static final class Outcome implements AiSyllableTransliterationService.Callback {
        boolean ok;
        String error;

        @Override
        public void onSuccess(LyricsData enrichedData) {
            ok = true;
        }

        @Override
        public void onError(String message) {
            error = message;
        }
    }

    private static final class FakeApi extends AntigravityApiClient {
        final String answer;
        Exception error;
        boolean hold;
        int calls;
        CompletionCallback callback;

        FakeApi(String answer) {
            super(null, null);
            this.answer = answer;
        }

        @Override
        public Cancellable generateContent(String modelId, List<AiChatMessage> messages, boolean web,
                                           String effort, CompletionCallback cb) {
            calls++;
            callback = cb;
            Cancellable none = new Cancellable() {
                @Override public void cancel() {}
                @Override public boolean isCancelled() { return false; }
            };
            if (hold) return none;
            if (error != null) cb.onError(error);
            else cb.onComplete(answer, null, 0, 0);
            return none;
        }
    }
}
