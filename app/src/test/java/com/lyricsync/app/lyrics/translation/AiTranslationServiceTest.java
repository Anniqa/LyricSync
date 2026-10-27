package com.lyricsync.app.lyrics.translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.lyricsync.app.ai.auth.AntigravityAuthManager;
import com.lyricsync.app.ai.client.AntigravityApiClient;
import com.lyricsync.app.ai.model.AiChatMessage;
import com.lyricsync.app.lyrics.model.LyricsData;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AiTranslationServiceTest {

    @Test
    public void promptNamesTheLanguageAndCarriesEveryLine() {
        String prompt = AiTranslationService.buildPrompt(lines("He said \"hi\"", "Bye"), "id");
        assertTrue(prompt.contains("Indonesian (id)"));
        assertTrue(prompt.contains("{\"id\":0,\"text\":\"He said \\\"hi\\\"\"}"));
        assertTrue(prompt.contains("{\"id\":1,\"text\":\"Bye\"}"));
    }

    @Test
    public void applyWritesTranslationsByIdThroughFencesAndChatter() {
        List<LyricsData.LyricsLine> l = lines("one", "two");
        GoogleTranslateParser.ParseResult r = AiTranslationService.apply(
                "Sure:\n```json\n{\"sourceLanguage\":\"en\",\"lines\":[{\"id\":1,\"text\":\"dua\"},{\"id\":0,\"text\":\"satu\"}]}\n```",
                l, "id");
        assertEquals(2, r.translatedLineCount);
        assertEquals("satu", l.get(0).translation);
        assertEquals("dua", l.get(1).translation);
    }

    @Test
    public void applyRejectsPartialOrBrokenAnswersWithoutTouchingLines() {
        List<LyricsData.LyricsLine> l = lines("a", "b", "c", "d", "e");
        assertNull(AiTranslationService.apply("{\"lines\":[{\"id\":0,\"text\":\"x\"}]}", l, "id"));
        assertNull(AiTranslationService.apply("Maaf, saya tidak bisa.", l, "id"));
        assertNull(l.get(0).translation);
    }

    @Test
    public void applyReportsSameLanguage() {
        GoogleTranslateParser.ParseResult r = AiTranslationService.apply(
                "{\"sourceLanguage\":\"ID\",\"lines\":[]}", lines("aku"), "id");
        assertTrue(r.isSameLanguage);
    }

    @Test
    public void signedOutGoesStraightToGoogle() {
        FakeApi api = new FakeApi("{}");
        FakeGoogle google = new FakeGoogle();
        service(api, google, false, 1000).translateLyrics(lines("hi"), "id", new Result());
        assertEquals(0, api.calls);
        assertEquals(1, google.calls);
    }

    @Test
    public void aiAnswerIsUsedAndGoogleIsNot() {
        FakeApi api = new FakeApi("{\"sourceLanguage\":\"en\",\"lines\":[{\"id\":0,\"text\":\"halo\"}]}");
        FakeGoogle google = new FakeGoogle();
        Result result = new Result();
        List<LyricsData.LyricsLine> l = lines("hello");
        service(api, google, true, 1000).translateLyrics(l, "id", result);
        assertEquals(1, result.successes);
        assertEquals("halo", l.get(0).translation);
        assertEquals(0, google.calls);
    }

    @Test
    public void errorOrUnusableAnswerFallsBackOnce() {
        FakeApi api = new FakeApi(null);
        api.error = new IOException("down");
        FakeGoogle google = new FakeGoogle();
        service(api, google, true, 1000).translateLyrics(lines("hi"), "id", new Result());
        assertEquals(1, google.calls);

        FakeGoogle google2 = new FakeGoogle();
        service(new FakeApi("no json"), google2, true, 1000).translateLyrics(lines("hi"), "id", new Result());
        assertEquals(1, google2.calls);
    }

    @Test
    public void timeoutCancelsTheCallAndFallsBack() throws Exception {
        FakeApi api = new FakeApi(null);
        api.hold = true;
        FakeGoogle google = new FakeGoogle();
        service(api, google, true, 50).translateLyrics(lines("hi"), "id", new Result());
        assertTrue(google.called.await(2, TimeUnit.SECONDS));
        assertTrue(api.handle.cancelled);
    }

    @Test
    public void lateAnswerOfASupersededRequestIsIgnored() {
        FakeApi api = new FakeApi(null);
        api.hold = true;
        FakeGoogle google = new FakeGoogle();
        AiTranslationService s = service(api, google, true, 1000);
        Result first = new Result();
        s.translateLyrics(lines("old"), "id", first);
        AntigravityApiClient.CompletionCallback stale = api.callback;
        s.translateLyrics(lines("new"), "id", new Result());
        stale.onComplete("{\"lines\":[{\"id\":0,\"text\":\"lama\"}]}", null, 0, 0);
        assertEquals(0, first.successes);
        stale.onError(new IOException("late"));
        assertEquals(0, google.calls);
    }

    // ── helpers ──

    private static List<LyricsData.LyricsLine> lines(String... texts) {
        List<LyricsData.LyricsLine> l = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) l.add(new LyricsData.LyricsLine(i * 1000L, i * 1000L + 900, texts[i]));
        return l;
    }

    private static AiTranslationService service(FakeApi api, FakeGoogle google, boolean signedIn, long timeoutMs) {
        AntigravityAuthManager auth = new AntigravityAuthManager((android.content.SharedPreferences) null) {
            @Override
            public boolean isAuthenticated() {
                return signedIn;
            }
        };
        return new AiTranslationService(null, auth, api, google, timeoutMs, null);
    }

    private static final class Result implements GoogleTranslateService.TranslationCallback {
        int successes;

        @Override
        public void onSuccess(GoogleTranslateParser.ParseResult result, String targetLang) {
            successes++;
        }

        @Override
        public void onError(Exception error) {}
    }

    private static final class FakeGoogle extends GoogleTranslateService {
        int calls;
        final CountDownLatch called = new CountDownLatch(1);

        FakeGoogle() {
            super(null, null);
        }

        @Override
        public synchronized void translateLines(List<LyricsData.LyricsLine> allLines, String targetLang,
                                                TranslationCallback callback) {
            calls++;
            called.countDown();
        }

        @Override
        public synchronized void cancel() {}
    }

    private static final class Handle implements AntigravityApiClient.Cancellable {
        volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class FakeApi extends AntigravityApiClient {
        final String answer;
        Exception error;
        boolean hold;
        int calls;
        Handle handle;
        CompletionCallback callback;

        FakeApi(String answer) {
            super(null, null);
            this.answer = answer;
        }

        @Override
        public Cancellable generateContent(String modelId, List<AiChatMessage> messages, boolean web,
                                           String effort, CompletionCallback cb) {
            calls++;
            handle = new Handle();
            callback = cb;
            if (hold) return handle;
            if (error != null) cb.onError(error);
            else cb.onComplete(answer, null, 0, 0);
            return handle;
        }
    }
}
